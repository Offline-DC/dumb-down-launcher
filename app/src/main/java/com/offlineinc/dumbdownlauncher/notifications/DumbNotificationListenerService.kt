package com.offlineinc.dumbdownlauncher.notifications

import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.offlineinc.dumbdownlauncher.launcher.dnd.MuteState
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem

class DumbNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()

        // Ensure MuteState is initialised from prefs (covers cold-start
        // where DndMuteManager.refreshFromSystem hasn't run yet).
        val prefs = applicationContext
            .getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        MuteState.muted = prefs.getBoolean("messages_muted", true)

        applyListenerHints()
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
            ACTION_UPDATE_MUTE -> {
                applyListenerHints()
            }
        }
        return START_NOT_STICKY
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Tells the system to suppress (or restore) notification effects
     * (sound + vibration) for all notifications.
     *
     * This does NOT affect call ringtones — those are played by the
     * telephony / InCallUI via STREAM_RING, completely outside the
     * notification system.  Notifications remain fully visible in the
     * status bar and notification shade.
     */
    private fun applyListenerHints() {
        try {
            val hints = if (MuteState.muted) {
                HINT_HOST_DISABLE_NOTIFICATION_EFFECTS
            } else {
                0
            }
            requestListenerHints(hints)
        } catch (t: Throwable) {
            Log.e(TAG, "requestListenerHints failed", t)
        }
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
        private const val TAG = "DUMB_MUTE"
        const val ACTION_DISMISS = "com.offlineinc.dumbdownlauncher.notifications.DISMISS"
        const val ACTION_CLEAR_ALL = "com.offlineinc.dumbdownlauncher.notifications.CLEAR_ALL"
        const val ACTION_SEED = "com.offlineinc.dumbdownlauncher.notifications.SEED"
        const val ACTION_UPDATE_MUTE = "com.offlineinc.dumbdownlauncher.notifications.UPDATE_MUTE"
        const val EXTRA_KEY = "key"
    }
}
