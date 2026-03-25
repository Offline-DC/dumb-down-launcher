package com.offlineinc.dumbdownlauncher.contactsync.sync

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class ContactSyncApiClient(private val httpClient: OkHttpClient) {
    companion object {
        private const val TAG = "ContactSyncAPI"
        private const val BASE_URL = "https://offline-dc-backend-ba4815b2bcc8.herokuapp.com/contact-sync"
        private const val WS_URL = "wss://offline-dc-backend-ba4815b2bcc8.herokuapp.com/contact-sync/ws"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    fun confirmPairing(pairingCode: String, flipPhoneNumber: String, flipLauncherVersion: String? = null): JSONObject {
        Log.i(TAG, "[ContactSync] confirmPairing: code=$pairingCode, phone=$flipPhoneNumber, version=$flipLauncherVersion")
        val body = JSONObject()
            .put("pairingCode", pairingCode)
            .put("flipPhoneNumber", flipPhoneNumber)
        if (flipLauncherVersion != null) {
            body.put("flipLauncherVersion", flipLauncherVersion)
        }
        val request = Request.Builder()
            .url("$BASE_URL/pairing/confirm")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val result = executeJson(request)
        Log.i(TAG, "[ContactSync] confirmPairing: success — pairingId=${result.optInt("pairingId")}")
        return result
    }

    fun unpair(flipPhoneNumber: String, sharedSecret: String): JSONObject {
        Log.i(TAG, "[ContactSync] unpair: phone=$flipPhoneNumber")
        val body = JSONObject().put("flipPhoneNumber", flipPhoneNumber)
        val hmac = CryptoUtil.hmacSha256Hex(body.toString().toByteArray(), sharedSecret)
        val request = Request.Builder()
            .url("$BASE_URL/pairing")
            .delete(body.toString().toRequestBody(JSON_TYPE))
            .addHeader("X-Auth-HMAC", hmac)
            .build()
        val result = executeJson(request)
        Log.i(TAG, "[ContactSync] unpair: success")
        return result
    }

    fun upload(flipPhoneNumber: String, source: String, encryptedVcf: String, iv: String, contentHash: String, contactCount: Int, sharedSecret: String, flipLauncherVersion: String? = null): JSONObject {
        val vcfLen = encryptedVcf.length
        Log.i(TAG, "[ContactSync] upload: source=$source, hash=${contentHash.take(12)}..., contacts=$contactCount, vcfLen=$vcfLen")
        val body = JSONObject()
            .put("flipPhoneNumber", flipPhoneNumber)
            .put("source", source)
            .put("encryptedVcf", encryptedVcf)
            .put("iv", iv)
            .put("contentHash", contentHash)
            .put("contactCount", contactCount)
        if (flipLauncherVersion != null) {
            body.put("flipLauncherVersion", flipLauncherVersion)
        }
        val hmac = CryptoUtil.hmacSha256Hex(body.toString().toByteArray(), sharedSecret)
        Log.d(TAG, "[ContactSync] upload: bodySize=${body.toString().length} bytes, hmac=${hmac.take(12)}...")
        val request = Request.Builder()
            .url("$BASE_URL/upload")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .addHeader("X-Auth-HMAC", hmac)
            .build()
        val result = executeJson(request)
        val hashChanged = result.optBoolean("hashChanged", false)
        Log.i(TAG, "[ContactSync] upload: success — hashChanged=$hashChanged")
        return result
    }

    fun download(flipPhoneNumber: String, source: String, lastKnownHash: String?, sharedSecret: String): JSONObject {
        Log.i(TAG, "[ContactSync] download: source=$source, lastHash=${lastKnownHash?.take(12) ?: "nil"}")
        val queryString = "flipPhoneNumber=${java.net.URLEncoder.encode(flipPhoneNumber, "UTF-8")}&source=$source&lastKnownHash=${lastKnownHash ?: ""}"
        val hmac = CryptoUtil.hmacSha256Hex(
            "flipPhoneNumber=$flipPhoneNumber&source=$source&lastKnownHash=${lastKnownHash ?: ""}".toByteArray(),
            sharedSecret
        )
        val request = Request.Builder()
            .url("$BASE_URL/download?$queryString")
            .get()
            .addHeader("X-Auth-HMAC", hmac)
            .build()
        val result = executeJson(request)
        val hasChanges = result.optBoolean("hasChanges", false)
        val contactCount = result.optInt("contactCount", 0)
        Log.i(TAG, "[ContactSync] download: hasChanges=$hasChanges, contactCount=$contactCount")
        return result
    }

    /**
     * Connect to sync WebSocket and wait for "both_ready" signal.
     */
    fun connectSyncWebSocket(
        flipPhoneNumber: String,
        onBothReady: () -> Unit,
        onPeerComplete: () -> Unit,
        onPeerDisconnected: () -> Unit,
        onPairingDeactivated: () -> Unit = {},
        onError: (String) -> Unit
    ): WebSocket {
        Log.i(TAG, "[ContactSync] WS: connecting to $WS_URL")
        val request = Request.Builder().url(WS_URL).build()
        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "[ContactSync] WS: opened — sending connect as role=flip, phone=$flipPhoneNumber")
                val connectMsg = JSONObject()
                    .put("type", "connect")
                    .put("role", "flip")
                    .put("flipPhoneNumber", flipPhoneNumber)
                webSocket.send(connectMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val type = msg.optString("type")
                    Log.i(TAG, "[ContactSync] WS: received — type=$type")
                    when (type) {
                        "both_ready" -> onBothReady()
                        "peer_sync_complete" -> onPeerComplete()
                        "peer_disconnected" -> onPeerDisconnected()
                        "pairing_deactivated" -> onPairingDeactivated()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[ContactSync] WS: failed to parse message: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "[ContactSync] WS: failure — ${t.message}", t)
                onError(t.message ?: "WebSocket error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "[ContactSync] WS: closed — code=$code, reason=$reason")
                if (reason == "timeout") onError("Connection timed out")
            }
        })
        return ws
    }

    private fun executeJson(request: Request): JSONObject {
        Log.d(TAG, "[ContactSync] HTTP ${request.method} ${request.url}")
        val response = httpClient.newCall(request).execute()
        val bodyStr = response.body?.string() ?: "{}"
        if (!response.isSuccessful) {
            val error = try { JSONObject(bodyStr).optString("error", "Request failed") } catch (_: Exception) { "Request failed: ${response.code}" }
            Log.e(TAG, "[ContactSync] HTTP ${request.method} ${request.url}: ${response.code} — $error")
            throw IOException(error)
        }
        Log.d(TAG, "[ContactSync] HTTP ${request.method} ${request.url}: ${response.code} OK")
        return JSONObject(bodyStr)
    }
}
