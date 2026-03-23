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
    fun confirmPairing(pairingCode: String, flipPhoneNumber: String): JSONObject {
        Log.i(TAG, "confirmPairing: code=$pairingCode, phone=$flipPhoneNumber")
        val body = JSONObject()
            .put("pairingCode", pairingCode)
            .put("flipPhoneNumber", flipPhoneNumber)
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
