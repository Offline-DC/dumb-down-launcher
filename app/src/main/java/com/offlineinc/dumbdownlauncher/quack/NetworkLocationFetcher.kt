package com.offlineinc.dumbdownlauncher.quack

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "NetLocFetcher"

/**
 * Gathers nearby Wi-Fi APs + the serving cell tower(s) and resolves them to
 * a coarse coordinate via [BeaconDbClient].
 *
 * This is the *primary* location source on this device because:
 *  - The MediaTek/TCL build has no Network Location Provider (no Play
 *    Services, no com.android.location.fused backing).
 *  - The GPS chip needs many minutes outdoors with clear sky to deliver a
 *    cold-start fix; indoors it never delivers.
 *  - Wi-Fi scans return BSSIDs from APs the user is *next to* — works
 *    perfectly indoors and resolves to ~50 m.
 *
 * Blocking. Designed to be called from a background thread. Total worst-case
 * runtime is roughly: WIFI_SCAN_TIMEOUT_MS (~10 s) + BeaconDbClient HTTP
 * timeout (~10 s). Returns null if no APs/cells were available, the scan
 * failed, or BeaconDB couldn't resolve the request.
 */
object NetworkLocationFetcher {

    /** How long to wait for WifiManager.startScan() to broadcast results. */
    private const val WIFI_SCAN_TIMEOUT_MS = 10_000L

    /**
     * Run a Wi-Fi + cell scan and resolve via BeaconDB. Returns lat/lng/accuracy
     * on success, null on any failure.
     */
    fun fetch(context: Context): BeaconDbClient.Fix? {
        val ctx = context.applicationContext
        if (!hasLocationPermission(ctx)) {
            Log.w(TAG, "fetch: no location permission — required for SSID scan")
            return null
        }

        val wifi = scanWifi(ctx)
        val cells = scanCells(ctx)
        Log.d(TAG, "fetch: gathered ${wifi.size} wifi APs, ${cells.size} cell towers")

        if (wifi.size < 2 && cells.isEmpty()) {
            Log.w(TAG, "fetch: insufficient signals (need ≥2 wifi or ≥1 cell)")
            return null
        }
        return BeaconDbClient.geolocate(wifi, cells)
    }

