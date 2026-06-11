package com.offlineinc.dumbdownlauncher.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesOps

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
    // Result broadcast from a PackageInstaller split-APK session (OpenBubbles).
    const val ACTION_INSTALL_RESULT = "com.offlineinc.dumbdownlauncher.action.INSTALL_RESULT"
    const val EXTRA_DOWNLOAD_URL = "extra_download_url"
    const val EXTRA_APP_KEY = "extra_app_key"

    // Persisted state for the sticky OpenBubbles forced-update tile, so it can
    // be re-posted after a reboot (which clears all notifications).
    private const val OB_PENDING_PREFS = "ob_update_pending"
    private const val KEY_OB_PENDING_URL = "url"
    private const val KEY_OB_PENDING_VERSION = "version"

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

        // OpenBubbles is a forced update — its tile is fully sticky: ongoing
        // (can't be swiped away) AND persisted so it can be re-posted after a
        // reboot (the OS clears all notifications on boot). Other apps' update
        // tiles stay dismissable-by-swipe but exempt from "Clear all".
        val isForced = appKey == "openbubbles-messaging"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update available")
            .setContentText("$appDisplayName v$versionName is ready to install")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(isForced)
            .setContentIntent(pendingIntent)
            .build()
            // FLAG_NO_CLEAR exempts from the shade's "Clear all" button; for the
            // forced OpenBubbles tile FLAG_ONGOING_EVENT (via setOngoing) also
            // blocks individual swipe-away.
            .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR }

        if (isForced) {
            // Persist so we can re-post on the next launcher start (≈ boot).
            context.getSharedPreferences(OB_PENDING_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_OB_PENDING_URL, downloadUrl)
                .putString(KEY_OB_PENDING_VERSION, versionName)
                .apply()
        }

        nm.notify(notificationId, notification)
    }

    /**
     * Re-posts the sticky OpenBubbles "update available" tile from persisted
     * state if an update is still pending. Called on launcher start (which on a
     * home launcher is effectively boot) so the forced-update prompt survives
     * reboots — Android clears all posted notifications on boot. If OpenBubbles
     * has since been updated to [OpenBubblesOps.MIN_SUPPORTED_VERSION_CODE] or
     * newer, the persisted state is cleared and the tile cancelled instead.
     */
    fun repostOpenBubblesUpdateIfPending(context: Context) {
        val prefs = context.getSharedPreferences(OB_PENDING_PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_OB_PENDING_URL, null) ?: return
        val versionName = prefs.getString(KEY_OB_PENDING_VERSION, "") ?: ""

        // Only relevant when the user is on the iOS (OpenBubbles) smart-txt
        // path. If they've switched to Android/none, clear the forced prompt.
        if (com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences.getChoice(context) != "ios") {
            prefs.edit().clear().apply()
            cancel(context, NOTIFICATION_ID_OPENBUBBLES)
            return
        }

        val installed = OpenBubblesOps.installedVersionCode(context)
        if (installed == null || installed >= OpenBubblesOps.MIN_SUPPORTED_VERSION_CODE) {
            // Updated (or uninstalled) — clear the forced prompt.
            prefs.edit().clear().apply()
            cancel(context, NOTIFICATION_ID_OPENBUBBLES)
            return
        }

        notify(
            context = context,
            notificationId = NOTIFICATION_ID_OPENBUBBLES,
            appKey = "openbubbles-messaging",
            appDisplayName = displayNameFor("openbubbles-messaging"),
            versionName = versionName,
            downloadUrl = url,
        )
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
            // Indeterminate bar until the first progress poll reports real
            // byte counts (see notifyDownloadProgress).
            .setProgress(100, 0, true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(notificationId, notification)
    }

    /**
     * Re-post the "Downloading update" notification for [appKey] with live
     * progress — a determinate bar plus "X / Y MB (Z%)" text, WhatsApp-style.
     * Posted to the same notification ID as [notifyDownloading] so it updates
     * the existing tile in place. [setOnlyAlertOnce] keeps the ~1s re-posts
     * from re-sounding/vibrating each tick. While [totalBytes] is unknown
     * (DownloadManager reports -1 until it has response headers) the bar stays
     * indeterminate and the text shows bytes-so-far only.
     */
    fun notifyDownloadProgress(
        context: Context,
        appKey: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = notificationIdFor(appKey)
        val appDisplayName = displayNameFor(appKey)

        val knownTotal = totalBytes > 0
        val pct = if (knownTotal) {
            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else 0
        val text = if (knownTotal) {
            "$appDisplayName: ${formatMb(downloadedBytes)} / ${formatMb(totalBytes)} MB ($pct%)"
        } else {
            "$appDisplayName: ${formatMb(downloadedBytes)} MB so far…"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading update")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setProgress(100, pct, !knownTotal)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(notificationId, notification)
    }

    private fun formatMb(bytes: Long): String =
        String.format("%.1f", bytes / (1024.0 * 1024.0))

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

    /**
     * Replace the (now-finished) download tile with an "Installing…" state —
     * ongoing + indeterminate bar — for the stretch between download-complete
     * and the install result. Without this the frozen download progress bar
     * lingers at whatever % it last polled (e.g. 94%) through the whole
     * multi-minute split install, which looks stuck.
     */
    fun notifyInstalling(context: Context, appKey: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appDisplayName = displayNameFor(appKey)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Installing update")
            .setContentText("$appDisplayName is installing…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setProgress(0, 0, true) // indeterminate
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(notificationIdFor(appKey), notification)
    }

    /**
     * Success confirmation, replacing the "Installing…" tile. Clearable and
     * self-dismisses after a few seconds so it doesn't linger. For OpenBubbles
     * it also clears the persisted forced-update state so the sticky prompt
     * never comes back for this version.
     */
    fun notifyInstalled(context: Context, appKey: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val displayName = displayNameFor(appKey)

        if (appKey == "openbubbles-messaging") {
            context.getSharedPreferences(OB_PENDING_PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        // Tapping the success tile opens the freshly-updated app and clears the
        // notification (setAutoCancel). Only wired for OpenBubbles ("smart txt").
        val launchIntent = if (appKey == "openbubbles-messaging") {
            context.packageManager.getLaunchIntentForPackage("com.openbubbles.messaging")
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        } else {
            null
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                notificationIdFor(appKey),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update installed")
            .setContentText("$displayName is up to date")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .setTimeoutAfter(8_000L) // auto-dismiss after 8s if not tapped
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        nm.notify(notificationIdFor(appKey), builder.build())
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
        // User-facing brand on this device is "Smart Txt", not "OpenBubbles".
        "openbubbles-messaging" -> "Smart Txt"
        else -> appKey
    }

    /**
     * Replace the "Update available" notification for [appKey] with a "needs
     * Wi-Fi" prompt. Posted to the same notification ID so it overwrites the
     * tappable update tile in the shade — the user sees one consistent slot
     * for that app's update state. Auto-cancels so it disappears once tapped;
     * the next periodic update check will re-post the regular tile if the
     * update is still pending.
     *
     * Tapping it routes through [WifiThenUpdateActivity], which decides at tap
     * time: if the phone is already on Wi-Fi it fires the download; otherwise
     * it opens the system Wi-Fi settings so the user can connect, then tap
     * again. An activity-typed PendingIntent is used deliberately: notification
     * taps are exempt from the Android 10+/12+ background-activity-start and
     * notification-trampoline restrictions only when the PendingIntent targets
     * an activity directly — see
     * [com.offlineinc.dumbdownlauncher.wifinudge.WifiNudgeTapActivity] for the
     * full rationale. [downloadUrl] is carried through so the tap-time download
     * can be re-fired.
     */
    fun notifyWifiRequired(context: Context, appKey: String, downloadUrl: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = notificationIdFor(appKey)

        val tapIntent = Intent(context, WifiThenUpdateActivity::class.java).apply {
            putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
            putExtra(EXTRA_APP_KEY, appKey)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // FLAG_NO_CLEAR exempts this from the shade's "Clear all" button AND
        // the in-launcher Clear All (which calls cancelAllNotifications(),
        // which likewise skips no-clear notifications) — so a sweep can't lose
        // the "you must get on Wi-Fi to update" prompt. autoCancel still
        // removes it on tap, and the user can still swipe it away individually.
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("connect to wifi to update")
            .setContentText("then click to update Smart Txt")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
            .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR }
        nm.notify(notificationId, notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notificationId)
    }
}
