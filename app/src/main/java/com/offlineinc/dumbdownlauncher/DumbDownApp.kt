package com.offlineinc.dumbdownlauncher

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatDelegate
import com.offlineinc.dumbdownlauncher.coverdisplay.CoverDisplayService
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import com.offlineinc.dumbdownlauncher.quack.LocationConsent
import com.offlineinc.dumbdownlauncher.quack.LocationPermissionGranter
import com.offlineinc.dumbdownlauncher.quack.QuackLocationHelper
import com.offlineinc.dumbdownlauncher.quack.QuackLocationRefreshWorker
import com.offlineinc.dumbdownlauncher.registration.DeviceRegistrar
import com.offlineinc.dumbdownlauncher.registration.SimInfoReader
import com.offlineinc.dumbdownlauncher.update.UpdateCheckWorker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DumbDownApp : Application() {
    companion object {
        /**
         * How long to wait after Application.onCreate before running one-time
         * migrations. Chosen to let the modem finish SIM init (~20–30s on
         * TCL/MediaTek), first-frame render, and WorkManager/accessibility
         * setup all complete first. The migration thread then has the su
         * daemon and disk largely to itself.
         */
        private const val MIGRATION_BOOT_DELAY_MS: Long = 30_000L

        internal const val MIGRATIONS_PREFS = "migrations"
        internal const val MIGRATION_SWAP_KEY = "create_swap_256m"

        /**
         * Offline backend base URL — kept in sync with [DeviceRegistrar.API_BASE]
         * (and OFFLINE_API in dumb-phone-configuration/.env). Used by the
         * activation-code fallback to fetch a QR code when the OpenBubbles
         * `dumb` file is empty/missing on startup.
         */
        private const val API_BASE =
            "https://offline-dc-backend-ba4815b2bcc8.herokuapp.com/api/v1"

        /**
         * Single-threaded executor for ALL boot-time tasks that shell out to
         * `su`. On low-RAM MediaTek/eMMC devices, concurrent `su` forks
         * saturate the I/O bus and starve the main thread — causing ANRs
         * even when the heavy work is nominally "in the background".
         * Serialising the commands ensures only one `su` process hits the
         * disk at a time, leaving enough I/O headroom for the UI to stay
         * responsive.
         */
        internal val bootExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "DumbDownBoot").apply { isDaemon = true }
            }
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        UpdateCheckWorker.schedule(this)

        // ── All su-heavy boot tasks are serialised on bootExecutor ──────────
        // Previously these ran on separate Threads, causing 4–5 concurrent
        // `su` forks that saturated the eMMC and starved the main thread.
        // Now they run one-at-a-time in priority order:
        //   1. Accessibility — needed for TypeSync and mouse
        //   2. Location permission grant — needed before prewarm
        //   3. Location prewarm — populates the quack cache
        //   4. FlipMouse binary update
        //   5. OpenBubbles dumb file
        //   6. Swap enable (fast swapon, no dd)
        //   7. (after 30s delay) One-time migrations incl. swap creation

        // 1. Ensure the mouse accessibility service is bound at startup so
        //    it's ready before the user toggles TypeSync for the first time.
        MouseAccessibilityService.appContext = applicationContext
        bootExecutor.execute {
            MouseAccessibilityService.ensureAccessibilityEnabled()
        }

        // 2–3. Self-grant location perms via `su pm grant` if the provisioning
        // script never ran or a reinstall cleared them. Must happen BEFORE
        // the prewarm so getLastKnownLocation() has permission to read.
        // After granting, kick off a silent coarse-location prewarm.
        //
        // GATED on the user having accepted the location ask in either the
        // quack app or the weather app. Until consent is granted we do no
        // location work at all — no permission self-grant, no prewarm, no
        // periodic refresh. The first time the user accepts consent in
        // either app, [LocationConsent.onConsentGranted] kicks all of this
        // off so the cache gets populated.
        if (LocationConsent.hasAnyConsent(this)) {
            bootExecutor.execute {
                LocationPermissionGranter.ensureGranted(this)
                // Boot-time prewarm: kick off a silent coarse-location read so the
                // persisted location cache is populated before the user opens
                // quack. Use the long PREWARM_TIMEOUT_MS (10 minutes) — a cold GPS
                // chip on a phone with no Network Location Provider can need
                // several minutes outside to download orbital data. The helper
                // persists every fix it gets internally.
                val noop = object : QuackLocationHelper.Callback {
                    override fun onLocation(lat: Double, lng: Double) { /* persisted internally */ }
                    override fun onError(reason: String) { /* ignore */ }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    QuackLocationHelper(
                        this,
                        noop,
                        hardTimeoutMs = QuackLocationHelper.PREWARM_TIMEOUT_MS,
                    ).request()
                }
            }

            // Periodic 1-hour background refresh of the persisted location cache,
            // so intercity travel (e.g. DC→NYC by train) lands inside the trip
            // rather than after it. BeaconDB is the primary path so each fire
            // is cheap; GPS fallback only runs when BeaconDB fails.
            QuackLocationRefreshWorker.schedule(this)
        } else {
            // Consent not yet granted — cancel any previously-scheduled periodic
            // refresh so devices upgraded from a build that scheduled it
            // unconditionally stop doing the background fetch.
            QuackLocationRefreshWorker.cancel(this)
        }

        // Schedule the Monday 9am quack reminder alarm (no-ops if muted).
        // As the HOME launcher our onCreate runs on every boot, so this
        // ensures the alarm is always registered even after a reboot.
        val quackMuted = getSharedPreferences("quack_prefs", Context.MODE_PRIVATE)
            .getBoolean("notifications_muted", false)
        if (!quackMuted) {
            com.offlineinc.dumbdownlauncher.quack.QuackMondayAlarmReceiver.scheduleNext(this)
        }

        // 4. Update FlipMouse (DumbMouse) binary if a newer version is bundled
        bootExecutor.execute { FlipMouseUpdater.checkAndUpdate(this) }

        // Start a periodic watchdog that re-enables the a11y service whenever
        // it drops (e.g. Android kills it in the background).
        MouseAccessibilityService.startWatchdog()

        // Listen for system-level accessibility state changes — if Android
        // disables accessibility services (e.g. after a crash), re-enable
        // ours immediately rather than waiting for the next watchdog tick.
        registerAccessibilityRecoveryListener()

        // 5. Ensure the OpenBubbles "dumb" activation file exists (blank) so
        // that older builds that check for it still work. Does NOT overwrite
        // an existing file.
        bootExecutor.execute { ensureOpenBubblesDumbFile() }

        // 5b. If the dumb file ends up empty (e.g. automated_configuration.sh
        // never ran, or its QR-code fetch failed during provisioning), pull a
        // QR code from the Offline backend and write it ourselves. Mirrors
        // the activation-code-write step in automated_configuration.sh so a
        // device that boots without a code can self-heal once it has SIM +
        // network. Runs on a dedicated thread (not bootExecutor) because it
        // blocks waiting for SIM/network and would otherwise hold up the
        // single-threaded boot tasks behind it.
        Thread({
            try {
                populateOpenBubblesActivationCode(this)
            } catch (e: Exception) {
                Log.w("DumbDownApp", "populateOpenBubblesActivationCode crashed: ${e.message}", e)
            }
        }, "DumbDownActivation").apply { isDaemon = true }.start()

        // 6. Enable swap if the file exists — swap doesn't survive reboot,
        // but as the HOME launcher our onCreate runs on every boot.
        // Internally defers to the create_swap_256m migration on first run
        // to avoid racing its dd/mkswap/swapon sequence.
        bootExecutor.execute { enableSwapIfPresent() }

        // 7. One-time migrations that run once per version bump.
        // Deferred ~30s on cold boot so the first-time swap-file creation
        // (9+ seconds of `dd`) and root commands don't compete with modem/SIM
        // init, WorkManager startup, and first-frame rendering. Subsequent
        // boots still pay the 30s wait but it's invisible to the user — the
        // loop short-circuits immediately when everything's already been
        // applied. Submitted to the same executor so it waits for earlier
        // tasks to finish before sleeping, avoiding overlap.
        bootExecutor.execute {
            try { Thread.sleep(MIGRATION_BOOT_DELAY_MS) }
            catch (ie: InterruptedException) { Thread.currentThread().interrupt(); return@execute }
            runOneTimeMigrations()
        }

        // Associate the device (IMEI + SIM + phone number) with the Offline API
        // on first boot, and re-associate whenever the phone number changes.
        // Waits for both a SIM and network in the background before doing any work —
        // a port of dumb-phone-configuration/device_registration.sh.
        DeviceRegistrar.scheduleOnBoot(this)

        // Start the cover display service. As the HOME launcher we are always alive,
        // so no foreground notification is required. The service is START_STICKY and
        // re-attaches automatically when the cover display is connected.
        startService(Intent(this, CoverDisplayService::class.java))
    }

    private fun registerAccessibilityRecoveryListener() {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
        am.addAccessibilityStateChangeListener { enabled ->
            // Ignore state changes that are side-effects of our own settings
            // toggle inside ensureAccessibilityEnabled — these fire as
            // enabled=false when we temporarily remove the service and would
            // otherwise re-trigger ensureAccessibilityEnabled in a loop.
            val msSinceToggle = android.os.SystemClock.uptimeMillis() - MouseAccessibilityService.lastToggleTimestamp
            if (msSinceToggle < 5_000L) {
                Log.d("DumbDownApp", "Accessibility state changed (enabled=$enabled) — ignoring (own toggle ${msSinceToggle}ms ago)")
                return@addAccessibilityStateChangeListener
            }

            if (!enabled || MouseAccessibilityService.instance == null) {
                Log.w("DumbDownApp", "Accessibility state changed (enabled=$enabled, instance=${MouseAccessibilityService.instance != null}) — re-enabling service")
                bootExecutor.execute { MouseAccessibilityService.ensureAccessibilityEnabled() }
            }
        }
    }

    /**
     * Ensures /data/data/com.openbubbles.messaging/files/dumb exists (blank)
     * for backwards compatibility — older OpenBubbles builds check for this file.
     * Does NOT overwrite an existing file. Runs via root on a background thread.
     */
    /**
     * Runs a root command in init's mount namespace so /data/data paths for
     * other packages are visible (the app's own namespace hides them on Android 10+).
     * Drains both streams before waitFor() to avoid pipe deadlocks.
     */
    private fun rootExec(cmd: String): Triple<Int, String, String> {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
            "nsenter -t 1 -m -- sh -c ${cmd.shellEscape()}"))
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        val stderr = proc.errorStream.bufferedReader().readText().trim()
        val exit = proc.waitFor()
        return Triple(exit, stdout, stderr)
    }

    /** Wraps a string in single quotes, escaping any embedded single quotes. */
    private fun String.shellEscape(): String =
        "'" + this.replace("'", "'\\''") + "'"

    private fun ensureOpenBubblesDumbFile() {
        val tag = "DumbDownApp"
        val dir  = "/data/data/com.openbubbles.messaging/files"
        val file = "$dir/dumb"
        try {
            /* 1 — already exists? */
            val (_, checkOut, _) = rootExec("test -f $file && echo exists || echo missing")
            if (checkOut == "exists") {
                Log.d(tag, "OpenBubbles dumb file already exists — skipping")
                return
            }

            /* 2 — create directory + file */
            val (touchExit, _, touchErr) = rootExec("mkdir -p $dir && touch $file")
            if (touchExit != 0) {
                Log.w(tag, "touch failed (exit=$touchExit): $touchErr")
                return
            }

            /* 3 — fix ownership to match the parent package dir */
            val (_, ownerOut, _) = rootExec("stat -c %u:%g /data/data/com.openbubbles.messaging")
            if (ownerOut.isNotBlank()) {
                rootExec("chown -R $ownerOut $dir")
            }

            /* 4 — verify */
            val (_, verifyOut, _) = rootExec("test -f $file && echo exists || echo missing")
            if (verifyOut == "exists") {
                Log.d(tag, "Created blank OpenBubbles dumb file")
            } else {
                Log.w(tag, "dumb file still missing after touch in init namespace!")
            }
        } catch (e: Exception) {
            Log.w(tag, "Cannot create OpenBubbles dumb file: ${e.message}")
        }
    }

    /**
     * Self-heals an empty/missing OpenBubbles activation file by fetching a
     * QR code from the Offline backend and writing it to
     * `/data/data/com.openbubbles.messaging/files/dumb` — the same path and
     * payload that `dumb-phone-configuration/automated_configuration.sh`
     * writes during initial provisioning.
     *
     * Runs on a dedicated background thread (NOT [bootExecutor]) because it
     * blocks waiting for the SIM to come up and for network to attach. The
     * launcher is the HOME process so a slow modem just means we sit cheaply.
     *
     * Flow:
     *   1. If `dumb` is already non-empty → skip (provisioning already wrote it).
     *   2. Wait for IMEI to become readable (SIM ready).
     *   3. Wait for network availability.
     *   4. GET `${API_BASE}/qr_codes/next_available?imei=${IMEI}` — the
     *      backend returns the existing assignment for this IMEI if one
     *      exists, otherwise hands out the next free QR code.
     *   5. Write the response's `code` field to the `dumb` file via
     *      `nsenter`-wrapped `su` (same pattern as [ensureOpenBubblesDumbFile]).
     *      Base64-encoded on the wire so JWT-style codes with `=`/`+`/etc.
     *      survive shell parsing without escaping headaches.
     *   6. PUT `${API_BASE}/phones/${IMEI}` with `{ qr_code_id: <id> }` to
     *      claim the assignment, mirroring the QR ASSIGNMENT step in
     *      automated_configuration.sh — without this the same code could be
     *      handed to another phone on its next provisioning run.
     *
     * Best-effort: every error path logs a warning and bails. The next boot
     * will retry from the top.
     */
    private fun populateOpenBubblesActivationCode(ctx: Context) {
        val tag = "DumbDownApp"
        val obDir = "/data/data/com.openbubbles.messaging/files"
        val obFile = "$obDir/dumb"

        try {
            // 1. Skip if already populated. `stat -c %s` returns the byte
            //    size; we compare > 0 to also treat a 0-byte placeholder
            //    (created by ensureOpenBubblesDumbFile above) as "needs
            //    fetching", which is the whole point of this routine.
            val (_, sizeOut, _) = rootExec("stat -c %s $obFile 2>/dev/null || echo missing")
            val size = sizeOut.toLongOrNull() ?: 0L
            if (size > 0L) {
                Log.d(tag, "OpenBubbles dumb file already populated (size=$size) — skipping QR fetch")
                return
            }
            Log.i(tag, "OpenBubbles dumb file empty/missing (sizeOut=$sizeOut) — fetching activation code")

            // 2. Resolve IMEI. Prefer the value DeviceRegistrar already
            //    persisted on a previous successful registration — that's a
            //    cheap SharedPreferences read that avoids the SimInfoReader
            //    cascade (TelephonyManager → service call iphonesubinfo →
            //    multiple getprops → siminfo content provider → ro.serialno),
            //    which can take several seconds AND may need to wait for the
            //    SIM to come up on cold boot. Only fall back to the live
            //    cascade when there's no cached value (first-ever boot, or
            //    storage was wiped).
            var imei: String? = DeviceRegistrar.getCachedImei(ctx)
            if (!imei.isNullOrBlank()) {
                Log.i(tag, "Using cached IMEI from DeviceRegistrar: $imei")
            } else {
                Log.i(tag, "No cached IMEI — polling SimInfoReader (every 10s, up to 10 min)")
                val pollIntervalMs = TimeUnit.SECONDS.toMillis(10)
                val maxPolls = 60  // 10 min total
                for (i in 1..maxPolls) {
                    imei = SimInfoReader.readImei(ctx)
                    if (!imei.isNullOrBlank()) break
                    try {
                        Thread.sleep(pollIntervalMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (imei.isNullOrBlank()) {
                    Log.w(tag, "IMEI not readable after extended wait — skipping QR fetch")
                    return
                }
                Log.i(tag, "IMEI ready (live read): $imei")
            }

            // 3. Wait for network. Mirrors DeviceRegistrar.awaitNetwork.
            if (!NetworkUtils.isNetworkAvailable(ctx)) {
                val lock = Object()
                val ready = AtomicBoolean(false)
                NetworkUtils.whenNetworkAvailable(ctx) {
                    synchronized(lock) {
                        ready.set(true)
                        lock.notifyAll()
                    }
                }
                synchronized(lock) {
                    while (!ready.get()) {
                        try {
                            lock.wait()
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                    }
                }
            }
            Log.i(tag, "Network available — fetching QR code from $API_BASE")

            // Re-check the dumb file size after the SIM/network waits — a
            // concurrent code drop (e.g. an operator running
            // automated_configuration.sh while we slept) means we don't need
            // to hit the API at all.
            val (_, recheckSize, _) = rootExec("stat -c %s $obFile 2>/dev/null || echo 0")
            if ((recheckSize.toLongOrNull() ?: 0L) > 0L) {
                Log.i(tag, "dumb file populated during wait — skipping QR fetch")
                return
            }

            // 4. GET /qr_codes/next_available?imei=...
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
            val qrUrl = "$API_BASE/qr_codes/next_available?imei=$imei"
            val qrReq = Request.Builder().url(qrUrl).get().build()
            Log.d(tag, "HTTP GET $qrUrl")

            var qrCode: String? = null
            var qrId: Long = -1L
            client.newCall(qrReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(tag, "qr_codes/next_available HTTP ${resp.code} body=${resp.body?.string()}")
                    return
                }
                val bodyStr = resp.body?.string().orEmpty()
                if (bodyStr.isBlank()) {
                    Log.w(tag, "qr_codes/next_available returned empty body")
                    return
                }
                try {
                    val json = JSONObject(bodyStr)
                    qrCode = json.optString("code", "").takeIf { it.isNotBlank() }
                    qrId = json.optLong("id", -1L)
                } catch (e: Exception) {
                    Log.w(tag, "qr_codes/next_available unparseable response: $bodyStr")
                    return
                }
            }
            val code = qrCode
            if (code.isNullOrBlank()) {
                Log.w(tag, "qr_codes/next_available returned no usable code (id=$qrId)")
                return
            }
            Log.i(tag, "QR code retrieved (id=$qrId, codePrefix=${code.take(20)}…)")

            // 5a. Force-stop OpenBubbles before touching the dumb file.
            //     OpenBubbles only reads the activation code on app launch
            //     (its DartWorker / APNService startup path); a process that
            //     was already running with no code stays in its
            //     "unactivated" state until we kick it. Stopping it here
            //     guarantees that the next time it's launched (whether by
            //     the user, a notification, or its own foreground-service
            //     auto-start) it picks up the freshly written code.
            //     Best-effort — non-zero exit just means the package
            //     wasn't running, which is exactly what we want anyway.
            val (stopExit, stopOut, stopErr) = rootExec(
                "am force-stop com.openbubbles.messaging"
            )
            if (stopExit == 0) {
                Log.i(tag, "Force-stopped com.openbubbles.messaging before writing activation code")
            } else {
                Log.d(tag, "force-stop returned exit=$stopExit out=$stopOut err=$stopErr (non-fatal)")
            }

            // 5b. Write to dumb file. Base64-encode the code so the inner shell
            //    doesn't have to deal with `=` / `+` / `/` from JWT-shaped
            //    payloads. The base64 alphabet is shell-safe (no quotes, no
            //    whitespace, no metacharacters) so the whole command can pass
            //    through rootExec's shellEscape unchanged.
            val b64 = android.util.Base64.encodeToString(
                code.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val writeCmd =
                "mkdir -p $obDir && " +
                "echo $b64 | base64 -d > $obFile && " +
                "chown -R \$(stat -c %u:%g /data/data/com.openbubbles.messaging) $obDir"
            val (writeExit, writeOut, writeErr) = rootExec(writeCmd)
            if (writeExit != 0) {
                Log.w(tag, "write dumb file failed (exit=$writeExit out=$writeOut err=$writeErr)")
                return
            }
            val (_, finalSize, _) = rootExec("stat -c %s $obFile 2>/dev/null || echo 0")
            Log.i(tag, "Wrote QR code to $obFile (size=$finalSize) ✔")

            // 6. PUT /phones/{imei} {qr_code_id: id} — claim the assignment.
            //    Same step as the "QR CODE ASSIGNMENT" stanza in
            //    automated_configuration.sh; without it the same code can be
            //    handed to a different phone next time.
            if (qrId > 0L) {
                try {
                    val assignBody = JSONObject().put("qr_code_id", qrId)
                    val assignReq = Request.Builder()
                        .url("$API_BASE/phones/$imei")
                        .put(assignBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    Log.d(tag, "HTTP PUT ${assignReq.url} body=$assignBody")
                    client.newCall(assignReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Log.i(tag, "QR code $qrId assigned to phone $imei (HTTP ${resp.code}) ✔")
                        } else {
                            Log.w(tag, "QR assignment HTTP ${resp.code} body=${resp.body?.string()}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "QR assignment failed: ${e.message}")
                }
            } else {
                Log.w(tag, "Skipping QR assignment PUT — response had no usable id")
            }
        } catch (e: Exception) {
            Log.w(tag, "populateOpenBubblesActivationCode: ${e.message}", e)
        }
    }

    /**
     * Runs one-time migrations keyed by name. Each migration executes at most
     * once across app updates. Add new entries to the map below.
     */
    private fun runOneTimeMigrations() {
        val tag = "DumbDownApp"
        val prefs = getSharedPreferences(MIGRATIONS_PREFS, Context.MODE_PRIVATE)

        val migrations = mapOf<String, () -> Unit>(
            // Uninstall the standalone snake APK now that snake is built into
            // the launcher. Defensive: silently ignores if already uninstalled
            // or if the package was never present. Uses `pm uninstall` which
            // does NOT require root — the launcher is signed with the platform
            // key, so shell-level uninstall works. Falls back to `su` if needed.
            "uninstall_snake_apk" to {
                try {
                    val pkg = "com.snake"
                    // Quick check: is the package even installed?
                    val checkProc = Runtime.getRuntime().exec(arrayOf("pm", "list", "packages", pkg))
                    val checkOut = checkProc.inputStream.bufferedReader().readText().trim()
                    checkProc.errorStream.bufferedReader().readText() // drain stderr to avoid pipe deadlock
                    checkProc.waitFor()
                    if (!checkOut.contains("package:$pkg")) {
                        Log.d(tag, "Snake APK not installed — nothing to uninstall")
                    } else {
                        Log.d(tag, "Uninstalling standalone snake APK ($pkg)")
                        val proc = Runtime.getRuntime().exec(arrayOf("pm", "uninstall", pkg))
                        val out = proc.inputStream.bufferedReader().readText().trim()
                        proc.errorStream.bufferedReader().readText() // drain stderr
                        val exit = proc.waitFor()
                        if (exit != 0) {
                            // Retry with root
                            val suProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm uninstall $pkg"))
                            val suOut = suProc.inputStream.bufferedReader().readText().trim()
                            suProc.errorStream.bufferedReader().readText() // drain stderr
                            val suExit = suProc.waitFor()
                            Log.d(tag, "Snake APK uninstall (su): exit=$suExit $suOut")
                        } else {
                            Log.d(tag, "Snake APK uninstalled: $out")
                        }
                        AllAppsActivity.invalidateCache()
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Snake APK uninstall failed (non-critical): ${e.message}")
                }
            },
            // Delete the old "type_sync" notification channel that was used by the
            // now-removed WebKeyboardService foreground service.
            "delete_type_sync_channel" to {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.deleteNotificationChannel("type_sync")
                    Log.d(tag, "Deleted old type_sync notification channel")
                }
            },
            // Create a 256 MB swap file to give low-RAM devices more headroom
            // for memory-hungry apps (Uber Lite, WhatsApp, Chrome, etc.).
            // Removes any existing swap file first to avoid wasting space.
            MIGRATION_SWAP_KEY to {
                val swapTag = "SwapSetup"
                val swapFile = "/data/swapfile"
                val sizeMb = 256
                val targetBytes = sizeMb.toLong() * 1024L * 1024L
                try {
                    Log.i(swapTag, "━━━ Starting $sizeMb MB swap setup ━━━")

                    // Short-circuit: if a swap file at the right size already
                    // exists (e.g. a previous install created it but the
                    // migration key was cleared), just ensure swapon and exit.
                    // Avoids a 9+ second `dd` that could block the su daemon
                    // and re-trigger the cold-boot jank we just fixed.
                    val statProc = Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "stat -c %s $swapFile 2>/dev/null || echo 0"))
                    val existingBytes = statProc.inputStream.bufferedReader()
                        .readText().trim().toLongOrNull() ?: 0L
                    statProc.waitFor()
                    if (existingBytes == targetBytes) {
                        Log.i(swapTag, "Swap file already exists at target size ($sizeMb MB) — ensuring swapon and skipping recreate")
                        val swapsProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/swaps"))
                        val swaps = swapsProc.inputStream.bufferedReader().readText()
                        swapsProc.waitFor()
                        if (!swaps.contains(swapFile)) {
                            val onProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "swapon $swapFile 2>&1"))
                            val onOut = onProc.inputStream.bufferedReader().readText().trim()
                            val onExit = onProc.waitFor()
                            Log.i(swapTag, "swapon: exit=$onExit ${if (onOut.isNotEmpty()) "output=$onOut" else ""}")
                        } else {
                            Log.i(swapTag, "Swap already active")
                        }
                        logSwapStatus(swapTag)
                        return@to
                    }

                    // Disable + remove any existing swap file (wrong size or missing)
                    val checkProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -f $swapFile && echo exists || echo missing"))
                    val checkOut = checkProc.inputStream.bufferedReader().readText().trim()
                    checkProc.waitFor()
                    if (checkOut == "exists") {
                        Log.i(swapTag, "Old swap file found (size=$existingBytes) — disabling and removing")
                        val offProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "swapoff $swapFile 2>&1"))
                        val offOut = offProc.inputStream.bufferedReader().readText().trim()
                        offProc.waitFor()
                        Log.i(swapTag, "swapoff: exit=${offProc.exitValue()} ${if (offOut.isNotEmpty()) "output=$offOut" else ""}")
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -f $swapFile")).waitFor()
                        Log.i(swapTag, "Deleted old swap file")
                    }

                    // Create new file
                    Log.i(swapTag, "Creating $sizeMb MB swap file with dd…")
                    val startMs = System.currentTimeMillis()
                    val ddProc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                        "dd if=/dev/zero of=$swapFile bs=1048576 count=$sizeMb 2>&1"))
                    val ddOut = ddProc.inputStream.bufferedReader().readText().trim()
                    val ddExit = ddProc.waitFor()
                    val elapsed = System.currentTimeMillis() - startMs
                    Log.i(swapTag, "dd finished: exit=$ddExit time=${elapsed}ms output=$ddOut")
                    if (ddExit != 0) {
                        Log.e(swapTag, "❌ dd FAILED — aborting swap setup")
                        return@to
                    }

                    // Verify file size
                    val lsProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -la $swapFile"))
                    val lsOut = lsProc.inputStream.bufferedReader().readText().trim()
                    lsProc.waitFor()
                    Log.i(swapTag, "File created: $lsOut")

                    // Secure permissions
                    val chmodProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 600 $swapFile"))
                    chmodProc.waitFor()
                    Log.i(swapTag, "chmod 600: exit=${chmodProc.exitValue()}")

                    // Format as swap
                    val mkswapProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "mkswap $swapFile 2>&1"))
                    val mkswapOut = mkswapProc.inputStream.bufferedReader().readText().trim()
                    mkswapProc.waitFor()
                    Log.i(swapTag, "mkswap: exit=${mkswapProc.exitValue()} output=$mkswapOut")

                    // Enable
                    val swapOnProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "swapon $swapFile 2>&1"))
                    val swapOnOut = swapOnProc.inputStream.bufferedReader().readText().trim()
                    val swapExit = swapOnProc.waitFor()
                    Log.i(swapTag, "swapon: exit=$swapExit ${if (swapOnOut.isNotEmpty()) "output=$swapOnOut" else ""}")

                    logSwapStatus(swapTag)

                    if (swapExit == 0) {
                        Log.i(swapTag, "✅ Swap file created and enabled ($sizeMb MB)")
                    } else {
                        Log.e(swapTag, "❌ swapon failed after creation")
                    }
                } catch (e: Exception) {
                    Log.e(swapTag, "❌ Swap setup failed: ${e.message}", e)
                }
            },
            // Disable TCL OTA updater so carrier/OEM updates don't nag or auto-install
            "disable_tcl_fota" to {
                try {
                    val proc = Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "pm disable-user --user 0 com.tcl.fota.system")
                    )
                    val stderr = proc.errorStream.bufferedReader().readText().trim()
                    val exit = proc.waitFor()
                    if (exit == 0) {
                        Log.d(tag, "Disabled com.tcl.fota.system")
                    } else {
                        Log.w(tag, "Failed to disable com.tcl.fota.system (exit=$exit): $stderr")
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Cannot disable com.tcl.fota.system: ${e.message}")
                }
            },
            // Disable Android's Wi-Fi scan throttle (default 4 scans / 2 min
            // for foreground apps, ~1 scan / 30 min in background) so that
            // BeaconDB-based geolocation gets fresh BSSIDs on every request.
            // Without this the periodic 1-h refresh worker can fall back to
            // cell-only fixes, and the very first cold-boot scan after a
            // fresh install may return stale results. Negligible battery
            // impact at our actual scan rate (~24–30 scans/day).
            "disable_wifi_scan_throttle" to {
                try {
                    val proc = Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "settings put global wifi_scan_throttle_enabled 0")
                    )
                    val stderr = proc.errorStream.bufferedReader().readText().trim()
                    val exit = proc.waitFor()
                    if (exit == 0) {
                        Log.d(tag, "Disabled wifi_scan_throttle_enabled")
                    } else {
                        Log.w(tag, "Failed to disable wifi_scan_throttle (exit=$exit): $stderr")
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Cannot disable wifi_scan_throttle: ${e.message}")
                }
            },
            // Grant READ_CONTACTS / WRITE_CONTACTS to OpenBubbles if it's
            // installed and missing them. OpenBubbles needs contacts access to
            // resolve handles → display names; on some installs the runtime
            // grant gets dropped (re-install, permission reset) and there's no
            // in-launcher UI to re-prompt. `pm grant` for a different package
            // requires GRANT_RUNTIME_PERMISSIONS (signature|privileged), so
            // this has to go through `su` — same as the other pm migrations.
            "grant_openbubbles_contact_perms" to {
                val obPkg = "com.openbubbles.messaging"
                try {
                    // Skip cleanly if OpenBubbles isn't installed.
                    try {
                        packageManager.getPackageInfo(obPkg, 0)
                    } catch (_: PackageManager.NameNotFoundException) {
                        Log.d(tag, "$obPkg not installed — skipping contact permission grant")
                        return@to
                    }

                    val perms = listOf(
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.WRITE_CONTACTS,
                    )
                    for (perm in perms) {
                        val granted = packageManager.checkPermission(perm, obPkg) ==
                                PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            Log.d(tag, "$obPkg already has $perm")
                            continue
                        }
                        val proc = Runtime.getRuntime().exec(
                            arrayOf("su", "-c", "pm grant $obPkg $perm")
                        )
                        val stderr = proc.errorStream.bufferedReader().readText().trim()
                        val exit = proc.waitFor()
                        if (exit == 0) {
                            Log.d(tag, "Granted $perm to $obPkg")
                        } else {
                            Log.w(tag, "Failed to grant $perm to $obPkg (exit=$exit): $stderr")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Cannot grant OpenBubbles contact permissions: ${e.message}")
                }
            },
            // Remove OpenBubbles from the Doze whitelist. A bug in OpenBubbles'
            // DartWorker causes a WorkManager crash loop (APNService not started
            // race condition) that holds wake locks indefinitely when whitelisted.
            // The APNService foreground service is unaffected by Doze and keeps
            // the iMessage bridge alive without the whitelist.
            "remove_openbubbles_doze_whitelist" to {
                try {
                    val proc = Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "dumpsys deviceidle whitelist -com.openbubbles.messaging")
                    )
                    val output = proc.inputStream.bufferedReader().readText().trim()
                    val exit = proc.waitFor()
                    if (exit == 0) {
                        Log.d(tag, "Removed OpenBubbles from Doze whitelist: $output")
                    } else {
                        Log.w(tag, "Failed to remove OpenBubbles from Doze whitelist (exit=$exit): $output")
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Cannot remove OpenBubbles from Doze whitelist: ${e.message}")
                }
            },
        )

        for ((key, action) in migrations) {
            if (prefs.getBoolean(key, false)) continue
            try {
                Log.d(tag, "Running migration: $key")
                action()
                // commit() instead of apply(): this runs on the boot executor
                // thread, so the synchronous write happens here — not queued
                // for main-thread flush during Activity.onStop() which causes
                // ANRs on slow eMMC.
                prefs.edit().putBoolean(key, true).commit()
                Log.d(tag, "Migration complete: $key")
            } catch (e: Exception) {
                Log.w(tag, "Migration failed: $key — ${e.message}")
            }
        }
    }

    /** Logs current swap and memory info so `adb logcat -s SwapSetup SwapBoot` shows the state. */
    private fun logSwapStatus(tag: String) {
        try {
            val swapsProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/swaps"))
            val swaps = swapsProc.inputStream.bufferedReader().readText().trim()
            swapsProc.waitFor()
            Log.i(tag, "/proc/swaps:\n$swaps")

            val memProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/meminfo"))
            val memAll = memProc.inputStream.bufferedReader().readText().trim()
            memProc.waitFor()

            // Parse key values (in kB) for a human-readable summary
            val kv = memAll.lines()
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) parts[0].trimEnd(':') to (parts[1].toLongOrNull() ?: 0L)
                    else null
                }.toMap()

            val memTotalMb  = (kv["MemTotal"] ?: 0) / 1024
            val memAvailMb  = (kv["MemAvailable"] ?: 0) / 1024
            val swapTotalMb = (kv["SwapTotal"] ?: 0) / 1024
            val swapFreeMb  = (kv["SwapFree"] ?: 0) / 1024
            val swapUsedMb  = swapTotalMb - swapFreeMb

            Log.i(tag, "Memory: ${memAvailMb}MB available / ${memTotalMb}MB total")
            Log.i(tag, "Swap:   ${swapUsedMb}MB used / ${swapTotalMb}MB total (${swapFreeMb}MB free)")
            if (swapUsedMb > 0) {
                Log.i(tag, "✅ Swap is actively being used — apps have ${swapUsedMb}MB paged out")
            } else if (swapTotalMb > 0) {
                Log.i(tag, "Swap enabled but 0 MB used (normal right after boot — will grow as apps run)")
            }
        } catch (e: Exception) {
            Log.w(tag, "logSwapStatus failed: ${e.message}")
        }
    }

    /**
     * Activates the swap file if it exists and isn't already active.
     * The file is created by the "create_swap_256m" migration; this just
     * re-enables it after every reboot.
     */
    private fun enableSwapIfPresent() {
        val tag = "SwapBoot"
        try {
            // If the create_swap_256m migration hasn't been applied yet, leave
            // swap setup entirely to that migration. It does the full
            // dd → mkswap → swapon sequence and we'd otherwise race it,
            // producing the misleading "swapon failed: No such file" error we
            // saw after the v3 → v4 upgrade.
            val migrationsApplied = getSharedPreferences(MIGRATIONS_PREFS, Context.MODE_PRIVATE)
                .getBoolean(MIGRATION_SWAP_KEY, false)
            if (!migrationsApplied) {
                Log.i(tag, "Swap-create migration not yet applied — deferring swapon to migration thread")
                return
            }

            val swapFile = "/data/swapfile"
            Log.i(tag, "━━━ Checking swap on boot ━━━")

            // Quick check: file exists?
            val check = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -f $swapFile && echo y || echo n"))
            val exists = check.inputStream.bufferedReader().readText().trim()
            check.waitFor()
            if (exists != "y") {
                Log.i(tag, "No swap file at $swapFile — skipping")
                return
            }
            Log.i(tag, "Swap file exists")

            // Already active?
            val active = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/swaps"))
            val swaps = active.inputStream.bufferedReader().readText()
            active.waitFor()
            Log.i(tag, "/proc/swaps:\n$swaps")
            if (swaps.contains(swapFile)) {
                Log.i(tag, "✅ Swap already active — nothing to do")
                logSwapStatus(tag)
                return
            }

            Log.i(tag, "Swap not active — enabling…")
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "swapon $swapFile 2>&1"))
            val out = proc.inputStream.bufferedReader().readText().trim()
            val exit = proc.waitFor()
            if (exit == 0) {
                Log.i(tag, "✅ Swap enabled on boot")
            } else {
                Log.e(tag, "❌ swapon failed: exit=$exit ${if (out.isNotEmpty()) "output=$out" else ""}")
            }
            logSwapStatus(tag)
        } catch (e: Exception) {
            Log.e(tag, "❌ enableSwapIfPresent failed: ${e.message}", e)
        }
    }

}