    // ─── Wi-Fi ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun scanWifi(ctx: Context): List<BeaconDbClient.WifiAp> {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        if (!wm.isWifiEnabled) {
            Log.i(TAG, "scanWifi: Wi-Fi disabled — using whatever cached scanResults exist")
            return mapResults(wm.scanResults ?: emptyList())
        }

        // Subscribe to the SCAN_RESULTS broadcast *before* triggering the scan
        // so we don't race the system. On Android 9+ scan throttling caps this
        // to 4/2min for foreground apps; the receiver still fires immediately
        // with cached results when throttled, so we get something either way.
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                Log.d(TAG, "scanWifi: SCAN_RESULTS_AVAILABLE broadcast received")
                latch.countDown()
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        val triggered = try { wm.startScan() } catch (e: Exception) {
            Log.w(TAG, "scanWifi: startScan threw — ${e.message}")
            false
        }
        if (!triggered) Log.w(TAG, "scanWifi: startScan returned false (probably throttled) — using cached results")

        try {
            // If the scan was throttled startScan returns false but the receiver
            // never fires; fall through after the timeout and use whatever
            // cached results are in scanResults.
            latch.await(WIFI_SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }

        val results = try { wm.scanResults ?: emptyList() } catch (e: Exception) {
            Log.w(TAG, "scanWifi: getScanResults failed — ${e.message}")
            emptyList()
        }
        return mapResults(results)
    }

    private fun mapResults(results: List<ScanResult>): List<BeaconDbClient.WifiAp> {
        val now = System.currentTimeMillis()
        return results.asSequence()
            // Skip APs whose owner asked to be excluded from geolocation
            // databases (RFC 7282 — SSID ending in "_nomap").
            .filter { it.SSID == null || !it.SSID.endsWith("_nomap", ignoreCase = true) }
            // Skip APs broadcasting random / null BSSIDs.
            .filter { it.BSSID != null && !it.BSSID.startsWith("00:00:00") }
            // Drop very stale results (>2 min) — not useful for current location.
            .filter {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    val ageMs = (System.nanoTime() - it.timestamp * 1000L) / 1_000_000L
                    ageMs in 0..120_000L
                } else true
            }
            // BeaconDB caps how many APs it'll consider per request; sending
            // too many wastes bandwidth. 25 strongest is plenty.
            .sortedByDescending { it.level }
            .take(25)
            .map {
                BeaconDbClient.WifiAp(
                    bssid = it.BSSID.lowercase(),
                    signalStrengthDbm = it.level,
                    frequencyMhz = it.frequency,
                )
            }
            .toList()
            .also { Log.d(TAG, "scanWifi: ${results.size} raw → ${it.size} eligible (now=$now)") }
    }

    // ─── Cell ───────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun scanCells(ctx: Context): List<BeaconDbClient.CellTower> {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return emptyList()
        val all = try { tm.allCellInfo ?: emptyList() } catch (e: Exception) {
            Log.w(TAG, "scanCells: getAllCellInfo failed — ${e.message}")
            return emptyList()
        }
        return all.mapNotNull { mapCell(it) }
            .also { Log.d(TAG, "scanCells: ${all.size} raw → ${it.size} usable") }
    }

    private fun mapCell(c: CellInfo): BeaconDbClient.CellTower? = try {
        when (c) {
            is CellInfoLte -> {
                val id = c.cellIdentity
                val mcc = id.mccString?.toIntOrNull() ?: id.mcc.takeIf { it != Int.MAX_VALUE }
                val mnc = id.mncString?.toIntOrNull() ?: id.mnc.takeIf { it != Int.MAX_VALUE }
                val tac = id.tac.takeIf { it != Int.MAX_VALUE }
                val ci  = id.ci.takeIf  { it != Int.MAX_VALUE }
                if (mcc == null || mnc == null || tac == null || ci == null) null
                else BeaconDbClient.CellTower(
                    radioType = "lte",
                    mobileCountryCode = mcc,
                    mobileNetworkCode = mnc,
                    locationAreaCode = tac,
                    cellId = ci.toLong(),
                    signalStrengthDbm = c.cellSignalStrength.dbm.takeIf { it != Int.MAX_VALUE },
                )
            }
            is CellInfoGsm -> {
                val id = c.cellIdentity
                val mcc = id.mccString?.toIntOrNull() ?: id.mcc.takeIf { it != Int.MAX_VALUE }
                val mnc = id.mncString?.toIntOrNull() ?: id.mnc.takeIf { it != Int.MAX_VALUE }
                val lac = id.lac.takeIf { it != Int.MAX_VALUE }
                val cid = id.cid.takeIf { it != Int.MAX_VALUE }
                if (mcc == null || mnc == null || lac == null || cid == null) null
                else BeaconDbClient.CellTower(
                    radioType = "gsm",
                    mobileCountryCode = mcc,
                    mobileNetworkCode = mnc,
                    locationAreaCode = lac,
                    cellId = cid.toLong(),
                    signalStrengthDbm = c.cellSignalStrength.dbm.takeIf { it != Int.MAX_VALUE },
                )
            }
            is CellInfoWcdma -> {
                val id = c.cellIdentity
                val mcc = id.mccString?.toIntOrNull() ?: id.mcc.takeIf { it != Int.MAX_VALUE }
                val mnc = id.mncString?.toIntOrNull() ?: id.mnc.takeIf { it != Int.MAX_VALUE }
                val lac = id.lac.takeIf { it != Int.MAX_VALUE }
                val cid = id.cid.takeIf { it != Int.MAX_VALUE }
                if (mcc == null || mnc == null || lac == null || cid == null) null
                else BeaconDbClient.CellTower(
                    radioType = "wcdma",
                    mobileCountryCode = mcc,
                    mobileNetworkCode = mnc,
                    locationAreaCode = lac,
                    cellId = cid.toLong(),
                    signalStrengthDbm = c.cellSignalStrength.dbm.takeIf { it != Int.MAX_VALUE },
                )
            }
            is CellInfoNr -> {
                val id = c.cellIdentity as? android.telephony.CellIdentityNr ?: return null
                val mcc = id.mccString?.toIntOrNull()
                val mnc = id.mncString?.toIntOrNull()
                val tac = id.tac.takeIf { it != Int.MAX_VALUE }
                val nci = id.nci.takeIf { it != Long.MAX_VALUE }
                if (mcc == null || mnc == null || tac == null || nci == null) null
                else BeaconDbClient.CellTower(
                    radioType = "nr",
                    mobileCountryCode = mcc,
                    mobileNetworkCode = mnc,
                    locationAreaCode = tac,
                    cellId = nci,
                    signalStrengthDbm = (c.cellSignalStrength as? CellSignalStrengthNr)?.dbm
                        ?.takeIf { it != Int.MAX_VALUE },
                )
            }
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "mapCell failed: ${e.message}")
        null
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        return coarse == PackageManager.PERMISSION_GRANTED || fine == PackageManager.PERMISSION_GRANTED
    }
}
