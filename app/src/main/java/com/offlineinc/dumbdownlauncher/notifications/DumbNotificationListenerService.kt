package com.offlineinc.dumbdownlauncher.notifications

import android.app.Notification
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
        sbn.ignoreReason()?.let { reason ->
            Log.i(
                TAG,
                "skip ${sbn.packageName} id=${sbn.id} flags=0x${Integer.toHexString(sbn.notification.flags)} reason=$reason"
            )
            // For pure shade-rendering artifacts (group summaries, autogroup
            // summaries, blank-content placeholders) we also dismiss them at
            // the system level so SystemUI's status bar notification count
            // reflects what the launcher actually shows. We do NOT cancel
            // foreground-service or ongoing-event notifications — Android
            // either rejects those or the source app re-posts immediately,
            // which would create a noisy fight with the system.
            if (reason in CANCEL_AT_SYSTEM_REASONS) {
                try {
                    cancelNotification(sbn.key)
                } catch (t: Throwable) {
                    Log.w(TAG, "cancelNotification failed for ${sbn.key}", t)
                }
            }
            return
        }
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
            val all = activeNotifications ?: return
            val kept = mutableListOf<NotificationItem>()
            for (sbn in all) {
                val reason = sbn.ignoreReason()
                if (reason != null) {
                    Log.i(
                        TAG,
                        "seed-skip ${sbn.packageName} id=${sbn.id} flags=0x${Integer.toHexString(sbn.notification.flags)} reason=$reason"
                    )
                    continue
                }
                kept.add(sbn.toItem())
            }
            NotificationStore.setAll(kept)
        } catch (_: Exception) {
            // ignore
        }
    }

    /**
     * Returns a non-null reason string when [sbn] should be dropped, or null
     * when it should appear in the notification list. Logged on each skip so
     * we can verify in logcat which filter caught a given notification.
     *
     * Deliberately narrow — we only filter notifications that have nothing
     * meaningful to display. System notifications like USB debugging, charging
     * indicators, foreground-service status, etc. all carry real titles/text
     * and pass through normally; the user wants to see those on the launcher's
     * notification page.
     *
     * Filters, in order:
     * 1. FLAG_GROUP_SUMMARY / FLAG_AUTOGROUP_SUMMARY — shade-rendering
     *    artifacts (one summary per notification group, used by the system
     *    shade to render a collapsed header). E.g. OpenBubbles posts
     *    NEW_MESSAGE_NOTIFICATION id=0 with flags=0x210 alongside the
     *    actual per-message notification at id=N.
     * 2. Empty content — title, text, AND bigText all blank. With nothing
     *    to display, [toItem] would fall back to the package name (which
     *    is exactly the rendering we're trying to avoid). Real system
     *    notifications always set at least one of these.
     */
    private fun StatusBarNotification.ignoreReason(): String? {
        val n = notification
        val flags = n.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return "FLAG_GROUP_SUMMARY"
        // FLAG_AUTOGROUP_SUMMARY = 0x400. Constant is hidden, hence the literal.
        if (flags and 0x400 != 0) return "FLAG_AUTOGROUP_SUMMARY"

        val extras = n.extras
        val title = extras?.getCharSequence("android.title")?.toString()?.trim().orEmpty()
        val text = extras?.getCharSequence("android.text")?.toString()?.trim().orEmpty()
        val bigText = extras?.getCharSequence("android.bigText")?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty() && bigText.isEmpty()) return "blank-title-and-text"

        return null
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

        /**
         * [ignoreReason] values for which we should additionally call
         * [cancelNotification] to remove the notification from SystemUI as
         * well, not just from our list. Limited to shade-rendering artifacts
         * so we don't fight the system over foreground-service notifications.
         *
         * NOTE: "FLAG_GROUP_SUMMARY" is deliberately NOT in this set.
         * Cancelling an app-posted group summary via NotificationListener
         * cascades through NotificationManagerService and also cancels every
         * child sharing the groupKey — which means the actual per-message
         * notifications (e.g. OpenBubbles NEW_MESSAGE_NOTIFICATION id=1,2,…)
         * disappear from both the launcher's list and the system shade as
         * soon as the summary at id=0 arrives. Confirmed via logcat:
         *   POST id=0 summary → POST id=1 child → DUMB_MUTE skip id=0 →
         *   cancelNotification(summary) → children gone, no further posts.
         * We still filter the summary out of [NotificationStore] above so
         * the launcher's own UI doesn't render it; we just leave the system
         * shade to decide what to do with it.
         *
         * FLAG_AUTOGROUP_SUMMARY is safe — those summaries are synthesized
         * by SystemUI itself and have no app-owned children to cascade to.
         * Blank-title-and-text is safe too — by definition nothing else is
         * attached.
         */
        private val CANCEL_AT_SYSTEM_REASONS = setOf(
            "FLAG_AUTOGROUP_SUMMARY",
            "blank-title-and-text",
        )
    }
}
