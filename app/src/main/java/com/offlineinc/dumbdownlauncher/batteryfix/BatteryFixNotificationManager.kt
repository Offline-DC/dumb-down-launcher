package com.offlineinc.dumbdownlauncher.batteryfix

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Notifications surfaced when a battery-life fix has been applied by the
 * launcher and the user needs to do something to make it effective —
 * currently, restarting the phone so a Magisk overlay module activates.
 *
 * Channel uses IMPORTANCE_LOW so it lands in the shade and on the in-app
 * Notifications page (mirrored via [com.offlineinc.dumbdownlauncher
 * .notifications.DumbNotificationListenerService]) without ringing,
 * vibrating, or peeking as a heads-up — a heads-up would be too loud for
 * a one-time housekeeping fix.
 *
 * No tap action: the action the user needs to take is "press and hold
 * power", which is not something we can fire as an Intent. Auto-cancel
 * is on so the tap dismisses the row cleanly even though it does
 * nothing else.
 */
object BatteryFixNotificationManager {

    private const val CHANNEL_ID = "battery_fix"
    const val NOTIFICATION_ID_REBOOT_TO_APPLY = 4901

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Battery optimizations",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notifications about battery-life fixes applied by the launcher"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Posted by the `disable_reducesar_v1` migration after the Magisk
     * overlay module has been written to disk. The mask only takes
     * effect after a reboot, so we tell the user to restart.
     */
    fun notifyRebootToApply(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Restart to save battery")
            .setContentText("Press and hold the power button to restart")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(longArrayOf(0))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID_REBOOT_TO_APPLY, notification)
    }
}
