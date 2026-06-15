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
    // OpenBubbles messaging is a fork of upstream OpenBubbles maintained in a
    // dedicated repo so releases are versioned independently of the launcher.
    private const val OPENBUBBLES_API =
        "https://api.github.com/repos/Offline-DC/openbubbles-messaging/releases?per_page=10"

    /**
     * @param betaChannel selects which release channel to consider, and the two
     *   channels are mutually exclusive (a "sandbox" model):
     *   - `false` (stable channel): only stable releases are considered;
     *     prereleases are skipped.
     *   - `true` (beta channel): only prerelease builds are considered (e.g.
     *     those published by the `.github/workflows/beta-release.yml` workflow
     *     for `v*-beta*`/`v*-rc*` tags); stable releases are skipped. This means
     *     a stable/prod release can never overwrite a beta build while the
     *     device is in beta mode, so multiple things can sit in beta
     *     independently of the prod release train.
     *   Beta testers opt in by long-pressing "updates" in AllAppsActivity; see
     *   [com.offlineinc.dumbdownlauncher.pairing.PairingStore.betaTesterMode].
     *   Drafts are always skipped in both channels — they aren't visible to
     *   unauthenticated API calls anyway.
     */
    fun fetchLatest(betaChannel: Boolean = false): Map<String, AppUpdateInfo> {
        return buildMap {
            fetchHighestRelease(LAUNCHER_API, betaChannel)?.let { put("dumb-down-launcher", it) }
            // Contact sync is now integrated into the launcher — no separate update check needed
            fetchHighestRelease(SNAKE_API, betaChannel)?.let { put("snake", it) }
            // OpenBubbles ships as a .zip of split APKs, not a single .apk.
            fetchHighestRelease(
                OPENBUBBLES_API,
                betaChannel,
                assetMatcher = { it.endsWith(".zip") },
            )?.let { put("openbubbles-messaging", it) }
        }
    }

    /**
     * Fetches recent releases and returns the one with the highest version_code.
     * This avoids relying on GitHub's /latest endpoint which is based on creation
     * date rather than version number — so publishing releases out of order or
     * re-creating releases could cause /latest to point to an older version.
     *
     * The two channels are mutually exclusive (sandbox model). When
     * [betaChannel] is false we only look at stable releases; when it is true we
     * only look at prerelease builds. Within the selected channel the highest
     * version_code wins. Crucially, because stable and beta no longer compete,
     * a prod release can't clobber a beta build a device is currently pinned to,
     * and several features can sit in beta at once without the prod release
     * train pulling testers back onto stable.
     *
     * Throws on network/IO failure (DNS lookup, connect refused, read timeout,
     * TLS error, etc.) — the previous behaviour swallowed those into a `null`
     * return, which the manual-tap path in AllAppsActivity then surfaced as
     * "Already up to date" even when the phone had no cellular service.
     * Callers that don't care to distinguish (the periodic workers) already
     * catch Exception and treat it as a retry.
     *
     * Non-200 responses (rate limit, server error) still return `null` — those
     * aren't connectivity issues and the caller may continue with a different
     * API in the same pass instead of bailing on the first 429.
     */
    private fun fetchHighestRelease(
        apiUrl: String,
        betaChannel: Boolean,
        assetMatcher: (String) -> Boolean = { it.endsWith(".apk") },
    ): AppUpdateInfo? {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        // Bypass GitHub CDN cache so we always see the freshest release list
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("If-None-Match", "")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val releases = JSONArray(conn.inputStream.bufferedReader().readText())

            var best: AppUpdateInfo? = null
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                // Always skip drafts
                if (release.optBoolean("draft", false)) continue
                // Sandbox the two channels: beta mode only sees prereleases,
                // stable mode only sees non-prereleases. This keeps prod
                // releases from overwriting beta builds (and vice versa).
                val isPrerelease = release.optBoolean("prerelease", false)
                if (betaChannel != isPrerelease) continue

                val info = parseRelease(release, assetMatcher) ?: continue
                if (best == null || info.versionCode > best.versionCode) {
                    best = info
                }
            }
            return best
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject, assetMatcher: (String) -> Boolean): AppUpdateInfo? {
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
                assetMatcher(it.getString("name")) &&
                it.optString("state", "uploaded") == "uploaded"
            }
            ?.getString("browser_download_url") ?: return null

        return AppUpdateInfo(versionCode, versionName, downloadUrl)
    }
}
