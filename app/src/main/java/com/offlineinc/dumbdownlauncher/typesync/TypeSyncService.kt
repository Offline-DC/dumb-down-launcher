package com.offlineinc.dumbdownlauncher.typesync

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.offlineinc.dumbdownlauncher.MouseAccessibilityService
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Type Sync Service — connects to the encrypted WebSocket relay and receives
 * text from the companion iPhone app.
 *
 * Decrypts incoming AES-256-GCM messages using the shared secret from the
 * device-link pairing, then injects plaintext into the focused text field
 * via the clipboard + paste accessibility action.
 *
 * Authenticates with HMAC-SHA256 on the handshake to prove it holds the
 * shared secret.
 */
class TypeSyncService : Service() {

    companion object {
        private const val TAG = "TypeSyncService"
        private const val WS_URL = "wss://offline-dc-backend-ba4815b2bcc8.herokuapp.com/keyboard/ws"
        private const val TEN_MINUTES_MS = 10 * 60 * 1000L
        private const val RECONNECT_BASE_MS = 3_000L
        private const val RECONNECT_MAX_MS  = 5 * 60 * 1000L   // 5 minutes
        private const val MAX_RECONNECT_ATTEMPTS = 20

        private var instance: TypeSyncService? = null

        fun isRunning(): Boolean = instance != null
    }

    private var webSocket: WebSocket? = null
    private var sharedSecret: String? = null
    private var flipPhoneNumber: String? = null
    private var reconnectCount = 0
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for WS
        .pingInterval(90, TimeUnit.SECONDS)    // keepalive — was missing, needed for long-lived WS
        .build()
    private var shutdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val shutdownRunnable = Runnable { stopSelf() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        MouseAccessibilityService.appContext = applicationContext
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pairing = DeviceLinkReader.readPairing(this)
        if (pairing == null) {
            Log.e(TAG, "No pairing found — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        sharedSecret = pairing.sharedSecret
        flipPhoneNumber = pairing.flipPhoneNumber

        // Ensure the accessibility service is bound before opening the WebSocket
        // so text injection works on the very first message. Mirrors the
        // waitForAccessibilityThenRelay() pattern in WebKeyboardService.
        waitForAccessibilityThenConnect()

        // Auto-stop after 10 minutes
        shutdownHandler.removeCallbacks(shutdownRunnable)
        shutdownHandler.postDelayed(shutdownRunnable, TEN_MINUTES_MS)

        return START_NOT_STICKY
    }

    private fun waitForAccessibilityThenConnect() {
        Thread {
            MouseAccessibilityService.ensureAccessibilityEnabled()
            val deadline = System.currentTimeMillis() + 5000L
            while (MouseAccessibilityService.instance == null &&
                   System.currentTimeMillis() < deadline) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
            if (MouseAccessibilityService.instance != null) {
                Log.i(TAG, "Accessibility service ready — connecting")
            } else {
                Log.w(TAG, "Accessibility service not ready after 5 s — connecting anyway (injectText will retry)")
            }
            connect()
        }.start()
    }

    private fun connect() {
        val phone = flipPhoneNumber ?: return
        val secret = sharedSecret ?: return

        // Build HMAC-authenticated handshake
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val hmacInput = "$phone$timestamp"
        val hmac = TypeSyncCrypto.hmacSha256Hex(hmacInput.toByteArray(), secret)

        val request = Request.Builder().url(WS_URL).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectCount = 0
                Log.i(TAG, "WebSocket opened, sending authenticated handshake")
                val handshake = JSONObject().apply {
                    put("type", "connect")
                    put("role", "phone")
                    put("phoneNumber", phone)
                    put("timestamp", timestamp)
                    put("hmac", hmac)
                }
                webSocket.send(handshake.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.getString("type")) {
                        "text" -> handleEncryptedText(json)
                        "auth_failed" -> {
                            Log.e(TAG, "Auth failed: ${json.optString("reason")}")
                            stopSelf()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                reconnectCount++
                if (reconnectCount > MAX_RECONNECT_ATTEMPTS) {
                    Log.e(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached — stopping")
                    stopSelf()
                    return
                }
                // Exponential backoff: 3s, 6s, 12s, … capped at 5 min
                val delay = (RECONNECT_BASE_MS * (1L shl (reconnectCount - 1).coerceAtMost(10)))
                    .coerceAtMost(RECONNECT_MAX_MS)
                Log.i(TAG, "Reconnecting in ${delay/1000}s (attempt $reconnectCount/$MAX_RECONNECT_ATTEMPTS)")
                shutdownHandler.postDelayed({ connect() }, delay)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
            }
        })
    }

    private fun handleEncryptedText(json: JSONObject) {
        val secret = sharedSecret ?: return
        val encryptedB64 = json.getString("encrypted")
        val ivB64 = json.getString("iv")

        try {
            val ciphertext = TypeSyncCrypto.fromBase64(encryptedB64)
            val iv = TypeSyncCrypto.fromBase64(ivB64)
            val plaintext = TypeSyncCrypto.decryptAesGcm(ciphertext, iv, secret)
            val text = String(plaintext, Charsets.UTF_8)
            Log.i(TAG, "Decrypted text (${text.length} chars)")
            injectText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
        }
    }

    /**
     * Injects text into the currently focused text field via the accessibility
     * service's clipboard paste (or ACTION_SET_TEXT fallback).
     */
    private fun injectText(text: String) {
        MouseAccessibilityService.injectText(text)
    }

    private fun sendDisconnect() {
        try {
            val msg = JSONObject().put("type", "disconnect")
            webSocket?.send(msg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send disconnect", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdownHandler.removeCallbacks(shutdownRunnable)
        sendDisconnect()
        webSocket?.close(1000, "service stopped")
        webSocket = null
        instance = null
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
