package com.offlineinc.dumbdownlauncher.notifications

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
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
            val active = activeNotifications ?: emptyArray<StatusBarNotification>()

            // Self-heal stuck call overlays on cover display.
            //
            // The cover screen treats any CATEGORY_CALL notification as a live
            // incoming/ongoing call and renders a full-screen overlay for the
            // caller. If the dialer process is killed mid-call (reboot, OOM,
            // force-stop) it can leave an orphan call notification behind that
            // the system happily re-posts on every listener reconnect. Without
            // this cleanup, seedFromActive() would shovel the orphan straight
            // back into NotificationStore and the overlay would re-appear with
            // the same caller name forever — surviving any number of reboots.
            //
            // Rule: if the radio reports CALL_STATE_IDLE, no CATEGORY_CALL
            // notification can possibly be valid, so cancel it from the system
            // *and* exclude it from the seed.
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val callState = try {
                @Suppress("DEPRECATION") // sync getter, sufficient for a one-shot check
                tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
            } catch (_: SecurityException) {
                TelephonyManager.CALL_STATE_IDLE
            }
            val orphanCallKeys: Set<String> =
                if (callState == TelephonyManager.CALL_STATE_IDLE) {
                    active.asSequence()
                        .filter { it.notification?.category == Notification.CATEGORY_CALL }
                        .map { it.key }
                        .toSet()
                } else emptySet()
            for (key in orphanCallKeys) {
                try {
                    cancelNotification(key)
                    Log.i(TAG, "Cancelled orphan call notification on seed: $key")
                } catch (_: Exception) {
                    // best-effort; if it can't be cancelled, the CoverScreen
                    // call-state gate will still prevent it from rendering.
                }
            }

            val current = active
                .filter { it.key !in orphanCallKeys }
                .map { it.toItem() }
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
