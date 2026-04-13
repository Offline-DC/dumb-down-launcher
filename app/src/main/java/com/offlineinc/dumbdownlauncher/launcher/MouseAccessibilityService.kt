package com.offlineinc.dumbdownlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.offlineinc.dumbdownlauncher.typesync.DeviceLinkReader
import com.offlineinc.dumbdownlauncher.typesync.TypeSyncCrypto
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

        /* ── Watchdog: periodically verifies the service is still bound ──────── */

        private val watchdogHandler = Handler(Looper.getMainLooper())
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        private val watchdogRunnable = object : Runnable {
            override fun run() {
                if (instance == null) {
                    Log.w("MouseService", "watchdog: instance null — re-enabling")
                    shellExecutor.execute { ensureAccessibilityEnabled() }
                }
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }

        fun startWatchdog() {
            watchdogHandler.removeCallbacks(watchdogRunnable)
            watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        }

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

        /**
         * Returns true if OpenBubbles needs mouse support, i.e. its version code
         * is below 20002221 (newer builds have native touch handling).
         */
        fun isOpenBubblesMouseNeeded(context: Context): Boolean {
            // TODO: restore version check logic once no longer needed unconditionally
            return true
//            return try {
//                val info = context.packageManager.getPackageInfo("com.openbubbles.messaging", 0)
//                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//                    info.longVersionCode
//                } else {
//                    @Suppress("DEPRECATION")
//                    info.versionCode.toLong()
//                }
//                versionCode < 20002221
//            } catch (e: PackageManager.NameNotFoundException) {
//                false
//            }
        }

        // ── Type Sync WebSocket relay ────────────────────────────────────
        // Lives here instead of a foreground service so there is no
        // persistent notification.  The AccessibilityService process is
        // already kept alive by Android.

        private const val RELAY_TAG = "TypeSyncRelay"
        private const val WS_URL   =
            "wss://offline-dc-backend-ba4815b2bcc8.herokuapp.com/keyboard/ws"

        @Volatile var relayRunning = false
            private set

        private val relayHandler = Handler(Looper.getMainLooper())
        private var relayWebSocket: WebSocket? = null
        private var relaySecret: String? = null
        private var relayPhone: String? = null
        private var relayReconnectCount = 0

        private val relayClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        private const val RELAY_A11Y_WAIT_MS = 5000L

        /**
         * Start (or restart) the encrypted WebSocket relay.
         * Safe to call multiple times — tears down the old socket first.
         */
        fun startRelay(context: Context, phoneNumber: String?) {
            val pairing = DeviceLinkReader.readPairing(context)
            if (pairing == null) {
                Log.e(RELAY_TAG, "startRelay — no pairing found, cannot start")
                return
            }
            val phone = pairing.flipPhoneNumber.ifBlank { phoneNumber.orEmpty() }
            if (phone.isBlank()) {
                Log.e(RELAY_TAG, "startRelay — no phone number available")
                return
            }

            // Tear down any existing connection (e.g. re-pairing)
            if (relayRunning) {
                Log.i(RELAY_TAG, "startRelay: already running — restarting with fresh credentials")
                relayHandler.removeCallbacksAndMessages(null)
                relayWebSocket?.close(1000, "credential refresh")
                relayWebSocket = null
                relayReconnectCount = 0
            }

            relaySecret = pairing.sharedSecret
            appContext = context.applicationContext
            ensureAccessibilityEnabled()

            // Wait for a11y service on a background thread, then open the WS
            Thread {
                val deadline = System.currentTimeMillis() + RELAY_A11Y_WAIT_MS
                while (instance == null && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                }
                if (instance != null) {
                    Log.i(RELAY_TAG, "Accessibility service ready — starting relay")
                } else {
                    Log.w(RELAY_TAG, "Accessibility service not ready — starting relay anyway")
                }
                relayHandler.post { openRelaySocket(phone) }
            }.start()
        }

        fun stopRelay() {
            relayHandler.removeCallbacksAndMessages(null)
            try {
                relayWebSocket?.send(JSONObject().apply {
                    put("type", "disconnect")
                    put("role", "phone")
                }.toString())
            } catch (_: Exception) {}
            relayWebSocket?.close(1000, "relay stopped")
            relayWebSocket = null
            relayRunning = false
        }

        private fun openRelaySocket(phoneNumber: String) {
            relayRunning = true
            relayPhone = phoneNumber
            val secret = relaySecret ?: return

            Log.i(RELAY_TAG, "━━━ openRelaySocket ━━━  phone=$phoneNumber  attempt=$relayReconnectCount")

            val request = Request.Builder().url(WS_URL).build()
            relayWebSocket = relayClient.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(ws: WebSocket, response: Response) {
                    relayReconnectCount = 0
                    val e164 = if (phoneNumber.startsWith("+")) phoneNumber else "+$phoneNumber"
                    Log.i(RELAY_TAG, "✅ WS open (HTTP ${response.code}) — handshake for $e164")

                    val timestamp = (System.currentTimeMillis() / 1000).toString()
                    val hmac = TypeSyncCrypto.hmacSha256Hex("$e164$timestamp".toByteArray(), secret)
                    val handshake = JSONObject().apply {
                        put("type",        "connect")
                        put("role",        "phone")
                        put("phoneNumber", e164)
                        put("timestamp",   timestamp)
                        put("hmac",        hmac)
                    }
                    ws.send(handshake.toString())
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(RELAY_TAG, "← $text")
                    try {
                        val msg = JSONObject(text)
                        when (msg.getString("type")) {
                            "text" -> handleRelayText(msg)
                            "auth_failed" -> {
                                Log.e(RELAY_TAG, "❌ Auth failed: ${msg.optString("reason")}")
                                stopRelay()
                            }
                            "companion_connected" -> Log.i(RELAY_TAG, "📱 Companion connected: ${msg.optString("role")}")
                            "companion_disconnected" -> Log.i(RELAY_TAG, "📵 Companion disconnected")
                            else -> Log.d(RELAY_TAG, "ignoring type=${msg.getString("type")}")
                        }
                    } catch (e: Exception) {
                        Log.w(RELAY_TAG, "parse failed: ${e.message}")
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(RELAY_TAG, "❌ WS failure: ${t.message}")
                    relayReconnectCount++
                    if (relayRunning) {
                        Log.i(RELAY_TAG, "🔄 reconnecting in 3 s (attempt $relayReconnectCount)")
                        relayHandler.postDelayed({ openRelaySocket(phoneNumber) }, 3_000)
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.i(RELAY_TAG, "🔌 WS closed: code=$code reason=\"$reason\"")
                }
            })
        }

        private fun handleRelayText(msg: JSONObject) {
            val secret = relaySecret ?: return
            if (!msg.has("encrypted") || !msg.has("iv")) return
            try {
                val ciphertext = TypeSyncCrypto.fromBase64(msg.getString("encrypted"))
                val iv = TypeSyncCrypto.fromBase64(msg.getString("iv"))
                val plaintext = TypeSyncCrypto.decryptAesGcm(ciphertext, iv, secret)
                val text = String(plaintext, Charsets.UTF_8)
                Log.i(RELAY_TAG, "🔓 decrypted ${text.length} chars")
                injectText(text)
            } catch (e: Exception) {
                Log.e(RELAY_TAG, "❌ Decryption failed", e)
            }
        }

        // ── end relay ────────────────────────────────────────────────────────

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

                // If the a11y service isn't bound yet, try to force-enable it and
                // wait up to 1.5 s for Android to bind it before we fall back to
                // the shell keyevent path (which doesn't work in all apps).
                if (instance == null) {
                    Log.w("MouseService", "injectText: a11y instance null — calling ensureAccessibilityEnabled and waiting for bind")
                    ensureAccessibilityEnabled()
                    val deadline = System.currentTimeMillis() + 5000L
                    while (instance == null && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                    }
                    if (instance != null) {
                        Log.i("MouseService", "injectText: a11y service bound after wait — proceeding with ACTION_PASTE")
                    } else {
                        Log.w("MouseService", "injectText: a11y service still null after 5 s — keyevent fallback will be used")
                    }
                }

                injectViaClipboardBlind(instance, text)
            }
        }

        private const val A11Y_SERVICE_ID = "com.offlineinc.dumbdownlauncher/com.offlineinc.dumbdownlauncher.MouseAccessibilityService"

        /** Guard to prevent concurrent/re-entrant calls to [ensureAccessibilityEnabled]. */
        private val enabling = AtomicBoolean(false)

        /**
         * Timestamp (uptimeMillis) of the last settings toggle inside
         * [ensureAccessibilityEnabled].  External listeners (e.g. the
         * AccessibilityStateChangeListener in DumbDownApp) should ignore
         * state-change callbacks that arrive within a short window of this
         * timestamp — those are side-effects of our own toggle, not the
         * system killing the service.
         */
        @Volatile var lastToggleTimestamp: Long = 0L
            private set

        /** How long to wait for Android to bind the service on cold start (ms). */
        private const val INITIAL_BIND_GRACE_MS = 3000L

        /**
         * Waits for the accessibility service to bind naturally.
         * Registration is handled externally (Magisk module) — this just
         * blocks briefly so callers don't have to poll themselves.
         */
        fun ensureAccessibilityEnabled() {
            if (instance != null) return
            if (!enabling.compareAndSet(false, true)) return
            try {
                val deadline = System.currentTimeMillis() + INITIAL_BIND_GRACE_MS
                while (instance == null && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                }
                if (instance != null) {
                    Log.i("MouseService", "ensureAccessibilityEnabled: service bound")
                } else {
                    Log.i("MouseService", "ensureAccessibilityEnabled: not bound yet — watchdog will retry in ${WATCHDOG_INTERVAL_MS / 1000}s")
                }
            } finally {
                enabling.set(false)
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

                // 2. Try ACTION_PASTE via the accessibility tree.
                //    For native EditText this is reliable and we're done.
                //    For WebView apps (e.g. OpenBubbles) ACTION_PASTE can return
                //    true without actually inserting text, so we skip it and go
                //    straight to keyevent which goes through the IME pipeline.
                val svc = service ?: instance
                var pastedViaA11y = false
                var isWebViewApp = false
                if (svc == null) {
                    Log.w("MouseService", "injectViaClipboardBlind: no a11y service instance, falling back to keyevent")
                } else {
                    try {
                        val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (focused != null) {
                            val pkg = focused.packageName?.toString() ?: ""
                            val className = focused.className?.toString() ?: ""
                            isWebViewApp = className.contains("WebView", ignoreCase = true)
                                    || pkg == "com.openbubbles.messaging"
                            Log.d("MouseService", "injectViaClipboardBlind: focused pkg=$pkg class=$className isWebView=$isWebViewApp")

                            if (isWebViewApp) {
                                // WebViews ignore clipboard-based paste (ACTION_PASTE
                                // and keyevent 279 both silently fail). Use ACTION_SET_TEXT
                                // which bypasses the clipboard and writes directly through
                                // the accessibility framework.
                                val existing = focused.text?.toString() ?: ""
                                val args = Bundle()
                                args.putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    existing + text
                                )
                                val set = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                if (set) {
                                    Log.i("MouseService", "injectViaClipboardBlind: WebView — ACTION_SET_TEXT ok for ${text.length} chars")
                                    pastedViaA11y = true
                                } else {
                                    Log.d("MouseService", "injectViaClipboardBlind: WebView — ACTION_SET_TEXT failed, falling back to input text")
                                }
                            } else {
                                val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                                if (pasted) {
                                    Log.i("MouseService", "injectViaClipboardBlind: pasted via a11y ACTION_PASTE for ${text.length} chars")
                                    pastedViaA11y = true
                                } else {
                                    Log.d("MouseService", "injectViaClipboardBlind: a11y ACTION_PASTE returned false, falling back to keyevent")
                                }
                            }
                            focused.recycle()
                        } else {
                            Log.d("MouseService", "injectViaClipboardBlind: no focused input node found, falling back to keyevent")
                        }
                    } catch (t: Throwable) {
                        Log.w("MouseService", "injectViaClipboardBlind: a11y paste threw — ${t.message}, falling back to keyevent")
                    }
                }

                // 3. Fallback for fields that don't expose themselves in the a11y tree.
                //    For WebView apps, try `input text` (simulates keystrokes, no clipboard).
                //    For native apps, try KEYCODE_PASTE (279) via shell.
                if (!pastedViaA11y) {
                    if (isWebViewApp) {
                        // `input text` simulates keystrokes through the input subsystem —
                        // works for WebViews where clipboard paste can't reach.
                        val escaped = text.replace(" ", "%s")
                            .replace("'", "'\\''")
                        val proc = ProcessBuilder("su", "-c", "input text '$escaped'")
                            .redirectErrorStream(true)
                            .start()
                        val exitCode = proc.waitFor()
                        if (exitCode != 0) {
                            Log.e("MouseService", "injectViaClipboardBlind: input text failed with exit code $exitCode")
                            showToast(ctx, "TypeSync: paste failed")
                        } else {
                            Log.i("MouseService", "injectViaClipboardBlind: typed via input text for ${text.length} chars")
                        }
                    } else {
                        val proc = ProcessBuilder("su", "-c", "input keyevent 279")
                            .redirectErrorStream(true)
                            .start()
                        val exitCode = proc.waitFor()
                        if (exitCode != 0) {
                            Log.e("MouseService", "injectViaClipboardBlind: paste keyevent failed with exit code $exitCode")
                            showToast(ctx, "TypeSync: paste failed")
                        } else {
                            Log.i("MouseService", "injectViaClipboardBlind: pasted via keyevent 279 for ${text.length} chars")
                        }
                    }
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

    // ── Background location warming ────────────────────────────────────
    // Grabs a single GPS + network fix every 30 min so getLastKnownLocation()
    // always has a recent result. This means apps like Google Maps and
    // Uber Lite get a near-instant fix instead of a 15-30 s cold start.
    //
    // Uses single-shot requests instead of continuous updates because
    // MediaTek GPS ignores minTime and streams fixes at 1 Hz.
    private val warmHandler = Handler(Looper.getMainLooper())
    private var warmRunning = false
    private val WARM_INTERVAL_MS = 30L * 60 * 1000  // 30 minutes

    @SuppressLint("MissingPermission")
    private fun startLocationWarming() {
        if (warmRunning) return
        warmRunning = true
        Log.i("LocWarmer", "Starting single-shot location warming (every ${WARM_INTERVAL_MS / 60000}min)")
        requestSingleFix()
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleFix() {
        if (!warmRunning) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
            var gotFix = false

            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (!gotFix) {
                        gotFix = true
                        Log.d("LocWarmer", "Fix: ${loc.provider} acc=${loc.accuracy}m — removing listener, next in ${WARM_INTERVAL_MS / 60000}min")
                        lm.removeUpdates(this)
                        scheduleNextFix()
                    }
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            }

            // Safety: if no fix arrives in 30 s, remove listener and schedule next attempt
            warmHandler.postDelayed({
                if (!gotFix) {
                    Log.w("LocWarmer", "No fix after 30s — will retry in ${WARM_INTERVAL_MS / 60000}min")
                    lm.removeUpdates(listener)
                    scheduleNextFix()
                }
            }, 30_000)
        } catch (e: Exception) {
            Log.w("LocWarmer", "Failed to request fix: ${e.message}")
            scheduleNextFix()
        }
    }

    private fun scheduleNextFix() {
        if (!warmRunning) return
        warmHandler.postDelayed(::requestSingleFix, WARM_INTERVAL_MS)
    }

    private fun stopLocationWarming() {
        warmRunning = false
        warmHandler.removeCallbacksAndMessages(null)
        Log.i("LocWarmer", "Stopped location warming")
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

        // Keep the system location cache warm so Maps / Uber get near-instant fixes
        startLocationWarming()

        // Prime the clipboard framework so the first TypeSync paste into a
        // WebView (e.g. OpenBubbles) works immediately.  Without this,
        // WebViews sometimes ignore ACTION_PASTE until the clipboard has
        // been written to at least once in the current process.
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("init", ""))
            Log.d("MouseService", "Clipboard primed")
        } catch (_: Exception) {}
    }

    private fun isTargetApp(pkg: String): Boolean =
        (pkg == "com.openbubbles.messaging" && isOpenBubblesMouseNeeded(this))
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
        stopLocationWarming()
        super.onDestroy()
        instance = null
    }
}
