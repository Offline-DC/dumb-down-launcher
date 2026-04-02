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
 * Gets a single location fix, with a layered fallback strategy designed for
 * MediaTek flip phones where getLastKnownLocation() frequently returns null.
 *
 * Priority order:
 *  1. Our own persisted location (< 6 hours old) — instant, no GPS needed
 *  2. System cached location (< 30 min old) — instant
 *  3. Live GPS/Network fix — wait up to HARD_TIMEOUT_MS
 *  4. Our persisted location (up to 7 days old) — stale fallback at hard timeout
 *  5. Any system cached location regardless of age
 *  6. Error
 *
 * On every successful delivery the location is saved to QuackLocationStore so
 * that step 1 succeeds on every subsequent open after first use.
 */
class QuackLocationHelper(context: Context, private val callback: Callback) {

    interface Callback {
        fun onLocation(lat: Double, lng: Double)
        fun onError(reason: String)
    }

    companion object {
        private const val TAG = "QuackLocation"
        private const val MIN_TIME_MS = 0L
        private const val MIN_DIST_M  = 0f
        // For a 25-mile feed radius, 30-min system cache is accurate enough
        private const val SYSTEM_CACHE_MAX_AGE_MS = 30 * 60 * 1000L
        /** Deliver a stale system cache early so the UI isn't stuck waiting. */
        private const val QUICK_TIMEOUT_MS = 5_000L
        /** Give up on live providers; use any fallback available. */
        private const val HARD_TIMEOUT_MS  = 30_000L
    }

    private val appContext: Context = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var delivered = false

