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
import com.offlineinc.dumbdownlauncher.calllog.CallLogCleanupWorker
import com.offlineinc.dumbdownlauncher.coverdisplay.CoverDisplayService
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesAttachmentCleanupWorker
import com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesOps
import com.offlineinc.dumbdownlauncher.whatsapp.WhatsAppAttachmentCleanupWorker
import com.offlineinc.dumbdownlauncher.whatsapp.WhatsAppOps
import com.offlineinc.dumbdownlauncher.quack.LocationConsent
import com.offlineinc.dumbdownlauncher.quack.LocationPermissionGranter
import com.offlineinc.dumbdownlauncher.quack.QuackLocationHelper
import com.offlineinc.dumbdownlauncher.quack.QuackLocationRefreshWorker
import com.offlineinc.dumbdownlauncher.registration.DeviceRegistrar
import com.offlineinc.dumbdownlauncher.registration.SimInfoReader
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.update.BetaUpdateReminderWorker
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

        // Re-arm the beta tester daily reminder if the user has opted in.
        // The flag is persisted in PairingStore — a long-press on "updates"
        // in AllAppsActivity flips it; this branch makes sure the periodic
        // worker survives reboots even though WorkManager itself re-enqueues
        // periodic work across boots (the KEEP policy makes the call safe
        // either way). If the flag is off, do nothing — opting out cancels
        // the unique work directly from AllAppsActivity.
        if (PairingStore(this).betaTesterMode) {
            BetaUpdateReminderWorker.schedule(this)
        }

        // Nightly 2 AM cleanup of the system call log — anything older than
        // 7 days is deleted. Keeps calllog.db small on low-storage devices
        // (TCL Flip Go) so the dialer and launcher stay responsive. KEEP
        // policy means re-scheduling on every boot is a no-op once the
        // first run has been queued.
        CallLogCleanupWorker.schedule(this)

        // Weekly 2 AM wipe of OpenBubbles' attachment cache. Pairs with
        // the openbubbles_setup_v1 migration that turns autoDownload
        // off, so the cache only refills with attachments the user
        // explicitly opens between runs. Skips cleanly when OpenBubbles
        // is in the foreground (defers to the next weekly tick).
        OpenBubblesAttachmentCleanupWorker.schedule(this)

        // Nightly 2 AM rolling cleanup of WhatsApp attachments older
        // than 7 days, restricted to three subdirs: .Links/ (link-
        // preview thumbnails — regenerated freely from the source URL),
        // WhatsApp Images/, and WhatsApp Video/. Voice notes, documents,
        // animated gifs, and audio are deliberately preserved (see
        // WhatsAppOps.TARGET_SUBDIRS). Pairs with the whatsapp_setup_v1
        // migration that zeroes the autodownload bitmasks, so those
        // three dirs only refill with attachments the user explicitly
        // opens.
        //
        // Unlike OpenBubbles, this worker doesn't kill WhatsApp first —
        // WhatsApp's media lives on /sdcard rather than /data/data so
        // there's nothing to race, and the -mtime +7 predicate
        // guarantees we never touch in-flight downloads. CRITICALLY
        // excludes .nomedia sentinel files; deleting them would unhide
        // WhatsApp's private/voice-note dirs in the system Photos app —
        // see WhatsAppOps.clearOldAttachments for details.
        //
        // Tighter cadence than the OpenBubbles weekly wipe because
        // .Links/ accumulates link-preview thumbnails continuously and
        // benefits from a daily trim cycle.
        WhatsAppAttachmentCleanupWorker.schedule(this)

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

        // 2nd Tuesday / 4th Sunday "add wifi 2 save on data" reminder.
        // Two parts:
        //   1. scheduleNext re-arms the recurring alarm chain — idempotent
        //      and cheap to call on every boot (same pattern as the quack
        //      alarm above).
        //   2. nudgeOnBootIfOffline fires on every launcher process start
        //      (so: every cold boot, plus app updates). After a 30 s
        //      grace period to let the Wi-Fi supplicant associate with a
        //      saved network, it posts the same nudge iff the device is
        //      not currently connected to Wi-Fi. Single notification id,
        //      so a stale shade entry from the previous boot is updated
        //      in-place rather than stacked.
        com.offlineinc.dumbdownlauncher.wifinudge.WifiNudgeAlarmReceiver.scheduleNext(this)
        com.offlineinc.dumbdownlauncher.wifinudge.WifiNudgeAlarmReceiver.nudgeOnBootIfOffline(this)

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
     *   2. Wait for IMEI to become readable (SIM ready). Also read the ICCID
     *      so the backend can run its SIM-keyed dedup (see step 4).
     *   3. Wait for network availability.
     *   4. POST `${API_BASE}/register` with `{imei, iccid, assign_qr: true}`.
     *      Replaces the legacy GET /qr_codes/next_available + PUT /phones/:imei
     *      pair with a single round-trip. The backend handles QR assignment
     *      AND persists `qr_code_id` on the phones row in one transaction —
     *      no follow-up PUT needed.
     *
     *      Sending `iccid` alongside `imei` lets the backend dedup against
     *      any existing QR claim under the same SIM. If a previous launcher
     *      build registered the same physical phone under a different (and
     *      sometimes bogus) IMEI value — IMSI-as-IMEI, ICCID-as-IMEI,
     *      ro.serialno-as-IMEI; see the post-fix SimInfoReader guards and
     *      docs/bug-imei-fallback-thrashes-system-server.md — the existing
     *      claim is returned instead of allocating a fresh code from the
     *      pool. The phone gets the same QR it had pre-update; no waste.
     *
     *   5. Write the response's `code` field to the `dumb` file via
     *      `nsenter`-wrapped `su` (same pattern as [ensureOpenBubblesDumbFile]).
     *      Base64-encoded on the wire so JWT-style codes with `=`/`+`/etc.
     *      survive shell parsing without escaping headaches.
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
                // Exponential backoff between cascade attempts.
                //
                // Old schedule: 10s × 60 = 60 cascades over 10 min. On a
                // MediaTek flip phone where the cascade never succeeds (no
                // SIM, broken service-call interface, etc.) that's 60 ×
                // ~12 su subprocesses = ~720 root-server invocations in 10
                // minutes, which thrashes Magisk under memory pressure and
                // freezes system_server. See
                // docs/bug-imei-fallback-thrashes-system-server.md.
                //
                // New schedule: 6 cascades over ~16.5 min, spaced so the
                // first few retries catch the common "SIM coming up late on
                // cold boot" case while later retries don't churn memory.
                // SimInfoReader now caches the first successful IMEI, so a
                // device that ever reads successfully will skip this loop
                // entirely on every subsequent boot — this schedule only
                // costs anything on first-install + no-SIM devices.
                val backoffsMs = longArrayOf(
                    TimeUnit.SECONDS.toMillis(10),
                    TimeUnit.SECONDS.toMillis(30),
                    TimeUnit.MINUTES.toMillis(1),
                    TimeUnit.MINUTES.toMillis(5),
                    TimeUnit.MINUTES.toMillis(10),
                )
                Log.i(tag, "No cached IMEI — polling SimInfoReader with backoff " +
                    "(immediate, then +10s/+30s/+1m/+5m/+10m)")
                imei = SimInfoReader.readImei(ctx)
                if (imei.isNullOrBlank()) {
                    for ((i, delay) in backoffsMs.withIndex()) {
                        try {
                            Thread.sleep(delay)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                        Log.d(tag, "IMEI retry ${i + 1}/${backoffsMs.size} after ${delay}ms wait")
                        imei = SimInfoReader.readImei(ctx)
                        if (!imei.isNullOrBlank()) break
                    }
                }
                if (imei.isNullOrBlank()) {
                    Log.w(tag, "IMEI not readable after backoff window — skipping QR fetch")
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

            // 4. POST /api/v1/register {imei, iccid, assign_qr: true}.
            //
            //    Single round-trip — replaces the legacy GET /qr_codes/next_available
            //    + PUT /phones/:imei pair. The backend assigns a QR and persists
            //    qr_code_id on the phones row in one transaction; no follow-up
            //    PUT is needed.
            //
            //    Sending the ICCID enables the backend's SIM-keyed dedup: if
            //    any phones row already holds a qr_code_id against this SIM
            //    (typically because a previous launcher build registered the
            //    same physical phone under a bogus IMEI value — IMSI,
            //    ro.serialno, or the ICCID itself), the existing claim is
            //    returned instead of allocating a fresh code from the pool.
            //    This is the only guarantee that keeps the QR pool from
            //    being burnt down by cross-version IMEI churn.
            //
            //    ICCID is read best-effort. If the cascade fails (no SIM,
            //    broken service-call binder, etc.) we still POST but without
            //    iccid — the call falls back to imei-only lookup, which
            //    means the SIM-keyed dedup can't fire for this round but
            //    everything else still works.
            val iccid = SimInfoReader.readIccid(ctx)
            if (iccid.isNullOrBlank()) {
                Log.w(tag, "ICCID not readable — POSTing /register without it; " +
                    "SIM-keyed dedup will not run for this device on this call")
            } else {
                Log.d(tag, "ICCID resolved for /register: $iccid")
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
            val registerBody = JSONObject()
                .put("imei", imei)
                .put("assign_qr", true)
            if (!iccid.isNullOrBlank()) {
                registerBody.put("iccid", iccid)
            }
            val registerReq = Request.Builder()
                .url("$API_BASE/register")
                .post(registerBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            Log.d(tag, "HTTP POST ${registerReq.url} body=$registerBody")

            var qrCode: String? = null
            var qrId: Long = -1L
            client.newCall(registerReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(tag, "register HTTP ${resp.code} body=${resp.body?.string()}")
                    return
                }
                val bodyStr = resp.body?.string().orEmpty()
                if (bodyStr.isBlank()) {
                    Log.w(tag, "register returned empty body")
                    return
                }
                try {
                    val json = JSONObject(bodyStr)
                    val results = json.optJSONObject("results")
                    val qrObj = results?.optJSONObject("qr")
                    if (qrObj == null) {
                        Log.w(tag, "register response missing results.qr: $bodyStr")
                        return
                    }
                    // Backend returns either {id, code, url} on success or
                    // {error: "..."} when the pool is exhausted under maxLines.
                    if (qrObj.has("error")) {
                        Log.w(tag, "register results.qr error: ${qrObj.optString("error")}")
                        return
                    }
                    qrCode = qrObj.optString("code", "").takeIf { it.isNotBlank() }
                    qrId = qrObj.optLong("id", -1L)
                } catch (e: Exception) {
                    Log.w(tag, "register unparseable response: $bodyStr", e)
                    return
                }
            }
            val code = qrCode
            if (code.isNullOrBlank()) {
                Log.w(tag, "register returned no usable QR code (id=$qrId)")
                return
            }
            Log.i(tag, "QR code retrieved via /register (id=$qrId, codePrefix=${code.take(20)}…)")

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

            // (No step 6.) /api/v1/register already persisted qr_code_id on
            // the phones row as part of its assign_qr branch, so we don't need
            // a follow-up PUT /phones/:imei here. The legacy GET/PUT pair was
            // collapsed into the single POST above precisely so that the QR
            // assignment and the phones-row update happen in one transaction
            // — eliminating the failure mode where the GET succeeded, we
            // wrote the dumb file, but the PUT failed and the same code got
            // re-handed-out on the next phone's provisioning run.
        } catch (e: Exception) {
            Log.w(tag, "populateOpenBubblesActivationCode: ${e.message}", e)
        }
    }

    /**
     * Applies opinionated OpenBubbles defaults by editing
     * `FlutterSharedPreferences.xml` directly.
     *
     * Skips cleanly if OpenBubbles isn't installed or hasn't been opened
     * yet (the prefs file is created lazily on first launch). In the
     * "not opened yet" case the function THROWS so the migration framework
     * leaves the migration unapplied — it'll retry on the next boot,
     * picking up the change as soon as the user opens OpenBubbles once.
     *
     * The edit pattern is upsert-style per key: if the `<boolean>` line
     * already exists with the wrong value we replace it; if it's missing
     * entirely (older OpenBubbles versions where the user never toggled
     * the setting) we insert it before `</map>`. If it already matches
     * the target value we leave it alone.
     *
     * Settings applied:
     *  - `flutter.autoDownload`  -> `false`  (don't pre-download attachments)
     *  - `flutter.highPerfMode`  -> `true`   (turn on OpenBubbles' high-perf path)
     */
    private fun applyOpenBubblesPerfSettings(tag: String) {
        val obPkg = "com.openbubbles.messaging"

        // Skip cleanly when OB isn't installed.
        try { packageManager.getPackageInfo(obPkg, 0) }
        catch (_: PackageManager.NameNotFoundException) {
            Log.d(tag, "$obPkg not installed — skipping OB perf settings migration")
            return
        }

        val prefsPath = "/data/data/$obPkg/shared_prefs/FlutterSharedPreferences.xml"

        // The prefs file doesn't exist until OpenBubbles is launched
        // once. Throw so the migration framework keeps it pending and
        // retries on the next boot.
        val (_, existsOut, _) = rootExec("test -f $prefsPath && echo y || echo n")
        if (existsOut != "y") {
            throw IllegalStateException(
                "$prefsPath not present yet — open OpenBubbles once and this migration will apply on the next boot"
            )
        }

        // Quietly kill OpenBubbles so its in-memory SharedPreferences
        // cache can't write back over our edits. The shared helper
        // throws if OB is currently focused (avoids the crash-look),
        // otherwise SIGKILLs the PID — its sticky foreground service
        // restarts automatically without a visible app-died moment.
        OpenBubblesOps.stopQuietly(tag)

        // Read current contents, capture original ownership.
        val (catExit, content, catErr) = rootExec("cat $prefsPath")
        if (catExit != 0) {
            throw RuntimeException("cat $prefsPath failed (exit=$catExit): $catErr")
        }
        val (_, ownerOut, _) = rootExec("stat -c %u:%g $prefsPath")
        val owner = ownerOut.trim()

        val targets = mapOf(
            "flutter.autoDownload" to "false",
            "flutter.highPerfMode" to "true",
        )

        var modified = content
        var changes = 0
        for ((key, desiredValue) in targets) {
            val pattern = Regex(
                """<boolean\s+name="${Regex.escape(key)}"\s+value="(true|false)"\s*/>"""
            )
            val match = pattern.find(modified)
            if (match != null) {
                val current = match.groupValues[1]
                if (current == desiredValue) {
                    Log.d(tag, "OB perf migration: $key already $desiredValue — no change")
                } else {
                    modified = pattern.replace(
                        modified,
                        """<boolean name="$key" value="$desiredValue" />"""
                    )
                    changes++
                    Log.d(tag, "OB perf migration: $key $current -> $desiredValue")
                }
            } else if (modified.contains("</map>")) {
                modified = modified.replace(
                    "</map>",
                    "    <boolean name=\"$key\" value=\"$desiredValue\" />\n</map>"
                )
                changes++
                Log.d(tag, "OB perf migration: $key absent — inserted as $desiredValue")
            } else {
                Log.w(tag, "OB perf migration: prefs file missing </map> close — skipped $key")
            }
        }

        if (changes == 0) {
            Log.d(tag, "OB perf migration: nothing to write")
            return
        }

        // Stage the new content in our own cacheDir (visible to root via
        // init's mount namespace) then `cp` into place. Avoids embedding
        // the file content in a shell heredoc, which gets ugly fast for
        // XML with lots of quotes.
        val tmp = java.io.File(cacheDir, "_ob_prefs_v1.xml")
        try {
            tmp.writeText(modified)
            // World-readable so root cp can definitely see it through any
            // namespace mount weirdness (root reads anything anyway, but
            // belt-and-suspenders).
            tmp.setReadable(true, /* ownerOnly = */ false)

            val (cpExit, _, cpErr) = rootExec("cp ${tmp.absolutePath} $prefsPath")
            if (cpExit != 0) {
                throw RuntimeException("cp to $prefsPath failed (exit=$cpExit): $cpErr")
            }
            if (owner.isNotEmpty()) {
                rootExec("chown $owner $prefsPath")
            }
            // 660 matches what we observed on-device for FlutterSharedPreferences.xml.
            rootExec("chmod 660 $prefsPath")

            Log.d(tag, "OB perf migration: wrote $changes change(s) to $prefsPath")
        } finally {
            tmp.delete()
        }
    }

    /**
     * Applies opinionated WhatsApp media defaults by editing
     * `com.whatsapp_preferences_light.xml` directly. Direct analog of
     * [applyOpenBubblesPerfSettings] — same upsert-or-insert shape — with
     * three WhatsApp-specific differences:
     *
     *   1. The XML element type is `<int>`, not `<boolean>`. WhatsApp's
     *      auto-download settings are integer bitmasks, not booleans:
     *      bit 1 = images, 2 = audio, 4 = video, 8 = documents. Setting
     *      a mask to 0 disables all four media types for that network
     *      condition.
     *
     *   2. Three keys instead of two — one per network condition. Per
     *      `scripts/whatsapp_probe.sh` (run against WhatsApp 2.26.13.72
     *      on the TCL Flip Go), only two of the three exist by default:
     *      `autodownload_cellular_mask` and `autodownload_wifi_mask`.
     *      `autodownload_roaming_mask` is created lazily by WhatsApp
     *      when the user toggles the roaming auto-download setting in
     *      the UI; we insert it pre-zeroed so that switch flip can't
     *      surprise us later. The upsert-or-insert pattern handles
     *      whichever set of keys is present on a given device.
     *
     *   3. File mode is restored to 600, not 660. The probe captured
     *      mode 600 on this file (OpenBubbles' FlutterSharedPreferences
     *      file is 660). Wrong mode after the cp would either prevent
     *      WhatsApp from reading its own settings (too restrictive) or
     *      relax permissions (too permissive); 600 is what the file had
     *      before our edit, so we put it back.
     *
     * Skips cleanly if WhatsApp isn't installed. THROWS if the prefs
     * file isn't present yet (i.e., WhatsApp has never been opened on
     * this device — the file is created lazily on first launch). The
     * throw makes the migration framework leave the migration unapplied
     * and retry on the next boot.
     *
     * Settings applied:
     *  - `autodownload_cellular_mask` -> 0  (no cellular auto-download)
     *  - `autodownload_wifi_mask`     -> 0  (no wifi auto-download)
     *  - `autodownload_roaming_mask`  -> 0  (no roaming auto-download)
     */
    private fun applyWhatsAppMediaSettings(tag: String) {
        val waPkg = "com.whatsapp"

        // Skip cleanly when WhatsApp isn't installed.
        try { packageManager.getPackageInfo(waPkg, 0) }
        catch (_: PackageManager.NameNotFoundException) {
            Log.d(tag, "$waPkg not installed — skipping WA media settings migration")
            return
        }

        val prefsPath = "/data/data/$waPkg/shared_prefs/com.whatsapp_preferences_light.xml"

        // The prefs file doesn't exist until WhatsApp is launched once.
        // Throw so the migration framework keeps it pending and retries
        // on the next boot.
        val (_, existsOut, _) = rootExec("test -f $prefsPath && echo y || echo n")
        if (existsOut != "y") {
            throw IllegalStateException(
                "$prefsPath not present yet — open WhatsApp once and this migration will apply on the next boot"
            )
        }

        // Quietly kill WhatsApp so its in-memory SharedPreferences cache
        // can't write back over our edits. The shared helper throws if
        // WhatsApp is currently focused (avoids the crash-look),
        // otherwise SIGKILLs the PIDs.
        WhatsAppOps.stopQuietly(tag)

        // Read current contents, capture original ownership.
        val (catExit, content, catErr) = rootExec("cat $prefsPath")
        if (catExit != 0) {
            throw RuntimeException("cat $prefsPath failed (exit=$catExit): $catErr")
        }
        val (_, ownerOut, _) = rootExec("stat -c %u:%g $prefsPath")
        val owner = ownerOut.trim()

        // Bitmask: 1=images, 2=audio, 4=video, 8=documents. All three
        // masks → 0 disables auto-download on every network condition.
        val targets = mapOf(
            "autodownload_cellular_mask" to "0",
            "autodownload_wifi_mask" to "0",
            "autodownload_roaming_mask" to "0",
        )

        var modified = content
        var changes = 0
        for ((key, desiredValue) in targets) {
            val pattern = Regex(
                """<int\s+name="${Regex.escape(key)}"\s+value="(\d+)"\s*/>"""
            )
            val match = pattern.find(modified)
            if (match != null) {
                val current = match.groupValues[1]
                if (current == desiredValue) {
                    Log.d(tag, "WA media migration: $key already $desiredValue — no change")
                } else {
                    modified = pattern.replace(
                        modified,
                        """<int name="$key" value="$desiredValue" />"""
                    )
                    changes++
                    Log.d(tag, "WA media migration: $key $current -> $desiredValue")
                }
            } else if (modified.contains("</map>")) {
                modified = modified.replace(
                    "</map>",
                    "    <int name=\"$key\" value=\"$desiredValue\" />\n</map>"
                )
                changes++
                Log.d(tag, "WA media migration: $key absent — inserted as $desiredValue")
            } else {
                Log.w(tag, "WA media migration: prefs file missing </map> close — skipped $key")
            }
        }

        if (changes == 0) {
            Log.d(tag, "WA media migration: nothing to write")
            return
        }

        // Stage the new content in our own cacheDir (visible to root via
        // init's mount namespace) then `cp` into place. Avoids embedding
        // the file content in a shell heredoc, which gets ugly fast for
        // XML with lots of quotes.
        val tmp = java.io.File(cacheDir, "_wa_prefs_v1.xml")
        try {
            tmp.writeText(modified)
            // World-readable so root cp can definitely see it through any
            // namespace mount weirdness (root reads anything anyway, but
            // belt-and-suspenders).
            tmp.setReadable(true, /* ownerOnly = */ false)

            val (cpExit, _, cpErr) = rootExec("cp ${tmp.absolutePath} $prefsPath")
            if (cpExit != 0) {
                throw RuntimeException("cp to $prefsPath failed (exit=$cpExit): $cpErr")
            }
            if (owner.isNotEmpty()) {
                rootExec("chown $owner $prefsPath")
            }
            // 600 matches what we observed on-device for this file
            // (the OpenBubbles equivalent uses 660 — WhatsApp differs).
            rootExec("chmod 600 $prefsPath")

            Log.d(tag, "WA media migration: wrote $changes change(s) to $prefsPath")
        } finally {
            tmp.delete()
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
            // One-time OpenBubbles settings pass. Flips two values in
            // FlutterSharedPreferences.xml:
            //
            //   - flutter.autoDownload  true  -> false  (no auto-fetch of attachments)
            //   - flutter.highPerfMode  false -> true   (high-perf path on)
            //
            // The file is edited directly via root because OpenBubbles
            // exposes no IPC for these. OpenBubbles is killed quietly
            // first (see [OpenBubblesOps.stopQuietly]) so its in-memory
            // SharedPreferences cache can't write back over our changes.
            //
            // THROWS when the prefs file isn't present yet (i.e.,
            // OpenBubbles has never been opened on this device). Throwing
            // makes the migration framework leave the migration pending
            // and retry on the next boot.
            //
            // The attachment-cache wipe that used to live alongside this
            // migration now runs as a weekly periodic worker
            // ([OpenBubblesAttachmentCleanupWorker]) instead — once
            // every Sunday-ish at 2 AM. That keeps the cache from
            // accumulating long-term without piggy-backing the wipe
            // onto a one-time migration.
            //
            // This key supersedes the earlier `openbubbles_perf_settings_v1`
            // migration; the framework keys by the full string, so the
            // old row in migrations.xml stays around but is harmless.
            //
            // Bump the v-suffix (-> _v2 etc.) to force every device to
            // re-apply the settings.
            "openbubbles_setup_v1" to {
                applyOpenBubblesPerfSettings(tag)
            },
            // One-time WhatsApp media-settings pass. Flips three values
            // in com.whatsapp_preferences_light.xml:
            //
            //   - autodownload_cellular_mask  N -> 0  (no cellular auto-download)
            //   - autodownload_wifi_mask      N -> 0  (no wifi auto-download)
            //   - autodownload_roaming_mask   N -> 0  (no roaming auto-download)
            //
            // Bitmask semantics: 1=images, 2=audio, 4=video, 8=documents.
            // All three masks → 0 = WhatsApp never auto-downloads any
            // media on any network condition.
            //
            // The file is edited directly via root because WhatsApp
            // exposes no IPC for these. WhatsApp is killed quietly
            // first (see [WhatsAppOps.stopQuietly]) so its in-memory
            // SharedPreferences cache can't write back over our
            // changes. THROWS when the prefs file isn't present yet
            // (WhatsApp has never been opened on this device); the
            // throw makes the migration framework leave the migration
            // pending and retry on the next boot — same behaviour as
            // openbubbles_setup_v1 above.
            //
            // The attachment-cache rolling 7-day cleanup that pairs
            // with this runs as a weekly periodic worker
            // ([WhatsAppAttachmentCleanupWorker]) scheduled from
            // onCreate, exactly mirroring OpenBubbles.
            //
            // Bump the v-suffix (-> _v2 etc.) to force every device to
            // re-apply the settings.
            "whatsapp_setup_v1" to {
                applyWhatsAppMediaSettings(tag)
            },
            // Install the built-in "Dumb Line" support contact (14047163605 /
            // support@dumb.co) once so we don't have to provision it from a
            // shell with the legacy `adb shell content insert ...` flow.
            // [DumbLineContactInstaller.ensureInstalled] is idempotent — if the
            // contact is already present under our private account it's a
            // no-op, so re-running this migration (e.g. after clearing the
            // migrations prefs) won't create duplicates.
            "install_dumb_line_contact_v1" to {
                DumbLineContactInstaller.ensureInstalled(this)
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
