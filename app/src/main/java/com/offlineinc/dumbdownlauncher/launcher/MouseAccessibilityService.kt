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
    private var currentDensity = -1

    // True while the star-key special-char picker is open.
    // The mouse is disabled for this duration.
    private var specialCharPickerOpen = false

    fun forceDisable() {
        mouseEnabled = false
        runMouseCmd("disable")
    }

    companion object {
        /** Shared single-thread executor for all shell commands — avoids raw Thread{} churn. */
        private val shellExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        fun forceDisable(context: Context) {
            instance?.forceDisable() ?: runMouseCmdStatic("disable")
        }

        @Volatile var instance: MouseAccessibilityService? = null
            private set
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

        /**
         * Maximum time (ms) to wait for the accessibility service to connect
         * before giving up. After a fresh boot, Android can take a few seconds
         * to bind the service — we poll in short intervals rather than
         * immediately falling back to the broken shell path.
         */
        private const val A11Y_WAIT_TIMEOUT_MS = 3000L
        private const val A11Y_POLL_INTERVAL_MS = 150L

        /**
         * Maximum number of attempts to find a focused editable node before
         * falling back. Covers the common case where the text field hasn't
         * received focus yet right after a screen transition.
         */
        private const val FIND_FOCUS_MAX_RETRIES = 5
        private const val FIND_FOCUS_RETRY_MS = 100L

        fun injectText(text: String) {
            // Run on the shared executor so polling doesn't block the caller.
            shellExecutor.execute {
                val service = waitForService()
                if (service == null) {
                    Log.e("MouseService", "injectText: accessibility service never connected after ${A11Y_WAIT_TIMEOUT_MS}ms — falling back to shell")
                    injectTextViaShell(text)
                    return@execute
                }

                // Retry loop: the focused node might not be ready immediately
                // (e.g. after a screen transition or when the keyboard is appearing).
                var focused: AccessibilityNodeInfo? = null
                for (attempt in 1..FIND_FOCUS_MAX_RETRIES) {
                    val root = service.rootInActiveWindow
                    if (root != null) {
                        focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (focused != null && focused.isEditable) break
                        focused = null
                    }
                    if (attempt < FIND_FOCUS_MAX_RETRIES) {
                        Log.d("MouseService", "injectText: no focused editable node (attempt $attempt/$FIND_FOCUS_MAX_RETRIES), retrying...")
                        try { Thread.sleep(FIND_FOCUS_RETRY_MS) } catch (_: InterruptedException) { break }
                    }
                }

                if (focused != null) {
                    Log.d("MouseService", "injectText via clipboard paste for: \"$text\"")
                    injectViaClipboard(service, focused, text)
                } else {
                    Log.w("MouseService", "injectText: no focused editable node after $FIND_FOCUS_MAX_RETRIES attempts, using shell")
                    injectTextViaShell(text)
                }
            }
        }

        private const val A11Y_SERVICE_ID = "com.offlineinc.dumbdownlauncher/com.offlineinc.dumbdownlauncher.MouseAccessibilityService"

        /**
         * Force-enable the accessibility service via root `settings` command.
         * Android will bind it shortly after the secure setting is updated.
         * Call this proactively (e.g. when TypeSyncService starts) so the
         * service is bound before any text injection is needed.
         */
        fun ensureAccessibilityEnabled() {
            try {
                // Read current enabled services
                val readProc = ProcessBuilder("su", "-c", "settings get secure enabled_accessibility_services")
                    .redirectErrorStream(true)
                    .start()
                val current = readProc.inputStream.bufferedReader().readText().trim()
                readProc.waitFor()

                if (current.contains(A11Y_SERVICE_ID)) return // already listed

                // Append our service to the list
                val newValue = if (current.isBlank() || current == "null") {
                    A11Y_SERVICE_ID
                } else {
                    "$current:$A11Y_SERVICE_ID"
                }
                val writeProc = ProcessBuilder("su", "-c", "settings put secure enabled_accessibility_services '$newValue'")
                    .redirectErrorStream(true)
                    .start()
                writeProc.inputStream.bufferedReader().readText()
                writeProc.waitFor()

                // Make sure accessibility master toggle is on
                val toggleProc = ProcessBuilder("su", "-c", "settings put secure accessibility_enabled 1")
                    .redirectErrorStream(true)
                    .start()
                toggleProc.inputStream.bufferedReader().readText()
                toggleProc.waitFor()

                Log.i("MouseService", "ensureAccessibilityEnabled: force-enabled via root (was: '$current')")
            } catch (t: Throwable) {
                Log.w("MouseService", "ensureAccessibilityEnabled: failed — ${t.message}")
            }
        }

        /**
         * Polls for the accessibility service instance, waiting up to
         * [A11Y_WAIT_TIMEOUT_MS]. If instance is null on first check,
         * force-enables the service via root to kick Android into binding it.
         */
        private fun waitForService(): MouseAccessibilityService? {
            instance?.let { return it }

            // Service not bound — force-enable via root so Android binds it
            ensureAccessibilityEnabled()

            val deadline = System.currentTimeMillis() + A11Y_WAIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                instance?.let { return it }
                try { Thread.sleep(A11Y_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
            return instance
        }

        /** Max attempts for the paste action itself. */
        private const val PASTE_MAX_RETRIES = 3
        private const val PASTE_RETRY_MS = 50L
        /** Small delay after setting the clipboard to let the system propagate it. */
        private const val CLIPBOARD_SETTLE_MS = 30L

        /**
         * Set [text] via clipboard paste. Selects all existing content first so the
         * paste fully replaces the field. Bypasses the IME entirely — @, numbers, and
         * all special characters arrive verbatim regardless of what keyboard is active.
         *
         * If clipboard paste fails after retries, falls back to ACTION_SET_TEXT
         * (works on most EditText views). Shell is the absolute last resort.
         */
        private fun injectViaClipboard(
            service: MouseAccessibilityService,
            node: AccessibilityNodeInfo,
            text: String
        ) {
            try {
                val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ts", text))

                // Let the clipboard settle — avoids a race where paste grabs stale content.
                try { Thread.sleep(CLIPBOARD_SETTLE_MS) } catch (_: InterruptedException) {}

                // Select all existing content (0 → end) so the paste fully replaces it
                val len = node.text?.length ?: 0
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)

                // Retry paste a few times — it can fail transiently if the node is
                // mid-layout or the clipboard manager hasn't finished propagating.
                var pasted = false
                for (attempt in 1..PASTE_MAX_RETRIES) {
                    pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    if (pasted) break
                    Log.d("MouseService", "clipboard paste attempt $attempt/$PASTE_MAX_RETRIES failed, retrying...")
                    if (attempt < PASTE_MAX_RETRIES) {
                        try { Thread.sleep(PASTE_RETRY_MS) } catch (_: InterruptedException) { break }
                    }
                }

                if (pasted) {
                    Log.d("MouseService", "injectText via clipboard paste: success")
                    return
                }

                // Fallback: ACTION_SET_TEXT works on standard EditText views and
                // doesn't require clipboard support. It replaces the entire field.
                Log.w("MouseService", "clipboard paste failed after $PASTE_MAX_RETRIES attempts, trying ACTION_SET_TEXT")
                val setTextArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val setText = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
                if (setText) {
                    Log.d("MouseService", "injectText via ACTION_SET_TEXT: success")
                    return
                }

                Log.w("MouseService", "ACTION_SET_TEXT also failed, falling back to shell")
                injectTextViaShell(text)
            } catch (t: Throwable) {
                Log.e("MouseService", "injectViaClipboard failed: ${t.message}")
                injectTextViaShell(text)
            }
        }

        /**
         * Last-resort shell injection. Handles only simple ASCII reliably.
         * Runs the command inline — callers are already on a background thread/executor.
         */
        private fun injectTextViaShell(text: String) {
            val escaped = text.replace("'", "'\\''")
            try {
                ProcessBuilder("su", "-c", "input text '$escaped'")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                Log.i("MouseService", "shell injectText finished")
            } catch (t: Throwable) {
                Log.e("MouseService", "shell injectText failed: ${t.message}")
            }
        }

        /**
         * Queries the FlipMouse daemon for current mouse state.
         * Returns true if the physical mouse cursor is enabled.
         * Runs a shell command — call from a background thread / IO dispatcher.
         */
        fun queryMouseEnabled(): Boolean {
            return try {
                val proc = ProcessBuilder("su", "-c", "/data/adb/modules/DumbMouse/mouse status")
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                output.contains("enabled=1")
            } catch (_: Throwable) {
                false
            }
        }

        private fun runMouseCmdStatic(cmd: String) {
            shellExecutor.execute {
                try {
                    val proc = ProcessBuilder("su", "-c", "/data/adb/modules/DumbMouse/mouse $cmd")
                        .redirectErrorStream(true)
                        .start()
                    proc.inputStream.bufferedReader().readText()
                    proc.waitFor()
                } catch (_: Throwable) {}
            }
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

        // Density switching: only act when WhatsApp is foreground, or we need to reset
        if (!webViewActivityActive) {
            if (pkg == "com.whatsapp") {
                handleWhatsAppDensity()
            } else if (currentDensity != 120) {
                setDensity(120)
            }
        }
    }

    private fun handleWhatsAppDensity() {
        shellExecutor.execute {
            try {
                val proc = ProcessBuilder("su", "-mm", "-c", "test -f /data/user/0/com.whatsapp/files/me && echo yes || echo no")
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor()
                val loggedIn = output.lines().any { it.trim() == "yes" }
                setDensity(if (loggedIn) 120 else 100)
            } catch (t: Throwable) {
                Log.e("MOUSE_SVC", "handleWhatsAppDensity failed: ${t.message}")
            }
        }
    }

    private fun setDensity(density: Int) {
        if (currentDensity == density) return
        currentDensity = density
        shellExecutor.execute {
            try {
                val proc = ProcessBuilder("su", "-c", "wm density $density")
                    .redirectErrorStream(true)
                    .start()
                proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                Log.d("MOUSE_SVC", "setDensity $density done")
            } catch (t: Throwable) {
                Log.e("MOUSE_SVC", "setDensity failed: ${t.message}")
            }
        }
    }

    private fun runMouseCmd(cmd: String) {
        shellExecutor.execute {
            try {
                val proc = ProcessBuilder("su", "-c", "/data/adb/modules/DumbMouse/mouse $cmd")
                    .redirectErrorStream(true)
                    .start()
                proc.inputStream.bufferedReader().readText()
                proc.waitFor()
            } catch (_: Throwable) {}
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
