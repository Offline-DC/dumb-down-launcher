package com.offlineinc.dumbdownlauncher.notifications

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem

class DumbNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        seedFromActive()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationStore.upsert(sbn.toItem())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationStore.remove(sbn.key)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return START_NOT_STICKY
                try { cancelNotification(key) } catch (_: Exception) {}
            }
            ACTION_CLEAR_ALL -> {
                try { cancelAllNotifications() } catch (_: Exception) {}
            }
            ACTION_SEED -> {
                seedFromActive()
            }
        }
        return START_NOT_STICKY
    }

    private fun seedFromActive() {
        try {
            val current = activeNotifications?.map { it.toItem() } ?: emptyList()
            NotificationStore.setAll(current)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun StatusBarNotification.toItem(): NotificationItem {
        val n = notification
        val extras = n.extras
        val title = extras.getCharSequence("android.title")?.toString()?.trim().orEmpty()
        val text =
            extras.getCharSequence("android.text")?.toString()?.trim()
                ?: extras.getCharSequence("android.bigText")?.toString()?.trim()
                ?: ""
        return NotificationItem(
            key = key,
            packageName = packageName,
            title = if (title.isBlank()) packageName else title,
            text = text,
            postTime = postTime,
            contentIntent = n.contentIntent,
            category = n.category,
        )
    }

    companion object {
        const val ACTION_DISMISS = "com.offlineinc.dumbdownlauncher.notifications.DISMISS"
        const val ACTION_CLEAR_ALL = "com.offlineinc.dumbdownlauncher.notifications.CLEAR_ALL"
        const val ACTION_SEED = "com.offlineinc.dumbdownlauncher.notifications.SEED"
        const val EXTRA_KEY = "key"
    }
}
