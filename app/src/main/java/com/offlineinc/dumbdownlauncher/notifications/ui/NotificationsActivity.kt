package com.offlineinc.dumbdownlauncher.notifications.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.offlineinc.dumbdownlauncher.R
import com.offlineinc.dumbdownlauncher.notifications.DumbNotificationListenerService
import com.offlineinc.dumbdownlauncher.notifications.NotificationStore
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem

class NotificationsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: NotificationsAdapter
    private lateinit var clearAllView: TextView
    private lateinit var emptyStateView: TextView

    private var hasNotifications: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)

        // Status bar black + light icons
        window.statusBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        // ✅ Proper back handling (fixes lint warning)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If you want special behavior when empty, keep it here.
                // Right now: just exit.
                finish()
            }
        })

        clearAllView = findViewById(R.id.clearAllButton)
        emptyStateView = findViewById(R.id.emptyState)

        recyclerView = findViewById(R.id.notificationsList)
        layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager
        recyclerView.itemAnimator = null

        // Don’t auto-focus the list; we start on Clear All (when visible)
        recyclerView.isFocusable = false
        recyclerView.isFocusableInTouchMode = false

        adapter = NotificationsAdapter(
            onClick = { openNotification(it) },
            onLongPress = { dismissOne(it) }
        )
        recyclerView.adapter = adapter

        // Click to clear all
        clearAllView.setOnClickListener { clearAll() }

        // Yellow highlight for Clear All when focused (match launcher)
        clearAllView.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundColor(if (hasFocus) 0xFFFFD400.toInt() else 0x00000000)
            (v as? TextView)?.setTextColor(
                if (hasFocus) 0xFF000000.toInt() else 0xFFFFD400.toInt()
            )

            if (hasFocus) {
                // While Clear All is focused, NEVER highlight a notification row.
                adapter.clearSelectionHighlight()
            }
        }

        // Ask service to seed (in case Activity opened before listener connected)
        startService(Intent(this, DumbNotificationListenerService::class.java).apply {
            action = DumbNotificationListenerService.ACTION_SEED
        })

        // Live updates
        NotificationStore.items().observe(this, Observer { list ->
            adapter.submit(list)
            updateEmptyState(list.isNotEmpty())
            if (list.isNotEmpty()) {
                // Default state: focus Clear All, no row highlighted
                clearAllView.post {
                    clearAllView.requestFocus()
                    adapter.clearSelectionHighlight()
                }
            }

        })
    }

    private fun updateEmptyState(hasAny: Boolean) {
        hasNotifications = hasAny

        if (!hasAny) {
            clearAllView.visibility = View.GONE
            emptyStateView.visibility = View.VISIBLE

            adapter.clearSelectionHighlight()

            emptyStateView.isFocusable = true
            emptyStateView.isFocusableInTouchMode = true
            emptyStateView.post { emptyStateView.requestFocus() }
        } else {
            emptyStateView.visibility = View.GONE
            clearAllView.visibility = View.VISIBLE
            clearAllView.post { clearAllView.requestFocus() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val focusId = currentFocus?.id

        // If empty: ignore list navigation
        if (!hasNotifications) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> true
                else -> super.dispatchKeyEvent(event) // Back handled by dispatcher
            }
        }

        // When Clear All is focused:
        if (focusId == R.id.clearAllButton) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Turn on selection highlight and start at first item
                    adapter.setSelection(0)
                    adapter.restoreSelectionHighlight()

                    // Move actual focus into list
                    recyclerView.isFocusable = true
                    recyclerView.isFocusableInTouchMode = true
                    recyclerView.requestFocus()
                    recyclerView.isFocusable = false
                    recyclerView.isFocusableInTouchMode = false

                    recyclerView.scrollToPosition(adapter.getSelectedIndex())
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    clearAllView.performClick()
                    true
                }
                else -> super.dispatchKeyEvent(event)
            }
        }

        // Otherwise: navigating notifications list with our own selection index
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (adapter.itemCount > 0 && adapter.moveSelection(1)) {
                    recyclerView.scrollToPosition(adapter.getSelectedIndex())
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // ✅ Restore old behavior: if at top item, go to Clear All
                if (adapter.getSelectedIndex() == 0) {
                    adapter.clearSelectionHighlight()
                    clearAllView.requestFocus()
                    true
                } else {
                    if (adapter.itemCount > 0 && adapter.moveSelection(-1)) {
                        recyclerView.scrollToPosition(adapter.getSelectedIndex())
                    }
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                adapter.activateSelected()
                true
            }
            KeyEvent.KEYCODE_DEL -> {
                adapter.longPressSelected()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun openNotification(item: NotificationItem) {
        try {
            val pi = item.contentIntent
            if (pi != null) {
                pi.send()
                overridePendingTransition(0, 0)
                finish()
            } else {
                Toast.makeText(this, "Can't open this notification.", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't open notification.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismissOne(item: NotificationItem) {
        startService(Intent(this, DumbNotificationListenerService::class.java).apply {
            action = DumbNotificationListenerService.ACTION_DISMISS
            putExtra(DumbNotificationListenerService.EXTRA_KEY, item.key)
        })
    }

    private fun clearAll() {
        startService(Intent(this, DumbNotificationListenerService::class.java).apply {
            action = DumbNotificationListenerService.ACTION_CLEAR_ALL
        })
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
    }
}
