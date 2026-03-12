package com.offlineinc.dumbdownlauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TypeSyncService
 *
 * Foreground service that maintains a WebSocket connection to the relay backend.
 * Identified by the device's phone number — no PIN needed.
 *
 * Lifecycle:
 *   startService(ACTION_START, phoneNumber) → opens WS, starts 10-min timer
 *   startService(ACTION_STOP)               → closes WS, stops service
 *
 * When a { type:"text", text:"..." } message arrives, it is injected into the
 * currently focused field via MouseAccessibilityService.
 */
class WebKeyboardService : Service() {

    companion object {
        const val ACTION_START          = "com.offlineinc.dumbdownlauncher.TS_START"
        const val ACTION_STOP           = "com.offlineinc.dumbdownlauncher.TS_STOP"
        const val ACTION_STOP_BROADCAST = "com.offlineinc.dumbdownlauncher.TS_STOP_BROADCAST"
        const val EXTRA_PHONE_NUMBER    = "phoneNumber"

        private const val TAG         = "TypeSyncService"
        private const val CHANNEL_ID  = "type_sync"
        private const val NOTIF_ID    = 9001
        private const val WS_URL      =
            "wss://offline-dc-backend-ba4815b2bcc8.herokuapp.com/keyboard/ws"
        private const val TEN_MINUTES = 10 * 60 * 1000L

        @Volatile var isRunning = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // no read timeout — WS is long-lived
        .pingInterval(20, TimeUnit.SECONDS)      // keep-alive pings
        .build()

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val phone = intent.getStringExtra(EXTRA_PHONE_NUMBER)
                if (phone.isNullOrBlank()) {
                    Log.e(TAG, "ACTION_START missing phone number — stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRelay(phone)
            }
            ACTION_STOP -> shutDown()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "service destroyed")
        webSocket = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Core relay
    // -------------------------------------------------------------------------

    private var reconnectCount = 0

    private fun startRelay(phoneNumber: String) {
        isRunning = true
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        Log.i(TAG, "━━━ startRelay ━━━  phone=$phoneNumber  url=$WS_URL  attempt=$reconnectCount")

        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectCount = 0
                // Normalize to E.164 — TelephonyManager.line1Number omits the leading +
                val e164 = if (phoneNumber.startsWith("+")) phoneNumber else "+$phoneNumber"
                Log.i(TAG, "✅ WS open (HTTP ${response.code}) — sending connect handshake for $e164")
                val handshake = JSONObject().apply {
                    put("type",        "connect")
                    put("role",        "phone")
                    put("phoneNumber", e164)
                }.toString()
                Log.d(TAG, "→ sending: $handshake")
                ws.send(handshake)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "← received: $text")
                try {
                    val msg = JSONObject(text)
                    if (msg.getString("type") == "text") {
                        val incoming = msg.getString("text")
                        Log.i(TAG, "💉 injecting text: \"$incoming\"")
                        MouseAccessibilityService.injectText(incoming)
                    } else {
                        Log.d(TAG, "ignoring message type=${msg.getString("type")}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "failed to parse message: ${e.message}  raw=$text")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val httpInfo = response?.let { "HTTP ${it.code} ${it.message}" } ?: "no HTTP response"
                Log.e(TAG, "❌ WS failure ($httpInfo): ${t::class.simpleName}: ${t.message}")
                t.cause?.let { Log.e(TAG, "  caused by: ${it.message}") }
                reconnectCount++
                if (isRunning) {
                    Log.i(TAG, "🔄 reconnecting in 3s (attempt $reconnectCount)")
                    mainHandler.postDelayed({ startRelay(phoneNumber) }, 3_000)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "🔌 WS closed: code=$code reason=\"$reason\"")
            }
        })

        // 10-minute hard cutoff
        mainHandler.postDelayed({
            Log.i(TAG, "⏱ 10-minute timer expired — stopping Type Sync for $phoneNumber")
            shutDown()
            // Broadcast to AllAppsActivity so it can flip the toggle off
            sendBroadcast(Intent(ACTION_STOP_BROADCAST))
        }, TEN_MINUTES)
    }

    private fun shutDown() {
        // Send explicit disconnect so the iOS app updates immediately, rather
        // than waiting for the server to detect the dropped TCP connection.
        try {
            webSocket?.send(JSONObject().apply {
                put("type", "disconnect")
                put("role", "phone")
            }.toString())
        } catch (e: Exception) {
            Log.w(TAG, "disconnect message failed (ok — close will follow): ${e.message}")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Type Sync",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Type Sync relay is active" }
                )
            }
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Type Sync active")
            .setContentText("Open ur smart phone and go to type sync to type")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, WebKeyboardService::class.java).apply {
                        action = ACTION_STOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

}
