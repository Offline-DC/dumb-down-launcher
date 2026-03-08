package com.offlineinc.dumbdownlauncher.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object UpdateNotificationManager {

    private const val CHANNEL_ID = "app_updates"
    const val NOTIFICATION_ID_LAUNCHER = 1001
    const val NOTIFICATION_ID_CONTACTS = 1002

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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update available")
            .setContentText("$appDisplayName v$versionName is ready to install")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(notificationId, notification)
    }

    fun notifyDownloading(context: Context, appKey: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = if (appKey == "dumb-down-launcher") NOTIFICATION_ID_LAUNCHER else NOTIFICATION_ID_CONTACTS
        val appDisplayName = if (appKey == "dumb-down-launcher") "Dumb Launcher" else "Dumb Contacts Sync"
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

    fun cancel(context: Context, notificationId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notificationId)
    }
}
