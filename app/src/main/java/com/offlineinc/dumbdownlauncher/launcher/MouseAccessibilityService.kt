package com.offlineinc.dumbdownlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

@SuppressLint("AccessibilityPolicy")
class MouseAccessibilityService : AccessibilityService() {

    private var mouseEnabled = false
    private var currentPackage = ""

    // True while the star-key special-char picker is open.
    // The mouse is disabled for this duration.
    private var specialCharPickerOpen = false

    fun forceDisable() {
        mouseEnabled = false
        runMouseCmd("disable")
    }

    companion object {
        fun forceDisable(context: Context) {
            instance?.forceDisable() ?: runMouseCmdStatic("disable")
        }

        @Volatile private var instance: MouseAccessibilityService? = null
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
                runMouseCmdStatic(if (enabled) "enable" else "disable")
            }
        }

        fun injectText(text: String) {
            val service = instance ?: run {
                Log.w("MouseService", "injectText: service not connected, falling back to shell")
                injectTextViaShell(text)
                return
            }

            val root = service.rootInActiveWindow
            if (root == null) {
                Log.w("MouseService", "injectText: rootInActiveWindow null, using shell")
                injectTextViaShell(text)
                return
            }

            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                Log.d("MouseService", "injectText via clipboard paste for: \"$text\"")
                injectViaClipboard(service, focused, text)
            } else {
                Log.w("MouseService", "injectText: no focused editable node, using shell")
                injectTextViaShell(text)
            }
        }

        /**
         * Set [text] via clipboard paste. Selects all existing content first so the
         * paste fully replaces the field. Bypasses the IME entirely — @, numbers, and
         * all special characters arrive verbatim regardless of what keyboard is active.
         */
        private fun injectViaClipboard(
            service: MouseAccessibilityService,
            node: AccessibilityNodeInfo,
            text: String
        ) {
            try {
                val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ts", text))

                // Select all existing content (0 → end) so the paste fully replaces it
                val len = node.text?.length ?: 0
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)

                val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d("MouseService", "injectText via clipboard paste: success=$pasted")
                if (!pasted) {
                    Log.w("MouseService", "clipboard paste failed, falling back to shell")
                    injectTextViaShell(text)
                }
            } catch (t: Throwable) {
                Log.e("MouseService", "injectViaClipboard failed: ${t.message}")
                injectTextViaShell(text)
            }
        }

        /** Last-resort shell injection. Handles only simple ASCII reliably. */
        private fun injectTextViaShell(text: String) {
            val escaped = text.replace("'", "'\\''")
            Thread {
                try {
                    ProcessBuilder("su", "-c", "input text '$escaped'")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                    Log.i("MouseService", "shell injectText finished")
                } catch (t: Throwable) {
                    Log.e("MouseService", "shell injectText failed: ${t.message}")
                }
            }.start()
        }

        private fun runMouseCmdStatic(cmd: String) {
            Thread {
                try {
                    val proc = ProcessBuilder("su", "-c", "/data/adb/modules/DumbMouse/mouse $cmd")
                        .redirectErrorStream(true)
                        .start()
                    proc.inputStream.bufferedReader().readText()
                    proc.waitFor()
                } catch (t: Throwable) {}
            }.start()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("MouseService", "✅ Accessibility service connected — text injection ready")
        // Enable key-event interception so onKeyEvent fires for all apps,
        // not just when the launcher is focused.
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info

        // Sync mouseEnabled with reality: the physical mouse may already be on
        // if a target app was on screen when the service restarted (e.g. after
        // a reinstall) and was enabled via the static path.
        if (webViewActivityActive || isCurrentlyInTargetApp()) {
            mouseEnabled = true
        }
    }

    private fun isTargetApp(pkg: String): Boolean =
        pkg == "com.openbubbles.messaging"
            || pkg == "com.android.chrome"
            || pkg == "org.chromium.chrome"
            || pkg == "com.google.android.apps.mapslite"
            || pkg == "com.ubercab.uberlite"

    // Returns true if a target-app window is currently on screen.
    // Used to sync mouseEnabled on service (re)connect.
    private fun isCurrentlyInTargetApp(): Boolean {
        return try {
            windows.any { w ->
                val root: AccessibilityNodeInfo? = w.root
                val pkg = root?.packageName?.toString()
                root?.recycle()
                pkg != null && isTargetApp(pkg)
            }
        } catch (t: Throwable) {
            false
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_STAR -> {
                val isTarget = isTargetApp(currentPackage)
                Log.d("MOUSE_SVC", "STAR pressed: currentPackage=$currentPackage isTarget=$isTarget pickerOpen=$specialCharPickerOpen")
                if (!specialCharPickerOpen
                    && isTarget
                ) {
                    specialCharPickerOpen = true
                    mouseEnabled = false
                    runMouseCmd("disable")
                    Log.d("MOUSE_SVC", "STAR: disabling mouse, picker opened")
                }
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Navigating within picker — keep mouse off.
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (specialCharPickerOpen) {
                    specialCharPickerOpen = false
                    mouseEnabled = true
                    runMouseCmd("enable")
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (specialCharPickerOpen) {
                    specialCharPickerOpen = false
                    mouseEnabled = true
                    runMouseCmd("enable")
                }
            }
            else -> { }
        }

        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d("MOUSE_SVC", "WINDOW_STATE_CHANGED: pkg=$pkg className=$className")
            if (className == "com.android.mms.ui.ConversationList" || className == "com.android.dialer") {
                handlePackage(pkg, className)
                return
            }
            if (!className.contains("Activity")) {
                Log.d("MOUSE_SVC", "WINDOW_STATE_CHANGED: skipped (no 'Activity' in className)")
                return
            }
            handlePackage(pkg, className)
        }
    }

    private fun handlePackage(pkg: String, className: String = "") {
        if (webViewActivityActive) {
            Log.d("MOUSE_SVC", "handlePackage: skipped (webViewActivityActive)")
            return
        }

        val openBubblesActive = isTargetApp(pkg)
            || (pkg == "com.offlineinc.dumbdownlauncher" && className == "com.offlineinc.dumbdownlauncher.WebViewActivity")
        currentPackage = if (openBubblesActive) pkg else ""
        Log.d("MOUSE_SVC", "handlePackage: pkg=$pkg openBubblesActive=$openBubblesActive currentPackage=$currentPackage mouseEnabled=$mouseEnabled")
        if (openBubblesActive && !mouseEnabled) {
            mouseEnabled = true
            runMouseCmd("enable")
            Log.d("MOUSE_SVC", "handlePackage: enabled mouse")
        } else if (!openBubblesActive && mouseEnabled) {
            mouseEnabled = false
            runMouseCmd("disable")
            Log.d("MOUSE_SVC", "handlePackage: disabled mouse")
        }
    }

    private fun runMouseCmd(cmd: String) {
        Thread {
            try {
                val proc = ProcessBuilder("su", "-c", "/data/adb/modules/DumbMouse/mouse $cmd")
                    .redirectErrorStream(true)
                    .start()
                proc.inputStream.bufferedReader().readText()
                proc.waitFor()
            } catch (t: Throwable) {}
        }.start()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
