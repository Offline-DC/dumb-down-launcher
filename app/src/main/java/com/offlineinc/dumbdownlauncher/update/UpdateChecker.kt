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

    /**
     * @param includePrereleases when true, also considers GitHub releases
     *   flagged `prerelease=true` (e.g. those published by the
     *   `.github/workflows/beta-release.yml` workflow for `v*-beta*`/`v*-rc*`
     *   tags). Beta testers opt in by long-pressing "updates" in
     *   AllAppsActivity; see [com.offlineinc.dumbdownlauncher.pairing.PairingStore.betaTesterMode].
     *   Drafts are still always skipped — they aren't visible to unauthenticated
     *   API calls anyway.
     */
    fun fetchLatest(includePrereleases: Boolean = false): Map<String, AppUpdateInfo> {
        return buildMap {
            fetchHighestRelease(LAUNCHER_API, includePrereleases)?.let { put("dumb-down-launcher", it) }
            // Contact sync is now integrated into the launcher — no separate update check needed
            fetchHighestRelease(SNAKE_API, includePrereleases)?.let { put("snake", it) }
        }
    }

    /**
     * Fetches recent releases and returns the one with the highest version_code.
     * This avoids relying on GitHub's /latest endpoint which is based on creation
     * date rather than version number — so publishing releases out of order or
     * re-creating releases could cause /latest to point to an older version.
     *
     * When [includePrereleases] is true, prerelease builds (the beta channel)
     * compete with stable ones on version_code alone. Because we publish beta
     * + stable releases against a monotonically increasing version_code, the
     * "highest wins" rule continues to do the right thing in both directions:
     * a stable release with a higher code supersedes a beta, and vice versa.
     */
    private fun fetchHighestRelease(apiUrl: String, includePrereleases: Boolean): AppUpdateInfo? {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        // Bypass GitHub CDN cache so we always see the freshest release list
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("If-None-Match", "")
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val releases = JSONArray(conn.inputStream.bufferedReader().readText())

            var best: AppUpdateInfo? = null
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                // Always skip drafts
                if (release.optBoolean("draft", false)) continue
                // Skip prereleases unless the caller opted in (beta tester mode)
                if (!includePrereleases && release.optBoolean("prerelease", false)) continue

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
            .firstOrNull {
                it.getString("name").endsWith(".apk") &&
                it.optString("state", "uploaded") == "uploaded"
            }
            ?.getString("browser_download_url") ?: return null

        return AppUpdateInfo(versionCode, versionName, downloadUrl)
    }
}
