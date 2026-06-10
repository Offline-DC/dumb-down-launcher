package com.offlineinc.dumbdownlauncher.contactsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Posts notifications for the contact-sync flow:
 *   - an in-progress notification while a sync is running
 *   - a "finished syncing N contacts" notification when it completes
 *
 * Both use the same notification id so the in-progress entry is replaced
 * in place rather than stacking. The in-progress notification is ongoing
 * (not user-dismissable); the completed one is dismissable so the user
 * can clear it from the shade.
 *
 * Channel uses IMPORTANCE_LOW — the notification shows up in the shade
 * (and is mirrored into the in-app Notifications page via
 * [com.offlineinc.dumbdownlauncher.notifications.DumbNotificationListenerService])
 * but doesn't ring, vibrate, or peek as a heads-up.
 *
 * Modelled on WifiNudgeNotificationManager — see that class for the
 * trampoline / PendingIntent rationale this manager intentionally avoids
 * (tapping the entry just opens the contact-sync screen).
 */
object ContactSyncNotificationManager {

    private const val CHANNEL_ID = "contact_sync"
    const val NOTIFICATION_ID_CONTACT_SYNC = 3101

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Contact sync",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress updates while contacts are syncing"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** Activity PendingIntent that re-opens the contact-sync screen. */
    private fun openSyncPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ContactSyncActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_CONTACT_SYNC,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Post the ongoing "syncing contacts..." notification. Called when
     * a sync transfer begins and again on each progress tick so the
     * running count stays in sync with the on-screen number. Subsequent
     * calls replace the previous entry on the same notification id.
     *
     * Ongoing + non-auto-cancel so the user can't accidentally swipe it
     * away mid-sync — it gets replaced by [notifyComplete] when the
     * transfer finishes (or cleared by [cancel] on failure).
     *
     * @param syncedSoFar Running total of contacts on the phone so far,
     *   matching what HomeScreen displays. Null while we don't yet have
     *   a count (very first post, before any progress tick).
     */
    fun notifyInProgress(context: Context, syncedSoFar: Int? = null) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // No contentIntent — tapping the in-progress entry should do nothing.
        // The completed notification (posted by notifyComplete) is the one
        // that's tappable/dismissable.
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("syncing contacts...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(longArrayOf(0))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
        if (syncedSoFar != null) {
            builder.setContentText("$syncedSoFar contacts synced so far")
        }
        nm.notify(NOTIFICATION_ID_CONTACT_SYNC, builder.build())
    }

    /**
     * Replace the in-progress notification with a completed one.
     * Dismissable so the user can clear it from the shade.
     */
    fun notifyComplete(context: Context, contactCount: Int) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("finished syncing $contactCount contacts")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(longArrayOf(0))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openSyncPendingIntent(context))
            .build()
        nm.notify(NOTIFICATION_ID_CONTACT_SYNC, notification)
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID_CONTACT_SYNC)
    }
}
