package com.offlineinc.dumbdownlauncher.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Posts the "Spotify was reset — tap to log back in" shade notification
 * after the long-press-on-Spotify hard-reset flow in [com.offlineinc.dumbdownlauncher.AllAppsActivity]
 * runs `pm clear com.spotify.music` via root.
 *
 * Why a notification (not just a Toast): `pm clear` is destructive. The
 * user just lost their session and needs to re-login the next time they
 * open Spotify. A shade entry persists past the toast timeout so the
 * reminder is still visible when they come back to the device later.
 * Tap launches Spotify directly so the user is one click away from the
 * login screen.
 *
 * Single notification id ([NOTIFICATION_ID_SPOTIFY_RESET]) so repeated
 * hard-resets collapse into one shade entry rather than stacking up.
 *
 * Channel uses IMPORTANCE_LOW — the entry sits in the shade and gets
 * mirrored into the in-app Notifications page by
 * [com.offlineinc.dumbdownlauncher.notifications.DumbNotificationListenerService]
 * but doesn't ring or peek. The user explicitly triggered the reset; no
 * need to interrupt them with a heads-up about an action they just took.
 */
object SpotifyResetNotificationManager {

    private const val CHANNEL_ID = "spotify_reset"
    const val NOTIFICATION_ID_SPOTIFY_RESET = 3101

    private const val SPOTIFY_PKG = "com.spotify.music"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Spotify reset",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Confirmation after a long-press hard-reset of Spotify"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * PendingIntent that launches Spotify directly via
     * [android.content.pm.PackageManager.getLaunchIntentForPackage].
     *
     * After `pm clear` Spotify is reinstalled-fresh from the user's
     * perspective; the launch intent lands on its first-run welcome
     * / login screen, which is exactly where the user needs to be to
     * recover from the reset. `FLAG_ACTIVITY_NEW_TASK` is required
     * because we're starting from a notification, which has no task
     * affinity of its own.
     *
     * Falls back to a self-targeted no-op PendingIntent if Spotify
     * has been uninstalled between the long-press and the
     * notification post (vanishingly rare, but the API allows null
     * here so we have to handle it).
     */
    private fun launchSpotifyPendingIntent(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ?: Intent() // unreachable in practice — Spotify was just `pm clear`ed, not uninstalled
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_SPOTIFY_RESET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Posts the reset-confirmation notification. Safe to call from
     * any thread (NotificationManager.notify is thread-safe).
     */
    fun notifyReset(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Spotify was reset")
            .setContentText("Tap to log back in")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(longArrayOf(0))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(launchSpotifyPendingIntent(context))
            .build()
        nm.notify(NOTIFICATION_ID_SPOTIFY_RESET, notification)
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID_SPOTIFY_RESET)
    }
}
