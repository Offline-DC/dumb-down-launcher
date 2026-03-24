package com.offlineinc.dumbdownlauncher.update

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
)

object UpdateChecker {

    // Fetch all releases (not just /latest) so we always find the highest version_code,
    // even if GitHub's "latest" pointer doesn't match the newest tag.
    private const val LAUNCHER_API =
        "https://api.github.com/repos/Offline-DC/dumb-down-launcher/releases?per_page=10"
    private const val SNAKE_API =
        "https://api.github.com/repos/Offline-DC/snake/releases?per_page=10"

    fun fetchLatest(): Map<String, AppUpdateInfo> {
        return buildMap {
            fetchHighestRelease(LAUNCHER_API)?.let { put("dumb-down-launcher", it) }
            // Contact sync is now integrated into the launcher — no separate update check needed
            fetchHighestRelease(SNAKE_API)?.let { put("snake", it) }
        }
    }

    /**
     * Fetches recent releases and returns the one with the highest version_code.
     * This avoids relying on GitHub's /latest endpoint which is based on creation
     * date rather than version number — so publishing releases out of order or
     * re-creating releases could cause /latest to point to an older version.
     */
    private fun fetchHighestRelease(apiUrl: String): AppUpdateInfo? {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val releases = JSONArray(conn.inputStream.bufferedReader().readText())

            var best: AppUpdateInfo? = null
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                // Skip drafts and prereleases
                if (release.optBoolean("draft", false)) continue
                if (release.optBoolean("prerelease", false)) continue

                val info = parseRelease(release) ?: continue
                if (best == null || info.versionCode > best.versionCode) {
                    best = info
                }
            }
            best
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): AppUpdateInfo? {
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

        return AppUpdateInfo(versionCode, versionName, downloadUrl)
    }
}
