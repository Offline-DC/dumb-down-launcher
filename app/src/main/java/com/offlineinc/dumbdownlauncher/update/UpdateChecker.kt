package com.offlineinc.dumbdownlauncher.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val notes: String,
)

object UpdateChecker {

    private const val APPS_JSON_URL = "https://dumb.co/downloads/apps.json"

    fun fetchLatest(): Map<String, AppUpdateInfo> {
        val conn = URL(APPS_JSON_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return emptyMap()
            val body = conn.inputStream.bufferedReader().readText()
            val apps = JSONObject(body).getJSONObject("apps")
            return buildMap {
                for (key in apps.keys()) {
                    val obj = apps.getJSONObject(key)
                    put(
                        key,
                        AppUpdateInfo(
                            versionCode = obj.getInt("version_code"),
                            versionName = obj.getString("latest_version"),
                            downloadUrl = obj.getString("download_url"),
                            notes = obj.optString("notes", ""),
                        )
                    )
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
