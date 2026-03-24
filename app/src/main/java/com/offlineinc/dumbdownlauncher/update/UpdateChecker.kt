package com.offlineinc.dumbdownlauncher.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
)

object UpdateChecker {

    private const val LAUNCHER_API =
        "https://api.github.com/repos/Offline-DC/dumb-down-launcher/releases/latest"
    private const val SNAKE_API =
        "https://api.github.com/repos/Offline-DC/snake/releases/latest"

    fun fetchLatest(): Map<String, AppUpdateInfo> {
        return buildMap {
            fetchRelease(LAUNCHER_API, "dumb-down-launcher")?.let { put("dumb-down-launcher", it) }
            // Contact sync is now integrated into the launcher — no separate update check needed
            fetchRelease(SNAKE_API, "snake")?.let { put("snake", it) }
        }
    }

    private fun fetchRelease(apiUrl: String, apkFileName: String): AppUpdateInfo? {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())

            val tagName = json.getString("tag_name")
            val versionName = tagName.trimStart('v')
            val body = json.optString("body", "")
            val versionCode = body.lines()
                .firstOrNull { it.startsWith("version_code=") }
                ?.removePrefix("version_code=")
                ?.trim()
                ?.toIntOrNull() ?: return null

            val assets = json.getJSONArray("assets")
            val downloadUrl = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?.getString("browser_download_url") ?: return null

            AppUpdateInfo(versionCode, versionName, downloadUrl)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
