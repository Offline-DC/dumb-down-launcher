package com.offlineinc.dumbdownlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
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

        /**
         * Dedicated executor for text injection so it is never blocked behind
         * slow mouse-enable / density-change shell commands on [shellExecutor].
         */
        private val textInjectorExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        fun forceDisable(context: Context) {
            instance?.forceDisable() ?: runMouseCmdStatic("disable")
        }

        @Volatile var instance: MouseAccessibilityService? = null
            private set

        /** App-level context — set once from TypeSyncService or any Activity so
         *  clipboard + toast always work, even before the a11y service binds. */
        @Volatile var appContext: Context? = null
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
            // Run on a dedicated executor so text injection is never queued
            // behind slow mouse-enable / density-change shell commands.
            textInjectorExecutor.execute {
                Log.d("MouseService", "injectText via blind clipboard paste for ${text.length} chars")
                injectViaClipboardBlind(instance, text)
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

        /** Small delay after setting the clipboard to let the system propagate it. */
        private const val CLIPBOARD_SETTLE_MS = 120L

        /**
         * "Blind" clipboard paste — sets the clipboard and dispatches a paste
         * key-event via shell. Works even when no focused editable node is
         * found by the accessibility service (e.g. the field exists but isn't
         * exposing itself via the a11y tree). If the paste key-event also
         * fails, logs an error — never falls back to `input text` which
         * produces gibberish on non-AOSP keyboards.
         */
        private fun injectViaClipboardBlind(service: MouseAccessibilityService?, text: String) {
            try {
                // 1. Set the clipboard — try ClipboardManager first (needs a Context),
                //    fall back to shell `am broadcast` if no context is available.
                val ctx: Context? = service ?: instance ?: appContext
                if (ctx != null) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ts", text))
                } else {
                    // No context at all — set clipboard via shell (requires root).
                    Log.w("MouseService", "injectViaClipboardBlind: no context, setting clipboard via shell")
                    val escaped = text.replace("'", "'\\''")
                    val clipProc = ProcessBuilder("su", "-c", "am broadcast -a clipper.set -e text '$escaped'")
                        .redirectErrorStream(true)
                        .start()
                    clipProc.inputStream.bufferedReader().readText()
                    clipProc.waitFor()
                }

                // Let the clipboard settle
                try { Thread.sleep(CLIPBOARD_SETTLE_MS) } catch (_: InterruptedException) {}

                // 2. Dispatch KEYCODE_PASTE (279) via shell — this tells the focused
                //    window to paste from the clipboard, even if we can't see the node.
                val proc = ProcessBuilder("su", "-c", "input keyevent 279")
                    .redirectErrorStream(true)
                    .start()
                val exitCode = proc.waitFor()
                if (exitCode != 0) {
                    Log.e("MouseService", "injectViaClipboardBlind: paste keyevent failed with exit code $exitCode")
                    showToast(ctx, "TypeSync: paste failed")
                } else {
                    Log.i("MouseService", "injectViaClipboardBlind: clipboard set + paste keyevent dispatched for ${text.length} chars")
                }
            } catch (t: Throwable) {
                Log.e("MouseService", "injectViaClipboardBlind failed: ${t.message} — text not injected")
                showToast(service ?: instance ?: appContext, "TypeSync: paste failed")
            }
        }

        private val mainHandler = Handler(Looper.getMainLooper())

        private fun showToast(ctx: Context?, message: String) {
            val c = ctx ?: instance ?: appContext ?: return
            mainHandler.post { Toast.makeText(c, message, Toast.LENGTH_SHORT).show() }
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
