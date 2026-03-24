package com.offlineinc.dumbdownlauncher.pairing

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Minimal API client for the pairing confirm endpoint.
 * Only used during onboarding to pair the flip phone with the smartphone.
 */
class PairingApiClient(private val httpClient: OkHttpClient) {
    companion object {
        private const val TAG = "PairingAPI"
        private const val BASE_URL = "https://offline-dc-backend-ba4815b2bcc8.herokuapp.com/contact-sync"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    /**
     * Confirms a 4-digit pairing code and returns the server response
     * containing sharedSecret and pairingId.
     */
    fun confirmPairing(pairingCode: String, flipPhoneNumber: String, flipLauncherVersion: String? = null): JSONObject {
        Log.i(TAG, "confirmPairing: code=$pairingCode, phone=$flipPhoneNumber, version=$flipLauncherVersion")
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

        Log.d(TAG, "HTTP ${request.method} ${request.url}")
        val response = httpClient.newCall(request).execute()
        val bodyStr = response.body?.string() ?: "{}"
        if (!response.isSuccessful) {
            val error = try {
                JSONObject(bodyStr).optString("error", "Request failed")
            } catch (_: Exception) {
                "Request failed: ${response.code}"
            }
            Log.e(TAG, "HTTP ${request.method} ${request.url}: ${response.code} — $error")
            throw IOException(error)
        }
        val result = JSONObject(bodyStr)
        Log.i(TAG, "confirmPairing: success — pairingId=${result.optInt("pairingId")}")
        return result
    }

    /**
     * Reports the current launcher version to the server so the smart phone
     * companion app knows which features are available.
     * Called once after each launcher update.
     */
    fun reportVersion(flipPhoneNumber: String, flipLauncherVersion: String, sharedSecret: String) {
        Log.i(TAG, "reportVersion: phone=$flipPhoneNumber, version=$flipLauncherVersion")
        val body = JSONObject()
            .put("flipPhoneNumber", flipPhoneNumber)
            .put("flipLauncherVersion", flipLauncherVersion)
        val hmac = com.offlineinc.dumbdownlauncher.contactsync.sync.CryptoUtil.hmacSha256Hex(
            body.toString().toByteArray(), sharedSecret
        )
        val request = Request.Builder()
            .url("$BASE_URL/report-version")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("X-Auth-HMAC", hmac)
            .build()

        Log.d(TAG, "HTTP ${request.method} ${request.url}")
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e(TAG, "reportVersion: HTTP ${response.code}")
            throw IOException("Report version failed: ${response.code}")
        }
        Log.i(TAG, "reportVersion: success")
    }

    /**
     * Fetches the pairing status from the server for a given flip phone number.
     * Returns the JSON response which includes smartPlatform when paired.
     */
    fun getPairingStatus(flipPhoneNumber: String): JSONObject {
        val encoded = java.net.URLEncoder.encode(flipPhoneNumber, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL/pairing/status?flipPhoneNumber=$encoded")
            .get()
            .build()

        Log.d(TAG, "HTTP ${request.method} ${request.url}")
        val response = httpClient.newCall(request).execute()
        val bodyStr = response.body?.string() ?: "{}"
        if (!response.isSuccessful) {
            Log.e(TAG, "getPairingStatus: HTTP ${response.code}")
            throw IOException("Request failed: ${response.code}")
        }
        return JSONObject(bodyStr)
    }
}
