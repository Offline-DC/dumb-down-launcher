package com.offlineinc.dumbdownlauncher.notifications.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.lifecycleScope
import com.offlineinc.dumbdownlauncher.launcher.dnd.DndMuteManager
import com.offlineinc.dumbdownlauncher.notifications.DumbNotificationListenerService
import com.offlineinc.dumbdownlauncher.notifications.NotificationStore

class NotificationsActivity : ComponentActivity() {

    private lateinit var dndMuteManager: DndMuteManager

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        // Status bar black + light icons
        window.statusBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        dndMuteManager = DndMuteManager(
            appContext = applicationContext,
            scope = lifecycleScope,
        )
        dndMuteManager.refreshFromSystem()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        startService(Intent(this, DumbNotificationListenerService::class.java).apply {
            action = DumbNotificationListenerService.ACTION_SEED
        })

        setContent {
            val items by NotificationStore.items().observeAsState(initial = emptyList())
            val muted by dndMuteManager.muted.collectAsState()
            var scrollToKey by remember { mutableStateOf<String?>(null) }
            val scrollToUpdate = intent.getBooleanExtra(EXTRA_SCROLL_TO_UPDATE, false)
            var scrollToUpdateConsumed by remember { mutableStateOf(false) }

            LaunchedEffect(items) {
                if (scrollToUpdate && !scrollToUpdateConsumed && items.isNotEmpty()) {
                    val ownItem = items.firstOrNull { it.packageName == packageName }
                    if (ownItem != null) {
                        scrollToKey = ownItem.key
                        scrollToUpdateConsumed = true
                    }
                }
            }

            NotificationsScreen(
                items = items,
                scrollToKey = scrollToKey,
                onScrollConsumed = { scrollToKey = null },
                onOpen = { item ->
                    try {
                        val pi = item.contentIntent
                        if (pi != null) {
                            pi.send()
                            overridePendingTransition(0, 0)
                            if (item.packageName != packageName) {
                                finish()
                            } else {
                                scrollToKey = item.key
                            }
                        }
                    } catch (_: Exception) {
                        // no toast here to keep minimal; add if you want
                    }
                },
                onDismiss = { item ->
                    startService(Intent(this, DumbNotificationListenerService::class.java).apply {
                        action = DumbNotificationListenerService.ACTION_DISMISS
                        putExtra(DumbNotificationListenerService.EXTRA_KEY, item.key)
                    })
                },
                onClearAll = {
                    startService(Intent(this, DumbNotificationListenerService::class.java).apply {
                        action = DumbNotificationListenerService.ACTION_CLEAR_ALL
                    })
                },
                messagesMuted = muted,
                onToggleMessagesMuted = { enabled ->
                    dndMuteManager.setMuted(enabled)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_SCROLL_TO_UPDATE = "scroll_to_update"
    }
}
