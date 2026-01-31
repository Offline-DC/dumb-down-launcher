// NotificationsActivity.kt
package com.offlineinc.dumbdownlauncher.notifications.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)

        clearAllView = findViewById(R.id.clearAllButton)

        recyclerView = findViewById(R.id.notificationsList)
        layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager
        recyclerView.itemAnimator = null

        // Don’t auto-focus the list; we start on Clear All
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
                adapter.clearSelectionHighlight()
            } else {
                adapter.restoreSelectionHighlight()
            }
        }

        // Start focused on Clear All
        clearAllView.post { clearAllView.requestFocus() }

        // Ask service to seed (in case Activity opened before listener connected)
        startService(Intent(this, DumbNotificationListenerService::class.java).apply {
            action = DumbNotificationListenerService.ACTION_SEED
        })

        // Live updates
        NotificationStore.items().observe(this, Observer { list ->
            adapter.submit(list)
            // keep focus on Clear All after refresh
            clearAllView.post { clearAllView.requestFocus() }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val focusId = currentFocus?.id

        // When Clear All is focused:
        if (focusId == R.id.clearAllButton) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Move into list
                    recyclerView.isFocusable = true
                    recyclerView.isFocusableInTouchMode = true
                    recyclerView.requestFocus()
                    recyclerView.isFocusable = false
                    recyclerView.isFocusableInTouchMode = false
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

        // Otherwise: we’re navigating notifications list with our own selection index
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                adapter.moveSelection(1)
                recyclerView.scrollToPosition(adapter.getSelectedIndex())
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (adapter.getSelectedIndex() == 0) {
                    clearAllView.requestFocus()
                    true
                } else {
                    adapter.moveSelection(-1)
                    recyclerView.scrollToPosition(adapter.getSelectedIndex())
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                adapter.activateSelected()
                true
            }
            // Optional: DEL/Backspace to dismiss one notification
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
