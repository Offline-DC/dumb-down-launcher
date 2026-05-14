package com.offlineinc.dumbdownlauncher.wifinudge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Posts the "add wifi 2 save on data" nudge.
 *
 * Tap opens the system Wi-Fi settings page so the user can hop straight
 * to picking a network. Single notification id ([NOTIFICATION_ID_WIFI_NUDGE])
 * so back-to-back fires (e.g. the 2nd-Tuesday and 4th-Sunday alarms landing
 * close together) collapse into one shade entry rather than stacking.
 *
 * Channel uses IMPORTANCE_LOW — the nudge shows up in the shade and on the
 * in-app Notifications page (the listener service mirrors it into
 * [com.offlineinc.dumbdownlauncher.notifications.NotificationStore]) but
 * doesn't ring, vibrate, or peek as a heads-up.
 */
object WifiNudgeNotificationManager {

    private const val CHANNEL_ID = "wifi_nudge"
    const val NOTIFICATION_ID_WIFI_NUDGE = 3001

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wi-Fi nudges",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Reminders to connect to Wi-Fi to save mobile data"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Tap trampoline: an activity PendingIntent that launches
     * [WifiNudgeTapActivity]. That headless activity cancels the
     * notification first (which propagates through
     * [com.offlineinc.dumbdownlauncher.notifications.DumbNotificationListenerService]
     * to clear the in-app Notifications row too) and then opens the
     * system Wi-Fi settings page.
     *
     * Activity (not broadcast) is required: a broadcast → startActivity
     * hop fired from a notification gets silently dropped on Android 10+
     * (background activity start restriction) and is explicitly blocked
     * on Android 12+ (notification trampoline restriction). Activity
     * PendingIntents are exempt from both — see [WifiNudgeTapActivity]
     * for the full rationale.
     *
     * Routing through a trampoline (instead of a direct
     * [Settings.ACTION_WIFI_SETTINGS] activity PendingIntent) is also
     * what reliably clears the in-app row. When the user taps the row
     * inside [com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity],
     * that screen calls `pi.send()` manually, which does NOT trigger the
     * framework's auto-cancel logic. The trampoline cancels explicitly,
     * so the result is the same regardless of where the tap came from
     * (system shade or in-app page).
     */
    private fun wifiSettingsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WifiNudgeTapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_WIFI_NUDGE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun notifyAddWifi(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("add wifi 2 save on data")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(longArrayOf(0))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(wifiSettingsPendingIntent(context))
            .build()
        nm.notify(NOTIFICATION_ID_WIFI_NUDGE, notification)
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID_WIFI_NUDGE)
    }
}
