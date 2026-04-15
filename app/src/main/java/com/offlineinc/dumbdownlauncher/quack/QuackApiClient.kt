package com.offlineinc.dumbdownlauncher.quack

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client for the Quack anonymous message board API.
 * Talks to the offline-dc-twilio Express backend.
 *
 * Location handling: the dumb-phone client never sends lat/lng. The backend
 * IP-geolocates the caller's egress to determine location — GPS and the
 * network provider are unreliable on these phones (MediaTek builds ship
 * without a network location provider, and GPS cold-starts fail indoors).
 * City-level IP accuracy is plenty for the 25-mile feed radius.
 */
object QuackApiClient {

    private const val BASE = "https://offline-dc-backend-ba4815b2bcc8.herokuapp.com/api/v1/quack"

    class ApiException(val statusCode: Int, message: String) : Exception(message)

    /** Fetch nearby posts. Location is resolved server-side from the caller's IP. */
    fun fetchPosts(): JSONArray {
        val url = URL("$BASE/posts")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout  = 30_000
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try {
                    conn.errorStream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
                } catch (_: Exception) { "" }
                throw ApiException(code, "GET /posts failed ($code): $errBody")
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val body = reader.readText()
            reader.close()
            return JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Create a new post. Location is resolved server-side from the caller's IP.
     * utcOffsetMinutes: device's total UTC offset in minutes (including DST),
     * used by the backend to find when 6am was in the user's local timezone.
     */
    fun createPost(body: String, deviceId: String, phoneNumber: String?, utcOffsetMinutes: Int): JSONObject {
        val url = URL("$BASE/posts")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout  = 30_000
        conn.setRequestProperty("Content-Type", "application/json")

        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("body", body)
            put("utc_offset_minutes", utcOffsetMinutes)
            if (!phoneNumber.isNullOrBlank()) put("phone_number", phoneNumber)
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try {
                    conn.errorStream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
                } catch (_: Exception) { "" }
                throw ApiException(code, "POST /posts failed ($code): $errBody")
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val response = reader.readText()
            reader.close()
            return JSONObject(response)
        } finally {
            conn.disconnect()
        }
    }
}
