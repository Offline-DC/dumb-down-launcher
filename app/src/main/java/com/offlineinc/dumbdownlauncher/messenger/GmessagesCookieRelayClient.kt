package com.offlineinc.dumbdownlauncher.messenger

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.offline.dpadmessenger.backend.gmessages.GoogleMessagesAccountStore
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.typesync.TypeSyncCrypto
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Receives the user's Google-account cookies from the companion smartphone over
 * the existing Type Sync encrypted relay, and stores them for Messages-for-web
 * (GAIA / cookie) auth.
 *
 * Reuses the device-link shared secret + the same AES-256-GCM / HMAC handshake
 * as [com.offlineinc.dumbdownlauncher.typesync.TypeSyncService]: we connect as
 * role "phone", and the companion (role "android"/"ios") sends a
 * `gmessages_cookies` message carrying an AES-256-GCM blob the relay forwards
 * without ever seeing plaintext. We decrypt with the shared secret and persist
 * the cookies, then ack.
 *
 * Short-lived: started when the user opens the "sign in with Google account"
 * screen, stopped when they leave or cookies arrive.
 */
class GmessagesCookieRelayClient(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for WS
        .build()
    private var ws: WebSocket? = null
    @Volatile private var finished = false
    @Volatile private var onEmojiCb: (String) -> Unit = {}
    @Volatile private var onCookiesCb: () -> Unit = {}

    /**
     * @param onCookies called (on the main thread) once the login arrives and
     *   Google sign-in + pairing begins — flip the UI from "waiting" to
     *   "signing in".
     * @param onEmoji called (on the main thread) with the verification emoji
     *   during UKey2 pairing — show it so the user can tap the matching one in
     *   Google Messages on their phone.
     * @param onResult (success, message) — always delivered on the main thread.
     */
    fun start(
        onCookies: () -> Unit = {},
        onEmoji: (String) -> Unit = {},
        onResult: (Boolean, String?) -> Unit,
    ) {
        onCookiesCb = onCookies
        onEmojiCb = onEmoji
        val store = PairingStore(appContext)
        val secret = store.sharedSecret
        val phone = store.flipPhoneNumber
        if (secret.isNullOrEmpty() || phone.isNullOrEmpty()) {
            deliver(onResult, false, "this dumb phone isn't linked to ur smart phone yet.")
            return
        }
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val hmac = TypeSyncCrypto.hmacSha256Hex("$phone$timestamp".toByteArray(), secret)
        val request = Request.Builder().url(WS_URL).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val handshake = JSONObject().apply {
                    put("type", "connect")
                    put("role", "phone")
                    put("phoneNumber", phone)
                    put("timestamp", timestamp)
                    put("hmac", hmac)
                }
                webSocket.send(handshake.toString())
                Log.i(TAG, "connected; waiting for cookies from companion")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "gmessages_cookies" -> handleCookies(webSocket, json, secret, onResult)
                        "auth_failed" -> deliver(onResult, false, "Auth failed: ${json.optString("reason")}")
                    }
                }.onFailure { Log.e(TAG, "message handling failed", it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "ws failure: ${t.message}")
                deliver(onResult, false, "Connection failed. Check your internet.")
            }
        })
    }

    private fun handleCookies(
        webSocket: WebSocket,
        json: JSONObject,
        secret: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val cipher = TypeSyncCrypto.fromBase64(json.getString("encrypted"))
        val iv = TypeSyncCrypto.fromBase64(json.getString("iv"))
        val plain = TypeSyncCrypto.decryptAesGcm(cipher, iv, secret)
        val obj = JSONObject(String(plain, Charsets.UTF_8))
        val cookies = HashMap<String, String>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            cookies[k] = obj.getString(k)
        }
        GoogleMessagesAccountStore(appContext).saveCookies(cookies)
        runCatching {
            webSocket.send(JSONObject().put("type", "gmessages_cookies_ack").put("ok", true).toString())
        }
        Log.i(TAG, "saved ${cookies.size} cookies from companion; names=${cookies.keys.sorted()}")
        main.post { onCookiesCb() } // UI: "waiting…" → "signing in…"
        // Phase 2+3: cookie-auth SignInGaia + the UKey2 emoji-match pairing. The
        // emoji is surfaced via onEmojiCb so the UI can show it; the user taps
        // the matching emoji in Google Messages on their phone to confirm. Runs
        // off the WS thread (blocks up to ~2min waiting for the phone). Delivers
        // the final result once pairing succeeds or fails.
        Thread {
            val paired = runCatching {
                com.offline.dpadmessenger.backend.gmessages.GMGaiaClient(appContext).run(
                    onEmoji = { emoji -> main.post { onEmojiCb(emoji) } },
                )
            }.getOrElse { Log.e(TAG, "GMGaia run failed", it); false }
            if (paired) {
                deliver(onResult, true, "connected — ur dumb phone is paired with google messages.")
            } else {
                deliver(
                    onResult, false,
                    "Pairing didn't finish. Make sure you tapped the matching emoji on your phone, then try again.",
                )
            }
        }.start()
    }

    private fun deliver(onResult: (Boolean, String?) -> Unit, ok: Boolean, msg: String?) {
        if (finished) return
        finished = true
        main.post { onResult(ok, msg) }
    }

    fun stop() {
        runCatching { ws?.close(1000, "done") }
        ws = null
    }

    companion object {
        private const val TAG = "GmCookieRelay"
        private const val WS_URL = "wss://offline-dc-backend-ba4815b2bcc8.herokuapp.com/keyboard/ws"
    }
}
