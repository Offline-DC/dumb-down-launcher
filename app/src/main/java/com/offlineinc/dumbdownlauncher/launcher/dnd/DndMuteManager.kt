package com.offlineinc.dumbdownlauncher.launcher.dnd

import android.content.Context
import android.content.Intent
import com.offlineinc.dumbdownlauncher.notifications.DumbNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the "mute all texts" preference flag.
 *
 * The actual silencing is done in [DumbNotificationListenerService]
 * via [requestListenerHints(HINT_HOST_DISABLE_NOTIFICATION_EFFECTS)].
 * When muted, the system suppresses sound and vibration for all
 * notifications while keeping them fully visible in the status bar
 * and notification shade.
 *
 * Call ringtones are unaffected — they are played by the telephony /
 * InCallUI via STREAM_RING, completely outside the notification system.
 *
 * This approach does NOT touch AudioManager, Do Not Disturb, ringer
 * mode, or any volume stream — so the phone's normal silent / vibrate /
 * ringer controls stay fully functional for calls and everything else.
 */
class DndMuteManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    // ── public API ──────────────────────────────────────────────────────

    /**
     * Call on startup. Reads saved pref (default = true / muted) and
     * publishes to both [muted] flow and [MuteState] singleton, then
     * tells the listener service to apply the hint.
     */
    fun refreshFromSystem() {
        scope.launch {
            val wantMuted = prefs.getBoolean(KEY_MESSAGES_MUTED, true)
            _muted.value = wantMuted
            MuteState.muted = wantMuted
            notifyService()
        }
    }

    /**
     * Toggle mute on / off. Persists immediately and tells the
     * listener service to update its hint.
     */
    fun setMuted(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MESSAGES_MUTED, enabled).apply()
        _muted.value = enabled
        MuteState.muted = enabled
        notifyService()
    }

    // ── internals ───────────────────────────────────────────────────────

    private fun notifyService() {
        try {
            val intent = Intent(appContext, DumbNotificationListenerService::class.java).apply {
                action = DumbNotificationListenerService.ACTION_UPDATE_MUTE
            }
            appContext.startService(intent)
        } catch (_: Exception) {
            // Service may not be bound yet — it will pick up MuteState
            // in onListenerConnected().
        }
    }

    companion object {
        private const val PREFS_NAME = "launcher_prefs"
        private const val KEY_MESSAGES_MUTED = "messages_muted"
    }
}

/**
 * Simple shared flag so the [DumbNotificationListenerService] can
 * read the mute state without needing a Context lookup on every
 * notification. Updated from [DndMuteManager.setMuted] and on startup.
 */
object MuteState {
    @Volatile
    var muted: Boolean = false
}
