package com.offlineinc.dumbdownlauncher

import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatDelegate
import com.offlineinc.dumbdownlauncher.coverdisplay.CoverDisplayService
import com.offlineinc.dumbdownlauncher.update.UpdateCheckWorker

class DumbDownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        UpdateCheckWorker.schedule(this)
        ensureNetworkLocationEnabled()
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
