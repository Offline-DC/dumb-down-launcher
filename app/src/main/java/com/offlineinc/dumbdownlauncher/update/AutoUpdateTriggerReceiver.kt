package com.offlineinc.dumbdownlauncher.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manual adb trigger for the nightly auto-updater, so the full
 * check-conditions → download → silent-install path can be exercised on demand
 * without waiting for 4 AM. Mirrors the other `RUN_*` trigger receivers in the
 * manifest (call-log cleanup, wifi nudge, etc.).
 *
 *   adb shell am broadcast -a com.offlineinc.dumbdownlauncher.RUN_AUTO_UPDATE \
 *       -n com.offlineinc.dumbdownlauncher/.update.AutoUpdateTriggerReceiver
 *
 * Still honours the Wi-Fi + battery gate inside
 * [AutoUpdateAlarmReceiver.runAutoUpdate] — it's the same code path the alarm
 * runs, just fired immediately. Worst case is installing an update the user
 * would have gotten at 4 AM anyway.
 */
class AutoUpdateTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "manual auto-update trigger: ${intent.action}")
        val pending = goAsync()
        Thread {
            try {
                AutoUpdateAlarmReceiver.runAutoUpdate(context)
            } catch (t: Throwable) {
                Log.e(TAG, "manual auto-update run failed", t)
            } finally {
                try {
                    pending.finish()
                } catch (_: Throwable) {
                }
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "AutoUpdate"
        const val ACTION_RUN = "com.offlineinc.dumbdownlauncher.RUN_AUTO_UPDATE"
    }
}
