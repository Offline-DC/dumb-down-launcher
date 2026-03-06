package com.offlineinc.dumbdownlauncher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent

@SuppressLint("AccessibilityPolicy")
class MouseAccessibilityService : AccessibilityService() {

    private var mouseEnabled = false

    fun forceDisable() {
        mouseEnabled = false
        runMouseCmd("disable")
    }

    companion object {
        fun forceDisable(context: Context) {
            instance?.forceDisable() ?: runMouseCmdStatic("disable")
        }

        private var instance: MouseAccessibilityService? = null
        private var webViewActivityActive = false

        fun notifyWebViewActive(active: Boolean) {
            webViewActivityActive = active
            instance?.let {
                if (active && !it.mouseEnabled) {
                    it.mouseEnabled = true
                    it.runMouseCmd("enable")
                } else if (!active && it.mouseEnabled) {
                    it.mouseEnabled = false
                    it.runMouseCmd("disable")
                }
            } ?: runMouseCmdStatic(if (active) "enable" else "disable")
        }

        fun setMouseEnabled(context: Context, enabled: Boolean) {
            instance?.let {
                if (enabled && !it.mouseEnabled) {
                    it.mouseEnabled = true
                    it.runMouseCmd("enable")
                } else if (!enabled && it.mouseEnabled) {
                    it.mouseEnabled = false
                    it.runMouseCmd("disable")
                }
            } ?: run {
                // Service not running, just run the command directly
                runMouseCmdStatic(if (enabled) "enable" else "disable")
            }
        }

        private fun runMouseCmdStatic(cmd: String) {
            Thread {
                try {
                    ProcessBuilder("su", "-c", "/data/adb/modules/DumbMouse/mouse $cmd")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                    Log.i("MouseService", "static mouse $cmd finished")
                } catch (t: Throwable) {
                    Log.e("MouseService", "static mouse $cmd failed: ${t.message}")
                }
            }.start()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("MouseService", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        if (className == "com.android.mms.ui.ConversationList" || className == "com.android.dialer") {
            Log.d("MouseService", "MMS ConversationList opened, disabling mouse")
            handlePackage(pkg, className)
            return
        }

        if (!className.contains("Activity")) {
            Log.d("MouseService", "Ignoring non-Activity: $pkg ($className)")
            return
        }

        Log.d("MouseService", "Activity event from $pkg mouseEnabled=$mouseEnabled")
        handlePackage(pkg, className)
    }

    private fun handlePackage(pkg: String, className: String = "") {
        if (webViewActivityActive) return

        val openBubblesActive = pkg == "com.openbubbles.messaging"
            || (pkg == "com.offlineinc.dumbdownlauncher" && className == "com.offlineinc.dumbdownlauncher.WebViewActivity")
        if (openBubblesActive && !mouseEnabled) {
            Log.i("MouseService", "Enabling mouse")
            mouseEnabled = true
            runMouseCmd("enable")
        } else if (!openBubblesActive && mouseEnabled) {
            Log.i("MouseService", "Disabling mouse, now in $pkg")
            mouseEnabled = false
            runMouseCmd("disable")
        } else {
            Log.d("MouseService", "No change needed (openBubblesActive=$openBubblesActive mouseEnabled=$mouseEnabled)")
        }
    }

    private fun runMouseCmd(cmd: String) {
        Thread {
            try {
                Log.i("MouseService", "Running: mouse $cmd")
                val proc = ProcessBuilder("su", "-c", "/data/adb/modules/DumbMouse/mouse $cmd")
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                val code = proc.waitFor()
                Log.i("MouseService", "mouse $cmd finished: exit=$code${if (output.isNotEmpty()) " output=$output" else ""}")
            } catch (t: Throwable) {
                Log.e("MouseService", "mouse $cmd threw exception: ${t.message}", t)
            }
        }.start()
    }

    override fun onInterrupt() {
        Log.w("MouseService", "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.w("MouseService", "Accessibility service destroyed")
    }
}