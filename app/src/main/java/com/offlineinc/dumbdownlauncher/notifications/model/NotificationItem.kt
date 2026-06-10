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
    /**
     * The poster-supplied event time (Notification.when / EXTRA_SHOW_WHEN),
     * which for a missed-call notification is the time the call ended
     * unanswered. Falls back to [postTime] if the poster didn't supply one
     * (Notification.when == 0L), so callers can always treat it as a usable
     * timestamp.
     */
    val whenTime: Long = postTime,
)
