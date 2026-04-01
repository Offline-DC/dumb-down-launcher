package com.offlineinc.dumbdownlauncher.quack

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Gets a single location fix using GPS, falling back to network provider.
 * Designed for flip phones: requests one fix then immediately removes updates
 * to preserve battery. Calls back on the main thread.
 *
 * Two-stage timeout strategy:
 *  1. QUICK_TIMEOUT_MS — if a stale cached location exists, deliver it early
 *     so the UI isn't blocked, but keep listening for a fresh fix.
 *  2. HARD_TIMEOUT_MS — stop listening entirely and fall back to whatever
 *     cached location is available (any age). Only errors if there is truly
 *     no cached location at all.
 */
class QuackLocationHelper(context: Context, private val callback: Callback) {

    interface Callback {
        fun onLocation(lat: Double, lng: Double)
        fun onError(reason: String)
    }

    companion object {
        private const val TAG = "QuackLocation"
        private const val MIN_TIME_MS = 0L
        private const val MIN_DIST_M = 0f
        // For a 25-mile feed radius, a 30-min-old location is plenty accurate
        private const val CACHE_MAX_AGE_MS = 30 * 60 * 1000L
        /** First stage: deliver a stale cached location quickly so the UI isn't stuck waiting. */
        private const val QUICK_TIMEOUT_MS = 5_000L           // 5 seconds
        /** Second stage: give up on a fresh fix entirely. */
        private const val HARD_TIMEOUT_MS = 30_000L           // 30 seconds
    }

    private val appContext: Context = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var delivered = false

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            Log.d(TAG, "GPS fix received: acc=${loc.accuracy}m age=${(System.currentTimeMillis() - loc.time) / 1000}s")
            deliver(loc)
        }
        override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {
            Log.d(TAG, "GPS status changed: provider=$p status=$s")
        }
        override fun onProviderEnabled(p: String) { Log.d(TAG, "Provider enabled: $p") }
        override fun onProviderDisabled(p: String) { Log.w(TAG, "Provider disabled: $p") }
    }

    private val netListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            Log.d(TAG, "Network fix received: acc=${loc.accuracy}m age=${(System.currentTimeMillis() - loc.time) / 1000}s")
            deliver(loc)
        }
        override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {
            Log.d(TAG, "Network status changed: provider=$p status=$s")
        }
        override fun onProviderEnabled(p: String) { Log.d(TAG, "Provider enabled: $p") }
        override fun onProviderDisabled(p: String) { Log.w(TAG, "Provider disabled: $p") }
    }

    private val passiveListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            Log.d(TAG, "Passive fix received: acc=${loc.accuracy}m age=${(System.currentTimeMillis() - loc.time) / 1000}s provider=${loc.provider}")
            deliver(loc)
        }
        override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    @SuppressLint("MissingPermission")
    fun request() {
        Log.d(TAG, "request() called")

        // Accept any cached location under 30 min — 25-mile radius doesn't need fresh GPS
        val last = bestLastKnown()
        if (last != null) {
            val ageMin = (System.currentTimeMillis() - last.time) / 60_000
            Log.d(TAG, "Best cached location: acc=${last.accuracy}m age=${ageMin}min provider=${last.provider}")
        } else {
            Log.w(TAG, "No cached location available at all")
        }

        if (last != null && last.time > System.currentTimeMillis() - CACHE_MAX_AGE_MS) {
            Log.d(TAG, "Using fresh cached location (age < 30min)")
            deliver(last)
            return
        }

        val netAvail = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val gpsAvail = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        Log.d(TAG, "Providers — GPS=$gpsAvail Network=$netAvail")

        if (!netAvail && !gpsAvail) {
            Log.w(TAG, "No providers enabled")
            // No providers at all — still try any cached location regardless of age
            if (last != null) {
                Log.d(TAG, "Falling back to stale cached location (no providers)")
                deliver(last)
            } else {
                callback.onError("No location providers enabled")
            }
            return
        }

        // Request network first — it responds in <1s vs 5+ for GPS.
        // Both race; first deliver() wins, so network usually takes it.
        if (netAvail) {
            Log.d(TAG, "Requesting network location updates")
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_TIME_MS, MIN_DIST_M, netListener)
        }
        if (gpsAvail) {
            Log.d(TAG, "Requesting GPS location updates")
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DIST_M, gpsListener)
        }

        // Passive provider — piggybacks on location requests from other apps (zero battery cost)
        try {
            Log.d(TAG, "Requesting passive location updates")
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, MIN_TIME_MS, MIN_DIST_M, passiveListener)
        } catch (_: Exception) {}

        // On API 30+, getCurrentLocation is optimized for single fixes and can be faster
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bestProvider = if (netAvail) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
            Log.d(TAG, "Using getCurrentLocation($bestProvider) on API ${Build.VERSION.SDK_INT}")
            lm.getCurrentLocation(bestProvider, null, appContext.mainExecutor) { loc ->
                if (loc != null) {
                    Log.d(TAG, "getCurrentLocation result: acc=${loc.accuracy}m age=${(System.currentTimeMillis() - loc.time) / 1000}s")
                    deliver(loc)
                } else {
                    Log.d(TAG, "getCurrentLocation returned null")
                }
            }
        }

        // Stage 1: Quick timeout — deliver a stale cached location so the UI
        // isn't blocked, but keep listening for a fresh fix in the background.
        if (last != null) {
            handler.postDelayed({
                if (!delivered) {
                    Log.d(TAG, "Stage 1 timeout (${QUICK_TIMEOUT_MS}ms) — delivering stale cached location")
                    deliver(last)
                }
            }, QUICK_TIMEOUT_MS)
        }

        // Stage 2: Hard timeout — stop listening and use whatever we have.
        handler.postDelayed({
            if (!delivered) {
                Log.w(TAG, "Stage 2 hard timeout (${HARD_TIMEOUT_MS}ms) — no fix received")
                cleanup()
                val stale = bestLastKnown()
                if (stale != null) {
                    Log.d(TAG, "Delivering stale location at hard timeout: acc=${stale.accuracy}m age=${(System.currentTimeMillis() - stale.time) / 60_000}min")
                    deliver(stale)
                } else {
                    Log.e(TAG, "No location available at all — giving up")
                    callback.onError("Location timed out — please check that location services are enabled")
                }
            }
        }, HARD_TIMEOUT_MS)
    }

    fun cancel() = cleanup()

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? {
        val candidates = mutableListOf<Location>()
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            "fused"  // some devices expose a fused provider
        )) {
            try { lm.getLastKnownLocation(provider)?.let { candidates.add(it) } } catch (_: Exception) {}
        }
        // Prefer the most accurate; among equal accuracy, prefer the newest
        return candidates.minWithOrNull(compareBy<Location> { it.accuracy }.thenByDescending { it.time })
    }

    private fun deliver(loc: Location) {
        if (delivered) {
            Log.d(TAG, "deliver() skipped — already delivered")
            return
        }
        delivered = true
        Log.d(TAG, "deliver() — lat=${loc.latitude} lng=${loc.longitude} acc=${loc.accuracy}m provider=${loc.provider}")
        cleanup()
        handler.post { callback.onLocation(loc.latitude, loc.longitude) }
    }

    private fun cleanup() {
        try { lm.removeUpdates(gpsListener) } catch (_: Exception) {}
        try { lm.removeUpdates(netListener) } catch (_: Exception) {}
        try { lm.removeUpdates(passiveListener) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }
}