    private fun makeListener(label: String) = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            Log.d(TAG, "$label fix: acc=${loc.accuracy}m age=${(System.currentTimeMillis() - loc.time) / 1000}s")
            deliver(loc)
        }
        override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        override fun onProviderEnabled(p: String)  { Log.d(TAG, "Provider enabled: $p") }
        override fun onProviderDisabled(p: String) { Log.w(TAG, "Provider disabled: $p") }
    }

    private val gpsListener     = makeListener("GPS")
    private val netListener     = makeListener("Network")
    private val passiveListener = makeListener("Passive")

    @SuppressLint("MissingPermission")
    fun request() {
        Log.d(TAG, "request() called")

        // ── 1. Our own persisted location (< 6 hours) ─────────────────────────
        // On MediaTek flip phones getLastKnownLocation() is often null because
        // no other app has recently requested location. We persist our own copy
        // so that after the first successful use this step is always instant.
        val persisted = QuackLocationStore.load(appContext)
        if (persisted != null) {
            Log.d(TAG, "Persisted location: age=${persisted.ageMinutes}min lat=${persisted.lat} lng=${persisted.lng}")
            if (persisted.ageMs < QuackLocationStore.FRESH_MAX_AGE_MS) {
                Log.d(TAG, "Persisted location is fresh (< 6h) — delivering immediately")
                delivered = true
                handler.post { callback.onLocation(persisted.lat, persisted.lng) }
                // Kick off providers quietly so the next open gets an even fresher fix
                startProviders(quickFallback = null, staleFallback = null)
                return
            }
        }

        // ── 2. System cached location (< 30 min) ──────────────────────────────
        val systemLast = bestLastKnown()
        if (systemLast != null) {
            val ageMin = (System.currentTimeMillis() - systemLast.time) / 60_000
            Log.d(TAG, "System cached: acc=${systemLast.accuracy}m age=${ageMin}min provider=${systemLast.provider}")
            if (systemLast.time > System.currentTimeMillis() - SYSTEM_CACHE_MAX_AGE_MS) {
                Log.d(TAG, "System cache is fresh (< 30min) — delivering immediately")
                deliver(systemLast)
                return
            }
        } else {
            Log.w(TAG, "No cached location available at all")
        }

        val netAvail = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        val gpsAvail = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        Log.d(TAG, "Providers — GPS=$gpsAvail Network=$netAvail")

        if (!netAvail && !gpsAvail) {
            Log.w(TAG, "No providers enabled — using best available fallback")
            deliverBestFallback(persisted, systemLast, "no providers enabled")
            return
        }

        // ── 3. Request a live fix with timed fallbacks ─────────────────────────
        startProviders(
            quickFallback = systemLast,  // deliver stale system cache after 5s if still waiting
            staleFallback = persisted,   // deliver persisted (up to 7 days) at hard timeout
        )
    }

    @SuppressLint("MissingPermission")
    private fun startProviders(
        quickFallback: Location?,
        staleFallback: QuackLocationStore.StoredLocation?,
    ) {
        val netAvail = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        val gpsAvail = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }

        // Request network first — responds in <1s vs 5+ for GPS; both race, first wins
        if (netAvail) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_TIME_MS, MIN_DIST_M, netListener)
        if (gpsAvail) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,     MIN_TIME_MS, MIN_DIST_M, gpsListener)
        // Passive provider piggybacks on other apps' requests at zero battery cost
        try { lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, MIN_TIME_MS, MIN_DIST_M, passiveListener) } catch (_: Exception) {}

        // API 30+: optimised single-fix API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val best = if (netAvail) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
            Log.d(TAG, "Using getCurrentLocation($best) on API ${Build.VERSION.SDK_INT}")
            lm.getCurrentLocation(best, null, appContext.mainExecutor) { loc ->
                if (loc != null) {
                    Log.d(TAG, "getCurrentLocation: acc=${loc.accuracy}m age=${(System.currentTimeMillis() - loc.time) / 1000}s")
                    deliver(loc)
                }
            }
        }

        // Stage 1: quick timeout — deliver stale system cache so UI isn't blocked
        if (quickFallback != null) {
            handler.postDelayed({
                if (!delivered) {
                    Log.d(TAG, "Stage 1 (${QUICK_TIMEOUT_MS}ms): delivering stale system cache")
                    deliver(quickFallback)
                }
            }, QUICK_TIMEOUT_MS)
        }

        // Stage 2: hard timeout — take whatever fallback is available
        handler.postDelayed({
            if (!delivered) {
                Log.w(TAG, "Stage 2 hard timeout (${HARD_TIMEOUT_MS}ms) — no fresh fix received")
                cleanup()
                val systemStale = bestLastKnown()
                when {
                    systemStale != null -> {
                        val ageMin = (System.currentTimeMillis() - systemStale.time) / 60_000
                        Log.d(TAG, "Hard timeout: using stale system cache age=${ageMin}min")
                        deliver(systemStale)
                    }
                    staleFallback != null && staleFallback.ageMs < QuackLocationStore.STALE_MAX_AGE_MS -> {
                        Log.d(TAG, "Hard timeout: using persisted location age=${staleFallback.ageMinutes}min")
                        delivered = true
                        handler.post { callback.onLocation(staleFallback.lat, staleFallback.lng) }
                    }
                    else -> {
                        Log.e(TAG, "No location available at all — giving up")
                        callback.onError("Location timed out — please enable location services and try again")
                    }
                }
            }
        }, HARD_TIMEOUT_MS)
    }

    private fun deliverBestFallback(
        persisted: QuackLocationStore.StoredLocation?,
        system: Location?,
        reason: String,
    ) {
        when {
            system != null -> {
                Log.d(TAG, "Fallback ($reason): using system cache")
                deliver(system)
            }
            persisted != null && persisted.ageMs < QuackLocationStore.STALE_MAX_AGE_MS -> {
                Log.d(TAG, "Fallback ($reason): using persisted age=${persisted.ageMinutes}min")
                delivered = true
                handler.post { callback.onLocation(persisted.lat, persisted.lng) }
            }
            else -> {
                callback.onError("Location unavailable — please enable location services")
            }
        }
    }

    fun cancel() = cleanup()

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? {
        val candidates = mutableListOf<Location>()
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            "fused",
        )) {
            try { lm.getLastKnownLocation(provider)?.let { candidates.add(it) } } catch (_: Exception) {}
        }
        return candidates.minWithOrNull(compareBy<Location> { it.accuracy }.thenByDescending { it.time })
    }

    private fun deliver(loc: Location) {
        if (delivered) {
            Log.d(TAG, "deliver() skipped — already delivered")
            return
        }
        delivered = true
        Log.d(TAG, "deliver() lat=${loc.latitude} lng=${loc.longitude} acc=${loc.accuracy}m provider=${loc.provider}")
        cleanup()
        // Persist for instant delivery next time
        QuackLocationStore.save(appContext, loc.latitude, loc.longitude)
        handler.post { callback.onLocation(loc.latitude, loc.longitude) }
    }

    private fun cleanup() {
        try { lm.removeUpdates(gpsListener) }     catch (_: Exception) {}
        try { lm.removeUpdates(netListener) }     catch (_: Exception) {}
        try { lm.removeUpdates(passiveListener) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }
}
