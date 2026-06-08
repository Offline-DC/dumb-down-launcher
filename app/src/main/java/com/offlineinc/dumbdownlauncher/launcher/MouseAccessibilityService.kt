package com.offlineinc.dumbdownlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.offline.dpadmessenger.backend.gmessages.GMGaiaClient
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesAccountStore
import com.offlineinc.dumbdownlauncher.launcher.ResetWarningOverlay
import com.offlineinc.dumbdownlauncher.launcher.qrenlarge.QrEnlargeController
import com.offlineinc.dumbdownlauncher.messenger.GmessagesCookieCallbacks
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

        /**
         * Audio apps that require mouse support and should be hidden when the
         * audio bundle is already included in the user's subscription.
         */
        val AUDIO_APP_PACKAGES = setOf(
            "de.danoeh.antennapod",   // AntennaPod
            "com.spotify.music",      // Spotify
            "com.apple.android.music" // Apple Music
        )

        /* ── Watchdog: periodically verifies the service is still bound ──────── */

        private val watchdogHandler = Handler(Looper.getMainLooper())

        /** Initial retry cadence while we still think binding is possible. */
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        /** Slow cadence once we've given up on fast retries — keeps polling
         *  cheaply in case the system eventually binds (e.g. after a settings
         *  change), without spamming logs every 15s forever. */
        private const val WATCHDOG_LONG_INTERVAL_MS = 5 * 60_000L

        /** After this many consecutive "still unbound" checks we stop toggling
         *  settings / writing warnings and switch to the long interval. Typing
         *  via the keyevent fallback works fine without a11y, so there's no
         *  value in shouting about it. */
        private const val WATCHDOG_QUIET_AFTER = 4

        private var watchdogConsecutiveMisses = 0

        private val watchdogRunnable = object : Runnable {
            override fun run() {
                if (instance != null) {
                    // Service came back — reset counter and resume normal cadence.
                    if (watchdogConsecutiveMisses > 0) {
                        Log.i("MouseService", "watchdog: service bound — resuming normal cadence")
                        watchdogConsecutiveMisses = 0
                    }
                    watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                    return
                }

                watchdogConsecutiveMisses++
                if (watchdogConsecutiveMisses <= WATCHDOG_QUIET_AFTER) {
                    Log.w("MouseService", "watchdog: instance null — re-enabling (attempt $watchdogConsecutiveMisses)")
                    shellExecutor.execute { ensureAccessibilityEnabled() }
                    watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                } else {
                    // Quiet mode: poll at a slow interval, log at debug level,
                    // skip the settings-toggle — it didn't help the last 4 times.
                    if (watchdogConsecutiveMisses == WATCHDOG_QUIET_AFTER + 1) {
                        Log.i(
                            "MouseService",
                            "watchdog: a11y still unbound after $WATCHDOG_QUIET_AFTER retries — " +
                                "backing off to every ${WATCHDOG_LONG_INTERVAL_MS / 60_000}min " +
                                "(typesync keyevent fallback still works)"
                        )
                    } else {
                        Log.d("MouseService", "watchdog: still unbound (slow poll)")
                    }
                    watchdogHandler.postDelayed(this, WATCHDOG_LONG_INTERVAL_MS)
                }
            }
        }

        fun startWatchdog() {
            watchdogHandler.removeCallbacks(watchdogRunnable)
            watchdogConsecutiveMisses = 0
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

        // Google Messages cookie sign-in: the sign-in screen registers callbacks
        // here so the login (a `gmessages_cookies` message) — which arrives on
        // THIS, the one live relay socket — can be decrypted, saved, and paired,
        // with progress surfaced back to the UI. (Previously a second socket
        // handled this and the two evicted each other.)
        @Volatile private var gmessagesCallbacks: GmessagesCookieCallbacks? = null

        // True only while the relay WebSocket is open AND the handshake has been
        // sent (i.e. we own the `phone` slot and can receive). The cookie
        // sign-in screen polls this so it can tell the user (and the log)
        // whether the flip is actually listening when the companion sends.
        @Volatile private var relayConnected = false

        // In-flight Google Messages pairing state. Only ONE handshake runs at a
        // time. We track it richly (not a bare boolean) so we can: dedupe the
        // companion's identical cookie resends (which otherwise spawn overlapping
        // attempts → Google rejects with NEW_REQUEST_WHILE_WAITING_FOR_VERIFICATION,
        // errCode 23); supersede a stale/stuck attempt when a genuinely new login
        // arrives; and replay progress to a screen that (re)opens mid-pairing so it
        // never sits on "waiting…" while an attempt is actually running.
        @Volatile private var pairingThread: Thread? = null
        @Volatile private var pairingClient: com.offline.dpadmessenger.backend.gmessages.GMGaiaClient? = null
        @Volatile private var pairingCookieFp: String? = null   // sha-256 of the active attempt's cookies
        @Volatile private var pairingEmoji: String? = null      // last UKey2 emoji shown (for replay)
        @Volatile private var pairingActive: Boolean = false

        /** Whether the relay socket is currently open (handshake sent). Used by
         *  the Google Messages sign-in screen to confirm the flip is listening. */
        fun isRelayConnected(): Boolean = relayConnected

        /** Register (or clear, with null) the cookie sign-in screen's callbacks.
         *  If a pairing is already running when a screen (re)registers, replay the
         *  current state to it so it reflects the in-flight attempt instead of
         *  showing "waiting…" forever. */
        fun setGmessagesCookieCallbacks(cb: GmessagesCookieCallbacks?) {
            gmessagesCallbacks = cb
            // A pairing can FINISH while no screen is registered, or in the gap
            // between a screen disposing and re-registering (config change, the
            // user tabbing out of Smart Txt and back, the host recomposing the
            // inline sign-in slot). The account is saved, but the freshly
            // registered screen comes up with result=null and — because
            // pairingActive is already false — gets no replay, so it sits on
            // "waiting for ur smart phone…" forever even though we're paired.
            // Detect that here and replay the terminal success.
            val alreadyPaired =
                cb != null && appContext?.let { GoogleMessagesAccountStore(it).isPaired() } == true
            Log.i(RELAY_TAG, "gmessages cookie callbacks ${if (cb != null) "registered" else "cleared"}; relayConnected=$relayConnected pairingActive=$pairingActive alreadyPaired=$alreadyPaired")
            if (cb == null) return
            relayHandler.post {
                when {
                    // Pairing already completed before this screen registered →
                    // advance straight to the connected state instead of waiting.
                    alreadyPaired ->
                        cb.onResult?.invoke(true, "connected — ur dumb phone is paired with google messages.")
                    // Pairing is mid-flight → replay progress so a screen that
                    // (re)opened mid-pairing shows "signing in…" + the emoji.
                    pairingActive -> {
                        cb.onCookies?.invoke()
                        pairingEmoji?.let { cb.onEmoji?.invoke(it) }
                    }
                }
            }
        }

        private val relayClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        private const val RELAY_A11Y_WAIT_MS = 5000L

        /**
         * Start (or restart) the encrypted WebSocket relay.
         * Safe to call multiple times — only tears down the old socket if the
         * credentials (phone + secret) have actually changed.  When called
         * from MainActivity.onCreate on a config change or back-navigation,
         * the credentials are the same and the existing connection is kept
         * alive so the companion doesn't see a disconnect/reconnect cycle.
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

            // If already running with the same credentials, nothing to do —
            // avoids tearing down a healthy connection every time
            // MainActivity.onCreate fires (back-press, config change, etc.).
            if (relayRunning && relayPhone == phone && relaySecret == pairing.sharedSecret) {
                Log.d(RELAY_TAG, "startRelay: already connected with same credentials — keeping existing connection")
                return
            }

            // Tear down any existing connection (credentials changed, e.g. re-pairing)
            if (relayRunning) {
                Log.i(RELAY_TAG, "startRelay: credentials changed — restarting with fresh credentials")
                relayHandler.removeCallbacksAndMessages(null)
                relayWebSocket?.close(1000, "credential refresh")
                relayWebSocket = null
                relayReconnectCount = 0
            }

            // Mark as running immediately to prevent a second startRelay call
            // (e.g. from MainActivity.onCreate racing with onPaired) from
            // spawning a duplicate a11y-wait thread before openRelaySocket runs.
            relayRunning = true
            relayPhone = phone
            relaySecret = pairing.sharedSecret
            appContext = context.applicationContext

            // Ensure a11y + wait for binding on a background thread to avoid
            // ANR — ensureAccessibilityEnabled() does blocking sleep loops and
            // a synchronous `su -c` root shell call that can take 6+ seconds.
            Thread {
                ensureAccessibilityEnabled()

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
            relayConnected = false
        }

        private fun openRelaySocket(phoneNumber: String) {
            // relayRunning and relayPhone are already set by startRelay()
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
                    relayConnected = true
                    Log.i(RELAY_TAG, "relay listening (phone slot owned) — ready to receive text + gmessages cookies")
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(RELAY_TAG, "← $text")
                    try {
                        val msg = JSONObject(text)
                        when (msg.getString("type")) {
                            "text" -> handleRelayText(msg)
                            "gmessages_cookies" -> handleRelayGmessagesCookies(ws, msg)
                            "auth_failed" -> {
                                Log.e(RELAY_TAG, "❌ Auth failed: ${msg.optString("reason")}")
                                stopRelay()
                            }
                            "companion_connected" -> Log.i(RELAY_TAG, "📱 Companion connected: ${msg.optString("role")} — if signing in, cookies should arrive next")
                            "companion_disconnected" -> Log.i(RELAY_TAG, "📵 Companion disconnected")
                            else -> Log.d(RELAY_TAG, "ignoring type=${msg.getString("type")}")
                        }
                    } catch (e: Exception) {
                        Log.w(RELAY_TAG, "parse failed: ${e.message}")
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    relayConnected = false
                    Log.e(RELAY_TAG, "❌ WS failure: ${t.message}")
                    relayReconnectCount++
                    if (relayRunning) {
                        Log.i(RELAY_TAG, "🔄 reconnecting in 3 s (attempt $relayReconnectCount)")
                        relayHandler.postDelayed({ openRelaySocket(phoneNumber) }, 3_000)
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    relayConnected = false
                    Log.i(RELAY_TAG, "🔌 WS closed: code=$code reason=\"$reason\"")
                    // Server closed the socket (e.g. "re-paired" or "replaced").
                    // Re-read credentials from the pairing store and reconnect
                    // so the relay comes back up with the new shared secret.
                    if (relayRunning && reason != "relay stopped") {
                        Log.i(RELAY_TAG, "🔄 server-initiated close — reconnecting with fresh credentials")
                        relayWebSocket = null
                        val ctx = appContext ?: return
                        val freshPairing = DeviceLinkReader.readPairing(ctx) ?: return
                        relaySecret = freshPairing.sharedSecret
                        relayHandler.postDelayed({ openRelaySocket(phoneNumber) }, 1_000)
                    }
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

        /**
         * Receive the user's Google-account cookies from the companion over this
         * (single, live) relay socket, decrypt + store them, ack, and run the
         * GAIA / UKey2 emoji-match pairing. Progress is surfaced to the sign-in
         * screen via the registered [GmessagesCookieCallbacks].
         */
        private fun handleRelayGmessagesCookies(ws: WebSocket, msg: JSONObject) {
            val cb = gmessagesCallbacks
            Log.i(RELAY_TAG, "📥 gmessages_cookies received (callbacksRegistered=${cb != null}, " +
                "relayConnected=$relayConnected, hasSecret=${relaySecret != null}, hasCtx=${appContext != null})")
            // Each of these would otherwise drop the login SILENTLY — log so a
            // missed token is always visible in logcat (tag $RELAY_TAG).
            val secret = relaySecret ?: run {
                Log.e(RELAY_TAG, "✋ dropping gmessages_cookies — relaySecret is null (relay not handshaken yet?)")
                return
            }
            val ctx = appContext ?: run {
                Log.e(RELAY_TAG, "✋ dropping gmessages_cookies — appContext is null")
                return
            }
            if (!msg.has("encrypted") || !msg.has("iv")) {
                Log.e(RELAY_TAG, "✋ dropping gmessages_cookies — missing encrypted/iv fields; keys=${msg.keys().asSequence().toList()}")
                return
            }

            val cookies = try {
                val cipher = TypeSyncCrypto.fromBase64(msg.getString("encrypted"))
                val iv = TypeSyncCrypto.fromBase64(msg.getString("iv"))
                val plain = TypeSyncCrypto.decryptAesGcm(cipher, iv, secret)
                val obj = JSONObject(String(plain, Charsets.UTF_8))
                HashMap<String, String>().apply {
                    val keys = obj.keys()
                    while (keys.hasNext()) { val k = keys.next(); put(k, obj.getString(k)) }
                }
            } catch (e: Exception) {
                Log.e(RELAY_TAG, "❌ gmessages cookie decrypt failed", e)
                relayHandler.post {
                    cb?.onResult?.invoke(false, "Couldn't read the login from your phone. Try again.")
                }
                return
            }

            GoogleMessagesAccountStore(ctx).saveCookies(cookies)
            // Always re-ack so the companion stops resending the login.
            runCatching {
                ws.send(JSONObject().put("type", "gmessages_cookies_ack").put("ok", true).toString())
            }
            Log.i(RELAY_TAG, "🔓 saved ${cookies.size} gmessages cookies; names=${cookies.keys.sorted()}")

            // Already paired? A stray late resend shouldn't kick off a new
            // background handshake. Re-pairing always goes through logout (which
            // clears the store), so isPaired() is false by the time a genuine
            // re-pair sends fresh cookies.
            if (GoogleMessagesAccountStore(ctx).isPaired()) {
                Log.i(RELAY_TAG, "↩︎ already paired — refreshed cookies, not re-pairing")
                return
            }

            // ONE handshake at a time, but never let a stale one wedge us:
            //  - identical resend while an attempt is alive → ignore (re-acked).
            //    (Overlapping CLIENT_INITs are what cause Google's errCode 23
            //     NEW_REQUEST_WHILE_WAITING_FOR_VERIFICATION.)
            //  - a genuinely new login (different cookies) → cancel the old
            //    attempt and start fresh, so a stuck/abandoned attempt can't
            //    block re-pairing.
            val fp = sha256Hex(cookies.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" })
            val running = pairingThread?.isAlive == true && pairingActive
            if (running) {
                if (fp == pairingCookieFp) {
                    Log.i(RELAY_TAG, "↩︎ identical cookies while pairing — ignoring duplicate (re-acked)")
                    return
                }
                Log.i(RELAY_TAG, "⟳ new login while a pairing is running — cancelling the old attempt and restarting")
                runCatching { pairingClient?.cancel() }
                runCatching { pairingThread?.interrupt() }
            }

            pairingCookieFp = fp
            pairingEmoji = null
            pairingActive = true
            Log.i(RELAY_TAG, "▶︎ starting gmessages pairing")
            relayHandler.post { gmessagesCallbacks?.onCookies?.invoke() } // UI: "waiting…" → "signing in…"

            // GAIA cookie-auth + UKey2 emoji pairing. Blocks (waits indefinitely
            // for the user to tap the matching emoji), so run off the WS thread.
            // Callbacks are read LIVE (gmessagesCallbacks) so a screen that opens
            // mid-pairing receives the emoji/result, not a screen that has closed.
            val t = Thread {
                val gaia = GMGaiaClient(ctx)
                pairingClient = gaia
                val paired = runCatching {
                    gaia.run(onEmoji = { emoji ->
                        pairingEmoji = emoji
                        relayHandler.post { gmessagesCallbacks?.onEmoji?.invoke(emoji) }
                    })
                }.getOrElse { Log.e(RELAY_TAG, "GMGaia run failed", it); false }
                // Only the CURRENT attempt updates shared state / reports a result.
                // A superseded attempt (a newer login replaced it) must stay silent
                // so it can't clobber the new attempt or flash a stale failure.
                if (pairingThread !== Thread.currentThread()) {
                    Log.i(RELAY_TAG, "▫︎ superseded pairing attempt ended (paired=$paired) — staying quiet")
                    return@Thread
                }
                pairingActive = false
                pairingEmoji = null
                Log.i(RELAY_TAG, "■ gmessages pairing finished paired=$paired")
                relayHandler.post {
                    if (paired) {
                        gmessagesCallbacks?.onResult?.invoke(true, "connected — ur dumb phone is paired with google messages.")
                    } else {
                        // Prefer the specific reason from the GAIA step (e.g. the
                        // login was rejected as invalid) over the generic emoji
                        // message — otherwise a cookie failure misleads the user
                        // into re-tapping an emoji that never appeared.
                        gmessagesCallbacks?.onResult?.invoke(
                            false,
                            gaia.lastError
                                ?: "Failed to pair. Make sure you tapped the matching emoji on your phone, then try again.",
                        )
                    }
                }
            }
            pairingThread = t
            t.start()
        }

        /** SHA-256 hex of [s] — used to fingerprint cookie payloads so identical
         *  resends are deduped without keeping the cookies around in memory. */
        private fun sha256Hex(s: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

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

                // If the a11y service isn't bound yet, try to force-enable it.
                // If we already know it's down (a11yKnownDown=true), skip the
                // 5-second wait entirely and go straight to keyevent — otherwise
                // every typed word costs a 5s stall while the service is out.
                if (instance == null) {
                    if (a11yKnownDown) {
                        // Service is known-down; watchdog is already working on it.
                        // Don't block — go straight to keyevent fallback.
                        Log.d("MouseService", "injectText: a11y known-down — skipping wait, using keyevent")
                    } else {
                        Log.w("MouseService", "injectText: a11y instance null — calling ensureAccessibilityEnabled and waiting for bind")
                        ensureAccessibilityEnabled()
                        // ensureAccessibilityEnabled already waited up to 6s and toggled
                        // the setting; check the result rather than waiting again.
                        if (instance != null) {
                            Log.i("MouseService", "injectText: a11y service bound — proceeding with ACTION_PASTE")
                        } else {
                            Log.w("MouseService", "injectText: a11y service still null — keyevent fallback will be used")
                        }
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

        /**
         * True once we've confirmed the service is not coming back on its own.
         * Cleared in [onServiceConnected] when the service binds again.
         * Used by [injectText] to skip the 5s wait and go straight to keyevent,
         * avoiding the "each word takes 5 seconds" problem when the service is down.
         */
        @Volatile var a11yKnownDown = false

        // ── WhatsApp phone-entry → companion-mode redirect ───────────────
        // When the user lands on WhatsApp's phone-number entry page we
        // silently launch RegisterAsCompanionActivity instead, so their
        // other devices don't get logged out.
        //
        // The cooldown serves two purposes:
        //   1. Suppresses the redirect storm from back-to-back
        //      WINDOW_STATE_CHANGED events for the same page (the keyboard
        //      opening, FrameLayout re-layout, etc.).
        //   2. Prevents a back-button loop: `am start --activity-clear-top`
        //      only clears activities above the target *if it's already in
        //      the task*. On first launch it isn't, so companion gets
        //      stacked on top of RegisterPhone. If the user then presses
        //      Back to exit WhatsApp, RegisterPhone reappears underneath
        //      and would re-trigger the redirect. 15 s comfortably covers
        //      a back-press-out sequence without penalising the user who
        //      genuinely revisits the page much later.
        private const val WA_COMPANION_PKG = "com.whatsapp"
        private const val WA_COMPANION_CLASS =
            "com.whatsapp.companionmode.registration.ui.RegisterAsCompanionActivity"
        private const val WA_REDIRECT_COOLDOWN_MS = 15_000L

        @Volatile private var lastWaCompanionRedirectMs: Long = 0L

        /** How long to wait for Android to bind the service after a settings toggle (ms). */
        private const val INITIAL_BIND_GRACE_MS = 3000L

        /**
         * Ensures the accessibility service is bound. First waits briefly for
         * natural binding, then actively toggles the secure setting via root to
         * force Android to rebind the service if it hasn't appeared on its own.
         */
        fun ensureAccessibilityEnabled() {
            if (instance != null) return
            if (!enabling.compareAndSet(false, true)) return
            try {
                // Stage 1: wait briefly for natural binding (e.g. just started)
                val deadline1 = System.currentTimeMillis() + INITIAL_BIND_GRACE_MS
                while (instance == null && System.currentTimeMillis() < deadline1) {
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                }
                if (instance != null) {
                    Log.i("MouseService", "ensureAccessibilityEnabled: service bound naturally")
                    a11yKnownDown = false
                    return
                }

                // Stage 2: actively toggle the setting to force Android to rebind.
                // Some devices (especially MediaTek) require a settings write after
                // a process restart before the system will re-deliver the service bind.
                // Only log at INFO for the first few attempts; after that drop
                // to DEBUG so the watchdog doesn't fill logcat.
                if (watchdogConsecutiveMisses <= WATCHDOG_QUIET_AFTER) {
                    Log.i("MouseService", "ensureAccessibilityEnabled: not bound — toggling setting to force rebind")
                } else {
                    Log.d("MouseService", "ensureAccessibilityEnabled: not bound — toggling setting (quiet)")
                }
                lastToggleTimestamp = android.os.SystemClock.uptimeMillis()
                try {
                    ProcessBuilder(
                        "su", "-c",
                        // Delete then re-insert the service ID so the ContentObserver
                        // always fires — a plain `put` with the same value is a no-op
                        // and may not trigger the accessibility manager to rebind.
                        // This is especially important after `am force-stop` clears
                        // the stopped flag: the earlier settings write (while the app
                        // was stopped) was observed but couldn't bind; by the time the
                        // watchdog runs the app is live, but the setting hasn't changed,
                        // so the manager won't retry without this delete+put cycle.
                        "settings delete secure enabled_accessibility_services && " +
                        "settings put secure enabled_accessibility_services $A11Y_SERVICE_ID && " +
                        "settings put secure accessibility_enabled 1"
                    ).redirectErrorStream(true).start().waitFor()
                } catch (e: Exception) {
                    Log.w("MouseService", "ensureAccessibilityEnabled: settings toggle failed: ${e.message}")
                }

                // Stage 3: wait again after the toggle
                val deadline2 = System.currentTimeMillis() + INITIAL_BIND_GRACE_MS
                while (instance == null && System.currentTimeMillis() < deadline2) {
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                }

                if (instance != null) {
                    Log.i("MouseService", "ensureAccessibilityEnabled: service bound after toggle")
                    a11yKnownDown = false
                } else {
                    if (watchdogConsecutiveMisses <= WATCHDOG_QUIET_AFTER) {
                        Log.i("MouseService", "ensureAccessibilityEnabled: not bound yet — watchdog will retry in ${WATCHDOG_INTERVAL_MS / 1000}s")
                    } else {
                        Log.d("MouseService", "ensureAccessibilityEnabled: not bound yet (quiet)")
                    }
                    // Mark as known-down so injectText stops waiting 5s on every call
                    a11yKnownDown = true
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        a11yKnownDown = false
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
            || pkg in AUDIO_APP_PACKAGES

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

    /** True while we're swallowing a BACK key sequence on behalf of the
     *  QR-enlarge overlay. Set on ACTION_DOWN, cleared on ACTION_UP. Both
     *  must be consumed so WhatsApp doesn't see an orphan KEY_UP and close
     *  the companion activity. */
    @Volatile private var qrBackConsumed: Boolean = false

    /** Same idea for POUND — when we're swallowing it to toggle the QR
     *  enlarge, both DOWN and UP must be consumed so the keypad's # key
     *  doesn't fall through to WhatsApp as an unrelated key event. */
    @Volatile private var qrPoundConsumed: Boolean = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // ── QR-enlarge BACK handling ─────────────────────────────────────
        // When the enlarged QR overlay is up, BACK should dismiss it and
        // return to WhatsApp's small QR — not navigate away from
        // RegisterAsCompanionActivity. Handled before the ACTION_DOWN
        // early-return because we need to consume the matching ACTION_UP too.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (QrEnlargeController.isEnlargedShowing) {
                        Log.d("QR_ENLARGE_CTRL", "onKeyEvent: BACK with overlay up — consuming")
                        QrEnlargeController.dismissEnlargedQr()
                        qrBackConsumed = true
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (qrBackConsumed) {
                        qrBackConsumed = false
                        return true
                    }
                }
            }
        }
        // ── end QR-enlarge BACK handling ─────────────────────────────────

        // ── QR-enlarge POUND handling ────────────────────────────────────
        // On the WhatsApp companion screen, # toggles the enlarged QR
        // overlay (pressing again collapses it). The existing STAR handler
        // below is left alone — it owns the special-char picker on every
        // other app and isn't used on the companion screen.
        if (event.keyCode == KeyEvent.KEYCODE_POUND && QrEnlargeController.isOnCompanionScreen) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    Log.d("QR_ENLARGE_CTRL", "onKeyEvent: # on companion screen — toggling")
                    QrEnlargeController.toggleEnlargedQr(this)
                    qrPoundConsumed = true
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (qrPoundConsumed) {
                        qrPoundConsumed = false
                        return true
                    }
                }
            }
        }
        // ── end QR-enlarge POUND handling ────────────────────────────────

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

            // ── Factory-reset warning overlay ──────────────────────────────
            // We only show the warning on the specific Reset options page that
            // lists BOTH "Factory data reset" and "Network settings reset" as
            // menu items — that combination uniquely identifies the screen and
            // is robust to TCL-specific class name variations.
            if (pkg == "com.android.settings") {
                Log.d("RESET_WARN", "Settings event: class=$className — checking page content")
                if (isResetOptionsScreen()) {
                    Log.d("RESET_WARN", "Factory-reset page confirmed — showing warning overlay")
                    ResetWarningOverlay.show(this)
                } else {
                    if (ResetWarningOverlay.isShowing) {
                        Log.d("RESET_WARN", "Navigated away from reset page — hiding overlay")
                    }
                    // User navigated away from the reset page — clear the dismissed
                    // flag so the overlay can appear again next time they visit.
                    ResetWarningOverlay.clearDismissed()
                    ResetWarningOverlay.hide()
                }
            } else if (pkg != packageName && (ResetWarningOverlay.isShowing || ResetWarningOverlay.userDismissed)) {
                // User navigated to a genuinely different app — dismiss and clear
                // the dismissed flag so the overlay shows fresh next Settings visit.
                // We explicitly skip our own package here because adding the
                // overlay window itself fires a WINDOW_STATE_CHANGED event for
                // com.offlineinc.dumbdownlauncher, which would otherwise
                // immediately call hide() and create an infinite show/hide loop.
                ResetWarningOverlay.clearDismissed()
                ResetWarningOverlay.hide()
            }
            // ── end reset warning ──────────────────────────────────────────

            // ── WhatsApp phone-entry → companion-mode redirect ─────────────
            // When the user lands on WhatsApp's phone-number entry page
            // (com.whatsapp.registration.app.phonenumberentry.RegisterPhone
            // in current builds), silently launch the companion-mode
            // registration activity instead. Entering a phone number logs
            // other devices out of WhatsApp, which is almost never what the
            // user wants on this device — linking as a companion preserves
            // the primary session.
            //
            // IME events are excluded (e.g. pkg=com.iqqijni.dvt912key,
            // className=android.inputmethodservice.*) so the keyboard
            // opening on top of the phone field doesn't count as "left the
            // page".
            val isImeEvent = className.contains("InputMethod", ignoreCase = true)
                || className.contains("SoftInput", ignoreCase = true)
            if (pkg == "com.whatsapp" && !isImeEvent) {
                val isPhoneEntryClass = className.contains("phonenumberentry", ignoreCase = true)
                val matchesPhoneEntryText = isWhatsAppPhoneEntryScreen()
                val isPhonePage = isPhoneEntryClass || matchesPhoneEntryText
                Log.d(
                    "WA_COMPANION_REDIRECT",
                    "WhatsApp event: class=$className isPhoneEntryClass=$isPhoneEntryClass textMatch=$matchesPhoneEntryText",
                )
                if (isPhonePage) {
                    redirectToWhatsAppCompanion()
                }
            }
            // ── end WhatsApp redirect ──────────────────────────────────────

            // ── WhatsApp companion-mode QR enlarge ─────────────────────────
            // The companion-mode QR is drawn at ~120px on a 240x320 screen,
            // which is too small for another phone's camera to scan. When
            // the user lands on RegisterAsCompanionActivity, kick off the
            // QrEnlargeController: it screencaps the QR, decodes the
            // payload, and shows a fullscreen high-res QR overlay.
            //
            // Tearing the overlay down is gated tightly because the capture
            // pipeline takes ~3s on this device (root screencap + decode +
            // encode) and a stray foreground change in the middle would
            // cancel a successful run. Rule:
            //   • Only act on events whose className contains "Activity".
            //     This filters out status-bar / SystemUI FrameLayout events,
            //     IME windows, popup dialogs, and our own overlay window
            //     (adding TYPE_ACCESSIBILITY_OVERLAY fires a non-Activity
            //     WINDOW_STATE_CHANGED for the launcher package, which
            //     would otherwise immediately tear our own overlay down).
            //   • On the companion activity → start.
            //   • On any other Activity event (including the launcher's
            //     own home Activity when the user hits Home) → tear down.
            val classLooksLikeActivity = className.contains("Activity")
            val isSystemUi = pkg == "com.android.systemui"
            if (!isImeEvent && !isSystemUi && classLooksLikeActivity) {
                val onCompanionScreen = pkg == WA_COMPANION_PKG
                    && className == WA_COMPANION_CLASS
                if (onCompanionScreen) {
                    QrEnlargeController.onCompanionScreenEntered()
                } else {
                    QrEnlargeController.onLeftCompanionScreen()
                }
            }
            // ── end QR enlarge ─────────────────────────────────────────────

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

    /**
     * Returns true when the currently visible Settings screen is the factory-
     * reset confirmation page, identified by the phrase
     * "This will delete all data from internal storage, including:" appearing
     * in the accessibility tree.  Using page content makes this robust across
     * TCL-specific Settings builds and locale-independent class names.
     */
    private fun isResetOptionsScreen(): Boolean {
        return try {
            val root = rootInActiveWindow
            if (root == null) {
                Log.d("RESET_WARN", "isResetOptionsScreen: rootInActiveWindow is null")
                return false
            }
            val needle = "This will delete all data from internal storage, including:"
            val matches = root.findAccessibilityNodeInfosByText(needle)
            Log.d("RESET_WARN", "isResetOptionsScreen: found ${matches.size} node(s) matching needle")
            root.recycle()
            matches.isNotEmpty()
        } catch (e: Exception) {
            Log.w("RESET_WARN", "isResetOptionsScreen: exception — ${e.message}")
            false
        }
    }

    /**
     * Launches WhatsApp's companion-mode registration activity in place of
     * the phone-number entry page. Uses the root shell's `am start` because
     * RegisterAsCompanionActivity is not exported, so a regular
     * [startActivity] from our app's uid would raise a SecurityException.
     *
     * Activities launched via `am start` run under the target app's uid, so
     * WhatsApp sees the same caller identity it would if the user had
     * tapped through its own "Link a device as a companion" flow.
     *
     * Protected by a short cooldown ([WA_REDIRECT_COOLDOWN_MS]) because the
     * phone-entry page emits multiple WINDOW_STATE_CHANGED events in quick
     * succession (FrameLayout → Main → EULA → RegisterPhone → keyboard),
     * and we don't want to fire `am start` four times per visit.
     */
    private fun redirectToWhatsAppCompanion() {
        val now = android.os.SystemClock.uptimeMillis()
        val elapsed = now - lastWaCompanionRedirectMs
        if (elapsed < WA_REDIRECT_COOLDOWN_MS) {
            Log.d(
                "WA_COMPANION_REDIRECT",
                "redirect suppressed — last attempt ${elapsed}ms ago (cooldown ${WA_REDIRECT_COOLDOWN_MS}ms)",
            )
            return
        }
        lastWaCompanionRedirectMs = now
        Log.i("WA_COMPANION_REDIRECT", "redirecting to $WA_COMPANION_PKG/$WA_COMPANION_CLASS")

        shellExecutor.execute {
            try {
                val proc = ProcessBuilder(
                    "su", "-c",
                    // --activity-clear-top + --activity-single-top: if the
                    // companion activity is already in WhatsApp's task, bring
                    // it forward and clear anything above it (including the
                    // phone-entry page) so back-press doesn't return the user
                    // to RegisterPhone and re-trigger this redirect.
                    "am start --activity-clear-top --activity-single-top " +
                        "-n $WA_COMPANION_PKG/$WA_COMPANION_CLASS"
                ).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                if (exit != 0) {
                    Log.w(
                        "WA_COMPANION_REDIRECT",
                        "am start exited $exit — output: ${output.trim()}",
                    )
                } else {
                    Log.i("WA_COMPANION_REDIRECT", "am start ok: ${output.trim()}")
                }
            } catch (t: Throwable) {
                Log.e("WA_COMPANION_REDIRECT", "am start failed: ${t.message}", t)
            }
        }
    }

    /**
     * Returns true when the currently visible WhatsApp screen is the phone-
     * number entry page, identified by the headline "Enter your phone
     * number" appearing in the accessibility tree.
     *
     * We intentionally do NOT require a body-text match: the body paragraph
     * ("WhatsApp will need to verify your phone number. Carrier charges may
     * apply. What's my number?") contains a clickable span which causes the
     * accessibility tree to split the paragraph across multiple nodes, so
     * `findAccessibilityNodeInfosByText` never returns a hit for the full
     * substring. The class-name check in [onAccessibilityEvent] is the
     * primary signal; this text match is a secondary fallback for WhatsApp
     * builds where the class path differs.
     */
    private fun isWhatsAppPhoneEntryScreen(): Boolean {
        return try {
            val root = rootInActiveWindow
            if (root == null) {
                Log.d("WA_PHONE_WARN", "isWhatsAppPhoneEntryScreen: rootInActiveWindow is null")
                return false
            }
            val header = "Enter your phone number"
            val headerMatches = root.findAccessibilityNodeInfosByText(header)
            Log.d(
                "WA_PHONE_WARN",
                "isWhatsAppPhoneEntryScreen: header=${headerMatches.size}",
            )
            root.recycle()
            headerMatches.isNotEmpty()
        } catch (e: Exception) {
            Log.w("WA_PHONE_WARN", "isWhatsAppPhoneEntryScreen: exception — ${e.message}")
            false
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
                setDensity(if (loggedIn) 120 else 90)
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
