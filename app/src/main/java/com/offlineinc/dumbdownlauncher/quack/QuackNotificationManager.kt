package com.offlineinc.dumbdownlauncher.quack

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Manages silent quack reminder notifications.
 *
 * Uses IMPORTANCE_LOW so notifications appear in the shade without sound,
 * vibration, or heads-up peek. Tapping opens [QuackActivity].
 */
object QuackNotificationManager {

    private const val CHANNEL_ID = "quack_reminders"
    const val NOTIFICATION_ID_BE_FIRST = 2001
    const val NOTIFICATION_ID_SOMEBODY_QUACKED = 2002
    const val NOTIFICATION_ID_CONSENT_NUDGE = 2003

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quack Reminders",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Weekly quack reminders"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun quackPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, QuackActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun notifyBeFirst(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("quack")
            .setContentText("be the first to quack this week")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(longArrayOf(0))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(quackPendingIntent(context, NOTIFICATION_ID_BE_FIRST))
            .build()
        nm.notify(NOTIFICATION_ID_BE_FIRST, notification)
    }

    fun notifySomebodyQuacked(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Dismiss "be the first" if it's still showing
        nm.cancel(NOTIFICATION_ID_BE_FIRST)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("quack")
            .setContentText("somebody quacked nearby")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(longArrayOf(0))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(quackPendingIntent(context, NOTIFICATION_ID_SOMEBODY_QUACKED))
            .build()
        nm.notify(NOTIFICATION_ID_SOMEBODY_QUACKED, notification)
    }

    /**
     * One-time nudge sent on the first Monday alarm where the user has no
     * usable location stored. Title-only ("wanna quack?") with no body —
     * tapping opens [QuackActivity], which surfaces the location consent
     * prompt. Sent at most once ever; see [QuackLocationConsentStore.hasNudged].
     */
    fun notifyConsentNudge(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("wanna quack?")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(longArrayOf(0))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(quackPendingIntent(context, NOTIFICATION_ID_CONSENT_NUDGE))
            .build()
        nm.notify(NOTIFICATION_ID_CONSENT_NUDGE, notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notificationId)
    }
}
