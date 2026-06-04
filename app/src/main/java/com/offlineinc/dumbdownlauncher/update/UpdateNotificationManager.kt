package com.offlineinc.dumbdownlauncher.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object UpdateNotificationManager {

    private const val CHANNEL_ID = "app_updates"
    /**
     * Lower-importance channel for the daily beta-tester check-in. Kept
     * separate from [CHANNEL_ID] so a beta tester can mute the daily
     * reminder via system settings without losing the high-priority
     * "Update available" notifications they actually opted in for.
     */
    private const val BETA_CHANNEL_ID = "beta_reminders"
    const val NOTIFICATION_ID_LAUNCHER = 1001
    const val NOTIFICATION_ID_SNAKE = 1003
    const val NOTIFICATION_ID_OPENBUBBLES = 1004
    /**
     * Daily reminder posted by
     * [com.offlineinc.dumbdownlauncher.update.BetaUpdateReminderWorker].
     * Distinct from the per-app update IDs above so it can coexist with a
     * real "Update available" notification without one cancelling the
     * other in the shade.
     */
    const val NOTIFICATION_ID_BETA_REMINDER = 1010

    const val ACTION_DOWNLOAD_APK = "com.offlineinc.dumbdownlauncher.action.DOWNLOAD_APK"
    const val EXTRA_DOWNLOAD_URL = "extra_download_url"
    const val EXTRA_APP_KEY = "extra_app_key"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when app updates are available"
        }
        nm.createNotificationChannel(channel)
    }

    private fun ensureBetaChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(BETA_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            BETA_CHANNEL_ID,
            "Beta Reminders",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Daily check-ins for beta testers"
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Post (or replace) the daily beta-tester reminder notification.
     *
     * Only called when a newer beta build than the installed one has been
     * detected — the "no new builds today" silent path lives in
     * [com.offlineinc.dumbdownlauncher.update.BetaUpdateReminderWorker],
     * which simply returns success without notifying when nothing's available.
     *
     * Posted to [BETA_CHANNEL_ID] (low importance), so it appears silently
     * in the shade. Pairs with the higher-priority [notify] for the same
     * release so a beta tester who dismisses one still has the other to
     * fall back on.
     */
    fun notifyBetaReminder(
        context: Context,
        updateVersionName: String,
        updateDownloadUrl: String,
    ) {
        ensureBetaChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = Intent(ACTION_DOWNLOAD_APK).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_DOWNLOAD_URL, updateDownloadUrl)
            putExtra(EXTRA_APP_KEY, "dumb-down-launcher")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID_BETA_REMINDER,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // FLAG_NO_CLEAR keeps this exempt from the shade's "Clear all"
        // button so a daily-cron reminder isn't wiped by an unrelated
        // shade sweep; the user can still swipe it away individually.
        val notification = NotificationCompat.Builder(context, BETA_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("New beta build available")
            .setContentText("v$updateVersionName is ready — tap to install")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
            .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR }
        nm.notify(NOTIFICATION_ID_BETA_REMINDER, notification)
    }

    fun notify(
        context: Context,
        notificationId: Int,
        appKey: String,
        appDisplayName: String,
        versionName: String,
        downloadUrl: String,
    ) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = Intent(ACTION_DOWNLOAD_APK).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
            putExtra(EXTRA_APP_KEY, appKey)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // FLAG_NO_CLEAR exempts this from the shade's "Clear all" button
        // so a casual sweep doesn't lose the update prompt — but the user
        // can still swipe it away individually if they want.
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update available")
            .setContentText("$appDisplayName v$versionName is ready to install")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()
            .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR }

        nm.notify(notificationId, notification)
    }

    fun notifyDownloading(context: Context, appKey: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = notificationIdFor(appKey)
        val appDisplayName = displayNameFor(appKey)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading update")
            .setContentText("$appDisplayName is downloading…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        nm.notify(notificationId, notification)
    }

    fun notifyFailed(context: Context, appKey: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = notificationIdFor(appKey)
        val appDisplayName = displayNameFor(appKey)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Update failed")
            .setContentText("$appDisplayName could not be installed")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        nm.notify(notificationId, notification)
    }

    private fun notificationIdFor(appKey: String) = when (appKey) {
        "dumb-down-launcher" -> NOTIFICATION_ID_LAUNCHER
        "snake" -> NOTIFICATION_ID_SNAKE
        "openbubbles-messaging" -> NOTIFICATION_ID_OPENBUBBLES
        else -> NOTIFICATION_ID_LAUNCHER
    }

    private fun displayNameFor(appKey: String) = when (appKey) {
        "dumb-down-launcher" -> "Dumb Launcher"
        "snake" -> "Snake"
        "openbubbles-messaging" -> "OpenBubbles"
        else -> appKey
    }

    /**
     * Replace the "Update available" notification for [appKey] with a "needs
     * Wi-Fi" prompt. Posted to the same notification ID so it overwrites the
     * tappable update tile in the shade — the user sees one consistent slot
     * for that app's update state. Auto-cancels so it disappears once tapped;
     * the next periodic update check will re-post the regular tile if the
     * update is still pending.
     */
    fun notifyWifiRequired(context: Context, appKey: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = notificationIdFor(appKey)
        val appDisplayName = displayNameFor(appKey)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Connect to Wi-Fi to update")
            .setContentText("$appDisplayName update needs Wi-Fi — connect, then tap again")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        nm.notify(notificationId, notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notificationId)
    }
}
