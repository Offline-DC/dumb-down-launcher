package com.offlineinc.dumbdownlauncher.wifinudge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * Headless trampoline that handles taps on the "add wifi 2 save on data"
 * notification. Two responsibilities, in order:
 *
 *   1. Cancel the nudge so it disappears from the system shade AND, via
 *      [com.offlineinc.dumbdownlauncher.notifications.DumbNotificationListenerService],
 *      from the in-app Notifications page too.
 *   2. Launch the system Wi-Fi settings page so the user lands where they
 *      can pick a network.
 *
 * Why an Activity (not a BroadcastReceiver):
 *   Two overlapping platform restrictions make a broadcast trampoline
 *   unreliable:
 *     - Android 10+ (the TCL Flip Go is on 11): apps that aren't in the
 *       foreground can't start activities. A notification tap delivers
 *       the broadcast but the receiver's `startActivity` call gets
 *       silently dropped, which is exactly the "tap does nothing"
 *       symptom we hit on the TCL.
 *     - Android 12+: the notification-trampoline rule additionally
 *       blocks broadcast/service → activity hops fired from a
 *       notification, even if the app would otherwise be allowed.
 *   Activity-typed PendingIntents are exempt from both, so a tiny no-UI
 *   trampoline activity is the recommended pattern that works across
 *   every supported Android version.
 *
 * This Activity has no layout. It does its work in `onCreate` and
 * immediately calls `finish()`, then disables both transitions so the
 * user sees a clean shade → Wi-Fi settings handoff with no flicker.
 *
 * Works for taps from BOTH surfaces:
 *   - System shade: the platform fires our contentIntent, which is a
 *     `getActivity` PendingIntent pointing here.
 *   - In-app Notifications page: `NotificationsActivity.onOpen` calls
 *     `pi.send()` on the same PendingIntent, which launches this
 *     activity, which cancels — and the listener service propagates
 *     the removal back into [com.offlineinc.dumbdownlauncher.notifications.NotificationStore]
 *     so the row clears too.
 */
class WifiNudgeTapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Tap received — cancelling nudge and opening Wi-Fi settings")

        // Order matters: cancel first so the in-app row clears even if
        // the activity launch below throws (some OEMs strip
        // ACTION_WIFI_SETTINGS — we'd rather lose the deep-link than
        // leave a sticky entry on the Notifications page).
        try {
            WifiNudgeNotificationManager.cancel(this)
        } catch (e: Exception) {
            Log.w(TAG, "Cancel on tap failed", e)
        }

        try {
            val settings = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                // Trampoline activity itself is finishing — the wifi
                // settings task needs to live in its own task stack,
                // not inherit ours.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(settings)
        } catch (e: Exception) {
            Log.w(TAG, "Launch Wi-Fi settings failed", e)
        }

        finish()
        // Suppress both the enter and exit transitions so the user
        // perceives an instant jump from the shade/Notifications page
        // straight to Wi-Fi settings. Without this you'd see a brief
        // empty Activity flash.
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "WifiNudgeTap"
    }
}
