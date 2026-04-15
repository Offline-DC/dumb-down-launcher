package com.offlineinc.dumbdownlauncher.quack

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "BeaconDbClient"

/**
 * Thin client for BeaconDB's geolocate API — a community-run replacement for
 * Mozilla Location Service (which shut down in June 2024). Same JSON schema
 * MLS used; same schema Google's Geolocation API uses, so swapping providers
 * later is a one-line change.
 *
 * Endpoint: https://api.beacondb.net/v1/geolocate
 *
 * Why we need this: phones without Google Play Services have no Network
 * Location Provider. On a cold GPS chip indoors that means *no* location at
 * all. Posting nearby Wi-Fi BSSIDs (and optionally a cell tower) to BeaconDB
 * fills that gap with a coarse fix accurate to ~50–500 m.
 *
 * Privacy note: only BSSIDs and signal strengths are sent — no SSIDs, no MAC
 * of this device, no PII. BeaconDB stores nothing per-request.
 */
object BeaconDbClient {

    private const val ENDPOINT = "https://api.beacondb.net/v1/geolocate"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS    = 5_000

    data class WifiAp(
        val bssid: String,        // colon-separated lowercase MAC, e.g. "aa:bb:cc:dd:ee:ff"
        val signalStrengthDbm: Int,
        val frequencyMhz: Int? = null,
        val signalToNoiseRatio: Int? = null,
    )

    data class CellTower(
        val radioType: String,    // "gsm" | "wcdma" | "lte" | "nr"
        val mobileCountryCode: Int,
        val mobileNetworkCode: Int,
        val locationAreaCode: Int, // TAC for LTE, LAC for GSM/WCDMA
        val cellId: Long,
        val signalStrengthDbm: Int? = null,
    )

    data class Fix(val lat: Double, val lng: Double, val accuracyMeters: Double)

    /**
     * POST a geolocate request and parse the response. Blocking; call from a
     * background thread. Returns null on any failure (network, HTTP non-2xx,
     * malformed JSON, no Wi-Fi APs and no cell towers — BeaconDB requires
     * at least 2 Wi-Fi APs OR 1 cell tower).
     */
    fun geolocate(wifi: List<WifiAp>, cells: List<CellTower>): Fix? {
        if (wifi.size < 2 && cells.isEmpty()) {
            Log.w(TAG, "geolocate: need ≥2 wifi APs or ≥1 cell — got wifi=${wifi.size} cells=${cells.size}")
            return null
        }
        val body = buildRequestBody(wifi, cells)
        Log.d(TAG, "geolocate: POSTing ${wifi.size} wifi + ${cells.size} cells")

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DumbDownLauncher/1.0 (+offline.community)")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Log.w(TAG, "geolocate: HTTP $code body=${text.take(200)}")
                return null
            }
            parseFix(text).also { fix ->
                if (fix != null) {
                    Log.i(TAG, "geolocate: got lat=${fix.lat} lng=${fix.lng} acc=${fix.accuracyMeters}m")
                } else {
                    Log.w(TAG, "geolocate: response had no usable location: ${text.take(200)}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "geolocate: failed — ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun buildRequestBody(wifi: List<WifiAp>, cells: List<CellTower>): String {
        val root = JSONObject()
        if (wifi.isNotEmpty()) {
            val arr = JSONArray()
            for (ap in wifi) {
                val obj = JSONObject()
                    .put("macAddress", ap.bssid)
                    .put("signalStrength", ap.signalStrengthDbm)
                ap.frequencyMhz?.let     { obj.put("frequency", it) }
                ap.signalToNoiseRatio?.let { obj.put("signalToNoiseRatio", it) }
                arr.put(obj)
            }
            root.put("wifiAccessPoints", arr)
        }
        if (cells.isNotEmpty()) {
            // BeaconDB infers radioType from the per-tower field but also
            // accepts a top-level hint. We always send per-tower for clarity.
            val arr = JSONArray()
            for (c in cells) {
                val obj = JSONObject()
                    .put("radioType", c.radioType)
                    .put("mobileCountryCode", c.mobileCountryCode)
                    .put("mobileNetworkCode", c.mobileNetworkCode)
                    .put("locationAreaCode", c.locationAreaCode)
                    .put("cellId", c.cellId)
                c.signalStrengthDbm?.let { obj.put("signalStrength", it) }
                arr.put(obj)
            }
            root.put("cellTowers", arr)
        }
        // Hint to the server that we're OK with low-precision answers. Default
        // would be true anyway; included for explicitness.
        root.put("considerIp", false)
        return root.toString()
    }

    private fun parseFix(json: String): Fix? {
        return try {
            val o = JSONObject(json)
            val loc = o.optJSONObject("location") ?: return null
            val lat = loc.optDouble("lat", Double.NaN)
            val lng = loc.optDouble("lng", Double.NaN)
            val acc = o.optDouble("accuracy", 1000.0)
            if (lat.isNaN() || lng.isNaN()) null else Fix(lat, lng, acc)
        } catch (e: Exception) {
            Log.w(TAG, "parseFix failed: ${e.message}")
            null
        }
    }
}
