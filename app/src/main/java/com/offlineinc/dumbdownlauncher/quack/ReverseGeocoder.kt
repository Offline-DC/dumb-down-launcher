package com.offlineinc.dumbdownlauncher.quack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns a (lat, lng) into a short, human-readable place label like
 * "Brooklyn, NY" so the user can sanity-check that weather / quack picked
 * up roughly the right location.
 *
 * Why BigDataCloud's reverse-geocode-client endpoint?
 *  - Android's [android.location.Geocoder] needs a geocoder *backend*
 *    service, which these MediaTek/TCL flip builds don't ship (same root
 *    cause as the missing Network Location Provider — see
 *    [NetworkLocationFetcher]). Geocoder.isPresent() returns false here.
 *  - Open-Meteo (our weather provider) only does *forward* geocoding.
 *  - BigDataCloud's `-client` endpoint is keyless, returns plain JSON over
 *    a single GET, and mirrors how we already call Open-Meteo.
 *
 * The result is intentionally coarse — Wi-Fi/BeaconDB fixes are ~50 m and
 * GPS cold-starts coarser — so callers should present it as "near <place>",
 * never as an exact address.
 *
 * Results are cached in SharedPreferences keyed by coordinates rounded to
 * ~1 km, so reopening a screen is instant and works offline (a cache hit
 * never hits the network). On a fetch failure we show nothing rather than a
 * stale label — better an absent location than a wrong one.
 */
object ReverseGeocoder {

    private const val TAG = "ReverseGeocoder"
    private const val PREFS = "reverse_geocode"
    private const val KEY_CACHE_KEY = "cache_key"
    private const val KEY_NAME = "place_name"

    /**
     * Resolve a short place label for [lat]/[lng], or null if it can't be
     * determined and nothing is cached. Safe to call from any coroutine —
     * the network hop runs on [Dispatchers.IO]. Never throws.
     */
    suspend fun resolve(context: Context, lat: Double, lng: Double): String? {
        val key = cacheKey(lat, lng)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Cache hit for this ~1 km cell — return instantly, no network.
        if (prefs.getString(KEY_CACHE_KEY, null) == key) {
            return prefs.getString(KEY_NAME, null)
        }

        val fetched = try {
            withContext(Dispatchers.IO) { fetch(lat, lng) }
        } catch (e: Exception) {
            Log.w(TAG, "resolve: lookup failed", e)
            null
        }

        if (fetched != null) {
            prefs.edit()
                .putString(KEY_CACHE_KEY, key)
                .putString(KEY_NAME, fetched)
                .apply()
            return fetched
        }

        // Lookup failed (no network, HTTP error, fair-use 402, bad payload).
        // Return null so callers show *no* location rather than risk showing a
        // stale label for somewhere the user may no longer be. A cache hit
        // above still works offline; this only affects genuine fetch failures.
        return null
    }

    /**
     * Drop the cached label so the next [resolve] re-fetches from scratch.
     * Used by the quack "refresh" button, which forces a fully fresh fix.
     */
    fun invalidate(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /** Round to 2 dp (~1.1 km) so nearby fixes share one cache entry. */
    private fun cacheKey(lat: Double, lng: Double): String =
        "%.2f,%.2f".format(lat, lng)

    private fun fetch(lat: Double, lng: Double): String? {
        val urlStr = "https://api.bigdatacloud.net/data/reverse-geocode-client" +
            "?latitude=$lat&longitude=$lng&localityLanguage=en"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "fetch: HTTP $code")
                return null
            }
            val json = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            return parse(json)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Build "City, ST" from the BigDataCloud payload. Falls back through
     * locality → principalSubdivision, and drops the region if absent.
     */
    private fun parse(json: String): String? {
        val root = JSONObject(json)
        val city = root.optString("city").trim()
            .ifEmpty { root.optString("locality").trim() }
        val region = regionAbbrev(root)

        return when {
            city.isNotEmpty() && region.isNotEmpty() -> "$city, $region"
            city.isNotEmpty() -> city
            region.isNotEmpty() -> region
            else -> root.optString("principalSubdivision").trim().ifEmpty { null }
        }
    }

    /**
     * Prefer a short subdivision code (e.g. "US-NY" → "NY"); otherwise use
     * the full subdivision name (e.g. "Bavaria"). Returns "" when neither
     * is present.
     */
    private fun regionAbbrev(root: JSONObject): String {
        val code = root.optString("principalSubdivisionCode").trim()
        if (code.contains("-")) {
            val tail = code.substringAfterLast("-")
            if (tail.isNotEmpty()) return tail
        }
        return root.optString("principalSubdivision").trim()
    }
}
