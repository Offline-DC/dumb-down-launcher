package com.offlineinc.dumbdownlauncher.quack

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal HTTP client for the Quack anonymous message board API.
 * Talks to the offline-dc-twilio Express backend.
 *
 * Location handling: the client sends coarse cell-tower location on every
 * request. The backend no longer falls back to IP geolocation — cellular
 * carriers CGNAT phones through a handful of regional POPs (e.g. T-Mobile
 * routes DC traffic through Atlanta), so IP geoloc placed phones hundreds of
 * miles from their real location. If the client can't get a fix, the server
 * returns an empty feed rather than a wrong one.
 */
object QuackApiClient {

    private const val BASE = "https://offline-dc-backend-ba4815b2bcc8.herokuapp.com/api/v1/quack"

    class ApiException(val statusCode: Int, message: String) : Exception(message)

    /** Fetch nearby posts. Passes coarse lat/lng for server-side radius filtering. */
    fun fetchPosts(lat: Double?, lng: Double?): JSONArray {
        val query = buildString {
            if (lat != null && lng != null) {
                append("?lat=")
                append(URLEncoder.encode(lat.toString(), "UTF-8"))
                append("&lng=")
                append(URLEncoder.encode(lng.toString(), "UTF-8"))
            }
        }
        val url = URL("$BASE/posts$query")
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
     * Create a new post. Sends coarse lat/lng (required — server rejects posts
     * without a location).
     *
     * utcOffsetMinutes: device's total UTC offset in minutes (including DST),
     * used by the backend to find when 6am was in the user's local timezone.
     */
    fun createPost(
        body: String,
        deviceId: String,
        phoneNumber: String?,
        utcOffsetMinutes: Int,
        lat: Double?,
        lng: Double?,
    ): JSONObject {
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
            if (lat != null) put("lat", lat)
            if (lng != null) put("lng", lng)
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
