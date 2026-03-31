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
 */
object QuackApiClient {

    private const val BASE = "https://offline-dc-backend-ba4815b2bcc8.herokuapp.com/api/v1/quack"

    class ApiException(val statusCode: Int, message: String) : Exception(message)

    /** Fetch nearby posts. Returns a JSONArray of post objects. */
    fun fetchPosts(lat: Double, lng: Double, radiusKm: Int = 5): JSONArray {
        val url = URL("$BASE/posts?lat=$lat&lng=$lng&radius=$radiusKm")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
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

    /** Create a new post. Returns the created post as a JSONObject. */
    fun createPost(body: String, lat: Double, lng: Double, deviceId: String, phoneNumber: String?): JSONObject {
        val url = URL("$BASE/posts")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json")

        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("body", body)
            put("lat", lat)
            put("lng", lng)
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
