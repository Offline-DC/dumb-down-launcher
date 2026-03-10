package com.offlineinc.dumbdownlauncher.notifications.model

import android.app.PendingIntent

data class NotificationItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val contentIntent: PendingIntent?,
    val category: String? = null,
)
