package com.offlineinc.dumbdownlauncher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class NotificationsAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile var instance: NotificationsAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
