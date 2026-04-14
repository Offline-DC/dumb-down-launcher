package com.offlineinc.dumbdownlauncher

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatDelegate
import com.offlineinc.dumbdownlauncher.coverdisplay.CoverDisplayService
import com.offlineinc.dumbdownlauncher.quack.LocationCacheWorker
import com.offlineinc.dumbdownlauncher.update.UpdateCheckWorker

class DumbDownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        UpdateCheckWorker.schedule(this)
        ensureNetworkLocationEnabled()
        LocationCacheWorker.schedule(this)
        // Update FlipMouse (DumbMouse) binary if a newer version is bundled
        Thread { FlipMouseUpdater.checkAndUpdate(this) }.start()
        // Ensure the mouse accessibility service is bound at startup so it's
        // ready before the user toggles TypeSync for the first time.
        MouseAccessibilityService.appContext = applicationContext
        Thread { MouseAccessibilityService.ensureAccessibilityEnabled() }.start()

        // Start a periodic watchdog that re-enables the a11y service whenever
        // it drops (e.g. Android kills it in the background).
        MouseAccessibilityService.startWatchdog()

        // Listen for system-level accessibility state changes — if Android
        // disables accessibility services (e.g. after a crash), re-enable
        // ours immediately rather than waiting for the next watchdog tick.
        registerAccessibilityRecoveryListener()

        // Ensure the OpenBubbles "dumb" activation file exists (blank) so that
        // older builds that check for it still work. Does NOT overwrite an existing file.
        Thread { ensureOpenBubblesDumbFile() }.start()

        // One-time migrations that run once per version bump
        Thread { runOneTimeMigrations() }.start()

        // Enable swap if the file exists — swap doesn't survive reboot, but as
        // the HOME launcher our onCreate runs on every boot.
        Thread { enableSwapIfPresent() }.start()

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
                Thread { MouseAccessibilityService.ensureAccessibilityEnabled() }.start()
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
     * Runs one-time migrations keyed by name. Each migration executes at most
     * once across app updates. Add new entries to the map below.
     */
    private fun runOneTimeMigrations() {
        val tag = "DumbDownApp"
        val prefs = getSharedPreferences("migrations", Context.MODE_PRIVATE)

        val migrations = mapOf<String, () -> Unit>(
            // Delete the old "type_sync" notification channel that was used by the
            // now-removed WebKeyboardService foreground service.
            "delete_type_sync_channel" to {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.deleteNotificationChannel("type_sync")
                    Log.d(tag, "Deleted old type_sync notification channel")
                }
            },
            // Create a 512 MB swap file to give low-RAM devices more headroom
            // for memory-hungry apps (Uber Lite, WhatsApp, Chrome, etc.).
            // Removes any existing swap file first to avoid wasting space.
            "create_swap_512m" to {
                val swapTag = "SwapSetup"
                val swapFile = "/data/swapfile"
                val sizeMb = 512
                try {
                    Log.i(swapTag, "━━━ Starting $sizeMb MB swap setup ━━━")

                    // Disable + remove any existing swap file
                    val checkProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -f $swapFile && echo exists || echo missing"))
                    val checkOut = checkProc.inputStream.bufferedReader().readText().trim()
                    checkProc.waitFor()
                    if (checkOut == "exists") {
                        Log.i(swapTag, "Old swap file found — disabling and removing")
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
                prefs.edit().putBoolean(key, true).apply()
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
     * The file is created by the "create_swap_512m" migration; this just
     * re-enables it after every reboot.
     */
    private fun enableSwapIfPresent() {
        val tag = "SwapBoot"
        try {
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

    /**
     * Uses root to enable the network location provider if it's currently off.
     * Network location resolves in <1s vs 10-20s for a GPS cold start.
     * Runs on a background thread to avoid blocking app startup.
     */
    private fun ensureNetworkLocationEnabled() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return

        Thread {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put secure location_providers_allowed +network"))
                val exit = proc.waitFor()
                if (exit == 0) {
                    Log.d("DumbDownApp", "Enabled network location provider via root")
                } else {
                    Log.w("DumbDownApp", "Failed to enable network location (exit=$exit)")
                }
            } catch (e: Exception) {
                Log.w("DumbDownApp", "Cannot enable network location: ${e.message}")
            }
        }.start()
    }
}
