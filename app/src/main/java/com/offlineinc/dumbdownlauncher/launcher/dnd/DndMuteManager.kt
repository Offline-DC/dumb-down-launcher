package com.offlineinc.dumbdownlauncher.launcher.dnd

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DndMuteManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private val nm: NotificationManager by lazy {
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _policyGranted = MutableStateFlow(false)
    val policyGranted: StateFlow<Boolean> = _policyGranted.asStateFlow()

    /**
     * Call on startup to sync state from prefs and apply to system if needed.
     * Defaults to muted=true if pref has never been set (fresh install).
     */
    fun refreshFromSystem() {
        scope.launch {
            val granted = hasPolicyAccess()
            _policyGranted.value = granted

            val prefs = appContext.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
            val savedMuted = prefs.getBoolean("messages_muted", true) // default true

            if (granted) {
                if (savedMuted) {
                    applySystem(true)
                    _muted.value = true
                } else {
                    _muted.value = isDndOn()
                }
            } else {
                _muted.value = savedMuted
            }
        }
    }

    /**
     * Attempt to set DND to "muted" (true) or "not muted" (false).
     */
    fun setMuted(enabled: Boolean) {
        _muted.value = enabled

        scope.launch {
            mutex.withLock {
                val granted = hasPolicyAccess()
                _policyGranted.value = granted

                if (!granted) {
                    _muted.value = false
                    return@withLock
                }

                applySystem(enabled)

                delay(80)

                val real = isDndOn()
                _muted.value = real
            }
        }
    }

    fun makePolicyAccessIntent(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun hasPolicyAccess(): Boolean {
        return nm.isNotificationPolicyAccessGranted
    }

    private suspend fun applySystem(enabled: Boolean) {
        withContext(Dispatchers.Main) {
            try {
                if (enabled) {
                    applyMuteSystemDirect(appContext)
                } else {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    restoreFullPolicy()
                }
            } catch (t: Throwable) {
                Log.e("DUMB_DND", "Failed to set DND", t)
            }
        }
    }

    private fun restoreFullPolicy() {
        val categories =
            NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
                    NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or
                    NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
                    NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA or
                    NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES or
                    NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS

        val policy = NotificationManager.Policy(
            categories,
            NotificationManager.Policy.PRIORITY_SENDERS_ANY,
            NotificationManager.Policy.PRIORITY_SENDERS_ANY
        )

        nm.notificationPolicy = policy
    }

    private fun isDndOn(): Boolean {
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    companion object {
        fun applyMuteSystemDirect(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) return

            val categories =
                NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
                        NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or
                        NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
                        NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA

            val policy = NotificationManager.Policy(
                categories,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY
            )
            nm.notificationPolicy = policy
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
    }
}
