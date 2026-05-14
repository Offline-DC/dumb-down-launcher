package com.offlineinc.dumbdownlauncher.wifinudge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manual triggers for the "add wifi 2 save on data" nudge, mirroring the
 * existing test-trigger receivers (call-log cleanup, OpenBubbles
 * attachment cleanup, WhatsApp attachment cleanup). Exists so the nudge
 * flow can be exercised on demand from adb, bypassing the
 * 2nd-Tuesday/4th-Sunday cadence entirely.
 *
 * Four actions, each runnable with:
 *
 * ```
 * adb shell am broadcast \
 *   -a <ACTION> \
 *   -n com.offlineinc.dumbdownlauncher/.wifinudge.WifiNudgeTriggerReceiver
 * ```
 *
 *  - [ACTION_RUN_ALARM] — run the same code the scheduled alarm runs.
 *    Checks Wi-Fi state and posts the notification iff offline. Lets you
 *    confirm the wifi-state check works without waiting for the next
 *    occurrence (which is at most a couple of weeks out but usually
 *    longer than you want for testing).
 *
 *  - [ACTION_RUN_BOOT] — run the boot-time check synchronously (no 30 s
 *    delay). Same Wi-Fi check + conditional post as [ACTION_RUN_ALARM]
 *    but without the alarm-reschedule side effect — useful when you
 *    don't want to perturb the live alarm chain mid-test.
 *
 *  - [ACTION_FORCE_POST] — post the notification unconditionally,
 *    bypassing the Wi-Fi check. Useful for verifying notification
 *    appearance + the tap → cancel + open Wi-Fi settings flow in
 *    [WifiNudgeTapActivity] without having to turn Wi-Fi off first.
 *
 *  - [ACTION_CANCEL] — cancel any currently-posted nudge. Same code path
 *    the trampoline activity uses; lets you verify the in-app
 *    NotificationsActivity row clears even when the cancel is initiated
 *    from outside the app.
 *
 * Exposed with `exported=true` and no permission — same security posture
 * as the existing test triggers (see CallLogCleanupTriggerReceiver doc
 * comment). The worst case if another app fires these is the user sees
 * an extra Wi-Fi nudge they can dismiss in one tap.
 */
class WifiNudgeTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "onReceive: action=$action")

        when (action) {
            ACTION_RUN_ALARM -> {
                // Send through the real alarm receiver so the alarm
                // reschedule side-effect runs too — i.e. testing the
                // alarm path also confirms the chain re-arms correctly.
                val alarmIntent = Intent(WifiNudgeAlarmReceiver.ACTION).apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(alarmIntent)
            }
            ACTION_RUN_BOOT -> {
                // Simulate the boot path WITHOUT the 30 s wait. The
                // public nudgeOnBootIfOffline runs delayed; for testing
                // we just call the underlying check + post inline.
                try {
                    if (isOnWifi(context)) {
                        Log.i(TAG, "Trigger boot — already on Wi-Fi, skipping")
                    } else {
                        Log.i(TAG, "Trigger boot — not on Wi-Fi, posting")
                        WifiNudgeNotificationManager.notifyAddWifi(context)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Trigger boot failed", e)
                }
            }
            ACTION_FORCE_POST -> {
                try {
                    Log.i(TAG, "Trigger force-post — posting nudge regardless of Wi-Fi state")
                    WifiNudgeNotificationManager.notifyAddWifi(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Trigger force-post failed", e)
                }
            }
            ACTION_CANCEL -> {
                try {
                    Log.i(TAG, "Trigger cancel — clearing nudge")
                    WifiNudgeNotificationManager.cancel(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Trigger cancel failed", e)
                }
            }
            else -> Log.w(TAG, "Unknown action — ignoring")
        }
    }

    /**
     * Local copy of the Wi-Fi check that lives in [WifiNudgeAlarmReceiver].
     * Duplicated rather than exposed to keep [WifiNudgeAlarmReceiver]'s
     * surface area minimal — this receiver only exists for testing.
     */
    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        private const val TAG = "WifiNudgeTrigger"
        const val ACTION_RUN_ALARM = "com.offlineinc.dumbdownlauncher.RUN_WIFI_NUDGE_ALARM"
        const val ACTION_RUN_BOOT = "com.offlineinc.dumbdownlauncher.RUN_WIFI_NUDGE_BOOT"
        const val ACTION_FORCE_POST = "com.offlineinc.dumbdownlauncher.FORCE_POST_WIFI_NUDGE"
        const val ACTION_CANCEL = "com.offlineinc.dumbdownlauncher.CANCEL_WIFI_NUDGE"
    }
}
