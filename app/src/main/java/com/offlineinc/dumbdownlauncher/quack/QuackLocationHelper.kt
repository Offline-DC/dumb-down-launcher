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
 *  1. Our own persisted location (< 2 hours old) — instant, no GPS needed
 *  2. System cached location (< 30 min old) — instant
 *  3. Live GPS/Network fix — wait up to HARD_TIMEOUT_MS
 *  4. Our persisted location (up to 7 days old) — stale fallback at hard timeout
 *  5. Any system cached location regardless of age
 *  6. Error
 *
 * On every successful delivery the location is saved to QuackLocationStore so
 * that step 1 succeeds on every subsequent open after first use.
 */
class QuackLocationHelper(
    context: Context,
    private val callback: Callback,
    /**
     * How long to wait on live providers before giving up. 30s for foreground
     * use (UI is loading), much longer for background prewarm where a cold GPS
     * chip indoors needs many minutes to download orbital data.
     */
    private val hardTimeoutMs: Long = HARD_TIMEOUT_MS,
) {

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
        /** Default give-up timeout for foreground use; UI shows loading until then. */
        const val HARD_TIMEOUT_MS  = 30_000L
        /** Long timeout used by background prewarm — cold GPS can take many minutes. */
        const val PREWARM_TIMEOUT_MS = 10 * 60_000L
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

        // ── 1. Our own persisted location (< 2 hours) ─────────────────────────
        // On MediaTek flip phones getLastKnownLocation() is often null because
        // no other app has recently requested location. We persist our own copy
        // so that after the first successful use this step is always instant.
        val persisted = QuackLocationStore.load(appContext)
        if (persisted != null) {
            Log.d(TAG, "Persisted location: age=${persisted.ageMinutes}min lat=${persisted.lat} lng=${persisted.lng}")
            if (persisted.ageMs < QuackLocationStore.FRESH_MAX_AGE_MS) {
                Log.d(TAG, "Persisted location is fresh (< 2h) — delivering immediately")
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

        // Don't early-return when no providers are enabled — BeaconDB
        // (Wi-Fi + cell) doesn't depend on LocationManager providers and is
        // our primary source on this hardware.

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

        // ── Primary: BeaconDB (Wi-Fi + cell) ──────────────────────────────
        // On this hardware the LocationManager Network Provider doesn't
        // exist (no Play Services / no AOSP fused backing) and GPS often
        // can't acquire indoors. BeaconDB resolves nearby BSSIDs + the
        // serving cell tower into a coarse fix that works *anywhere* with
        // an internet connection — typically beating GPS by 5–10 seconds
        // and beating GPS-indoors by infinity.
        //
        // GPS is only started as a *fallback* if BeaconDB returns null —
        // no point hammering the GPS chip 24/7 on a device where it rarely
        // acquires. Runs on its own thread; total latency is bounded by
        // NetworkLocationFetcher's ~20s worst case before GPS even begins.
        Thread({
            try {
                val fix = NetworkLocationFetcher.fetch(appContext)
                if (fix != null) {
                    Log.i(TAG, "BeaconDB fix: lat=${fix.lat} lng=${fix.lng} acc=${fix.accuracyMeters}m")
                    val synthetic = Location("beacondb").apply {
                        latitude = fix.lat
                        longitude = fix.lng
                        accuracy = fix.accuracyMeters.toFloat()
                        time = System.currentTimeMillis()
                    }
                    handler.post { if (!delivered) deliver(synthetic) }
                } else {
                    Log.w(TAG, "BeaconDB returned no fix — falling back to GPS providers")
                    handler.post { if (!delivered) startGpsFallback(netAvail, gpsAvail) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "BeaconDB fetch threw: ${e.message} — falling back to GPS providers")
                handler.post { if (!delivered) startGpsFallback(netAvail, gpsAvail) }
            }
        }, "QuackLocation-BeaconDB").start()

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
                Log.w(TAG, "Stage 2 hard timeout (${hardTimeoutMs}ms) — no fresh fix received")
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
        }, hardTimeoutMs)
    }

    /**
     * Start GPS/Network/Passive providers. Only invoked when BeaconDB has
     * already failed — on a healthy device BeaconDB wins first and this
     * never runs, which saves the GPS chip a lot of pointless wake-ups.
     */
    @SuppressLint("MissingPermission")
    private fun startGpsFallback(netAvail: Boolean, gpsAvail: Boolean) {
        if (delivered) return
        Log.d(TAG, "startGpsFallback: netAvail=$netAvail gpsAvail=$gpsAvail")

        // Request network first — responds in <1s vs 5+ for GPS; both race, first wins
        if (netAvail) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_TIME_MS, MIN_DIST_M, netListener)
        if (gpsAvail) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,     MIN_TIME_MS, MIN_DIST_M, gpsListener)
        // Passive provider piggybacks on other apps' requests at zero battery cost
        try { lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, MIN_TIME_MS, MIN_DIST_M, passiveListener) } catch (_: Exception) {}

        // API 30+: optimised single-fix API. Only call if at least one of
        // the underlying providers is enabled — passing a disabled provider
        // throws IllegalArgumentException on some MediaTek builds.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (netAvail || gpsAvail)) {
            val best = if (netAvail) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
            Log.d(TAG, "Using getCurrentLocation($best) on API ${Build.VERSION.SDK_INT}")
            try {
                lm.getCurrentLocation(best, null, appContext.mainExecutor) { loc ->
                    if (loc != null) {
                        Log.d(TAG, "getCurrentLocation: acc=${loc.accuracy}m age=${(System.currentTimeMillis() - loc.time) / 1000}s")
                        deliver(loc)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocation($best) threw: ${e.message}")
            }
        }
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
        // Always persist the latest fix so the next open gets a fresh location,
        // even if we already delivered (e.g. from persisted cache).
        QuackLocationStore.save(appContext, loc.latitude, loc.longitude)

        if (delivered) {
            // We got a better fix after already delivering — save it (done above)
            // and clean up the listeners so we stop the 1 Hz log spam.
            Log.d(TAG, "deliver() — fresher fix saved (acc=${loc.accuracy}m), cleaning up listeners")
            cleanup()
            return
        }
        delivered = true
        Log.d(TAG, "deliver() lat=${loc.latitude} lng=${loc.longitude} acc=${loc.accuracy}m provider=${loc.provider}")
        cleanup()
        handler.post { callback.onLocation(loc.latitude, loc.longitude) }
    }

    private fun cleanup() {
        try { lm.removeUpdates(gpsListener) }     catch (_: Exception) {}
        try { lm.removeUpdates(netListener) }     catch (_: Exception) {}
        try { lm.removeUpdates(passiveListener) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }
}
