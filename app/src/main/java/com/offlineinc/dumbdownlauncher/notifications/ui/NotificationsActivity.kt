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
import com.offlineinc.dumbdownlauncher.notifications.DumbNotificationListenerService
import com.offlineinc.dumbdownlauncher.notifications.NotificationStore

class NotificationsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        // Status bar black + light icons
        window.statusBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

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

            NotificationsScreen(
                items = items,
                onOpen = { item ->
                    try {
                        val pi = item.contentIntent
                        if (pi != null) {
                            pi.send()
                            overridePendingTransition(0, 0)
                            if (item.packageName != packageName) {
                                finish()
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
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
    }
}
