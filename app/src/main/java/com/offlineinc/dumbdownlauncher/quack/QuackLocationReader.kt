package com.offlineinc.dumbdownlauncher.quack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "QuackLocationReader"

/**
 * Reads a coarse location for the quack feed using stock Android [LocationManager].
 *
 * We deliberately avoid FusedLocationProviderClient: MediaTek/TCL flip phones
 * ship inconsistent Play Services and often no Network Location Provider, so
 * talking straight to [LocationManager] is the most reliable path.
 *
 * Usage model (matches the launcher's quack flow):
 *  - [prewarm] is called from [DumbDownApp.onCreate] on every boot, and fires
 *    a background read that populates the cache within ~30s.
 *  - When the user opens the quack screen, [readCached] returns whatever's in
 *    the cache *without* blocking — the feed loads instantly.
 *  - When the user hits the "refresh" soft key, [forceRefresh] invalidates
 *    the cache and re-reads (blocking up to 30s), so they can deliberately
 *    re-geolocate if they've moved.
 *  - Cache TTL is 30 minutes. After that [readCached] returns null and the
 *    ViewModel falls back to sending no coords (backend returns empty feed).
 *
 * Strategy inside the blocking read:
 *  1. Try cached [LocationManager.getLastKnownLocation] across every enabled
 *     provider. If *any* returns a fix, use the most accurate one.
 *  2. Otherwise request a single update from every enabled provider in
 *     parallel (fused, gps, network, passive) and take whichever returns
 *     first. On MediaTek flip phones the stock AOSP `fused` provider
 *     (com.android.location.fused — NOT Google Play Services) is the one
 *     that actually delivers; `network` typically doesn't exist without
 *     Play Services; `gps` works outdoors.
 *  3. Give up after the timeout.
 */
object QuackLocationReader {

    /** Raw provider name; avoids LocationManager.FUSED_PROVIDER (API 31+). */
    private const val FUSED_PROVIDER = "fused"

    /** Coarse location is stable over a 30-min window; no need to re-fetch. */
    private const val CACHE_TTL_MS = 30 * 60_000L

    /**
     * Max age of Android's system-wide last-known fix before we reject it
     * and force a fresh read. Short enough that "took the train from NYC
     * to DC" won't return yesterday's NYC fix; long enough that the common
     * case (fix from a few minutes ago) is instant.
     */
    private const val LAST_KNOWN_MAX_AGE_MS = 5 * 60_000L

    /**
     * Timeout for the boot-time prewarm. Longer than the user-facing refresh
     * because at boot the radio may still be attaching to the cellular
     * network — 60s covers most cold-start cell registration windows.
     */
    private const val PREWARM_TIMEOUT_MS = 60_000L

    /** User-facing refresh timeout (what we block the loading UI for). */
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    @Volatile private var cachedLat: Double? = null
    @Volatile private var cachedLng: Double? = null
    @Volatile private var cachedAt: Long = 0L

    /** Ensures only one background read runs at a time (prewarm + refresh). */
    private val inflight = AtomicBoolean(false)

    /**
     * Latch for callers who open quack *while* a prewarm is still running.
     * Non-null whenever a background read is in flight; counted down and
     * cleared when the read finishes (success or timeout).
     */
    @Volatile private var inflightLatch: CountDownLatch? = null

    /**
     * Return a cached coarse fix if one exists and is less than [CACHE_TTL_MS]
     * old, else null. Never blocks, never touches the radio — safe to call on
     * the main thread.
     */
    @JvmStatic
    fun readCached(): Pair<Double, Double>? {
        val lat = cachedLat ?: return null
        val lng = cachedLng ?: return null
        val age = System.currentTimeMillis() - cachedAt
        if (age >= CACHE_TTL_MS) return null
        return lat to lng
    }

    /**
     * Fire-and-forget: kick off a background read that will populate the cache.
     * No-op if the cache is already fresh or a read is already in flight.
     *
     * Called from [DumbDownApp.onCreate] so the first time the user opens
     * quack (seconds or minutes after boot) there's already a fix waiting.
     */
    @JvmStatic
    fun prewarm(ctx: Context) {
        if (readCached() != null) {
            Log.d(TAG, "prewarm: cache fresh — skipping")
            return
        }
        spawnReadLocked(ctx, "prewarm", PREWARM_TIMEOUT_MS)
    }

    /**
     * True if a background location read is currently in progress (either
     * the boot-time prewarm or a user-triggered refresh).
     */
    @JvmStatic
    fun isFetching(): Boolean = inflightLatch != null

    /**
     * Block up to [waitMs] for the currently-inflight read to finish, then
     * return whatever's cached (possibly null if the read timed out).
     *
     * If nothing is in flight, returns [readCached] immediately.
     *
     * MUST be called from a background thread. Designed for the case where
     * the user opens the quack screen while the boot-time prewarm is still
     * waiting on the radio — we show a loading UI and let them piggyback
     * on the prewarm instead of firing a second read.
     */
    @JvmStatic
    fun awaitInflight(waitMs: Long): Pair<Double, Double>? {
        val latch = inflightLatch ?: return readCached()
        try {
            Log.i(TAG, "awaitInflight: waiting up to ${waitMs}ms for in-flight read")
            val finished = latch.await(waitMs, TimeUnit.MILLISECONDS)
            Log.i(TAG, "awaitInflight: finished=$finished")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "awaitInflight: interrupted")
        }
        return readCached()
    }

    /**
     * Invalidates the cache and blocks up to [timeoutMs] re-reading location.
     * Called from the quack screen's refresh soft key.
     *
     * MUST be called from a background thread.
     */
    @JvmStatic
    @JvmOverloads
    fun forceRefresh(ctx: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Pair<Double, Double>? {
        invalidateCache()
        return blockingRead(ctx, timeoutMs)
    }

    /**
     * Legacy convenience — read from cache, falling back to a blocking fetch
     * if empty. Preferred API is [readCached] + [prewarm] + [forceRefresh].
     */
    @JvmStatic
    @JvmOverloads
    fun read(ctx: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Pair<Double, Double>? {
        readCached()?.let { return it }
        return blockingRead(ctx, timeoutMs)
    }

    fun invalidateCache() {
        cachedLat = null
        cachedLng = null
        cachedAt = 0L
    }

    // ─── internals ─────────────────────────────────────────────────────

    /** Spawn a background thread that fills the cache. Dedup'd via [inflight]. */
    private fun spawnReadLocked(ctx: Context, reason: String, timeoutMs: Long) {
        if (!inflight.compareAndSet(false, true)) {
            Log.d(TAG, "$reason: read already in flight — skipping")
            return
        }
        val latch = CountDownLatch(1)
        inflightLatch = latch
        Thread({
            try {
                Log.i(TAG, "$reason: starting background location read (timeout=${timeoutMs}ms)")
                val start = System.currentTimeMillis()
                val result = blockingRead(ctx, timeoutMs)
                val elapsed = System.currentTimeMillis() - start
                if (result != null) {
                    Log.i(TAG, "$reason: got fix in ${elapsed}ms (lat=${result.first}, lng=${result.second})")
                } else {
                    Log.w(TAG, "$reason: no fix after ${elapsed}ms")
                }
            } finally {
                latch.countDown()
                inflightLatch = null
                inflight.set(false)
            }
        }, "QuackLocationReader-$reason").start()
    }

    private fun blockingRead(ctx: Context, timeoutMs: Long): Pair<Double, Double>? {
        if (!hasLocationPermission(ctx)) {
            Log.w(TAG, "no location permission — quacks will have no location")
            return null
        }

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Log.w(TAG, "LocationManager unavailable")
            return null
        }

        // 1. Cheapest possible: scan lastKnownLocation across every provider
        //    and pick the most accurate *recent* one. We reject fixes older
        //    than LAST_KNOWN_MAX_AGE_MS because a stale fix (e.g. NYC from
        //    yesterday while the user is now in DC) is worse than no fix.
        bestLastKnown(lm)?.let { loc ->
            val age = System.currentTimeMillis() - loc.time
            if (age <= LAST_KNOWN_MAX_AGE_MS) {
                Log.i(TAG, "using last-known fix from ${loc.provider} (acc=${loc.accuracy}m, age=${age / 1000}s)")
                return storeAndReturn(loc.latitude, loc.longitude)
            } else {
                Log.i(TAG, "last-known fix too stale (age=${age / 1000}s) — requesting fresh update")
            }
        }

        // 2. Request a single update from every enabled provider in parallel,
        //    PLUS always try `fused` even if getProviders(true) doesn't list
        //    it. On MediaTek flip phones the AOSP fused service
        //    (com.android.location.fused) is bound but reports as disabled to
        //    apps because there's no Play Services / Network provider backing
        //    it — yet it will still accept requests and sometimes deliver.
        val enabled = lm.getProviders(true).toMutableSet()
        if ("fused" !in enabled && FUSED_PROVIDER in lm.getProviders(false)) {
            Log.i(TAG, "fused provider exists but not enabled — requesting anyway")
            enabled.add(FUSED_PROVIDER)
        } else if (FUSED_PROVIDER !in enabled) {
            // Force-add fused even if getProviders(false) hides it. Cheap to
            // try; requestSingleUpdate will throw IllegalArgumentException we
            // can swallow if it truly isn't there.
            enabled.add(FUSED_PROVIDER)
        }
        val providers = enabled.toList()
        if (providers.isEmpty()) {
            Log.w(TAG, "no location providers enabled — can't fix")
            return null
        }
        Log.i(TAG, "no cached fix — requesting single update from $providers (timeout=${timeoutMs}ms)")

        return requestOneShot(lm, providers, timeoutMs)?.let { storeAndReturn(it.latitude, it.longitude) }
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        return coarse == PackageManager.PERMISSION_GRANTED || fine == PackageManager.PERMISSION_GRANTED
    }

    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (p in providers) {
            val loc = try {
                @Suppress("MissingPermission")
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                Log.w(TAG, "getLastKnownLocation($p) denied: ${e.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "getLastKnownLocation($p) failed: ${e.message}")
                null
            } ?: continue
            if (best == null || loc.accuracy < best!!.accuracy) best = loc
        }
        return best
    }

    private fun requestOneShot(
        lm: LocationManager,
        providers: List<String>,
        timeoutMs: Long,
    ): Location? {
        val latch = CountDownLatch(1)
        val handlerThread = HandlerThread("QuackLocationReader-OneShot").apply { start() }
        val handler = Handler(handlerThread.looper)
        val captured = AtomicReference<Location?>(null)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.i(TAG, "got fix from ${location.provider} acc=${location.accuracy}m")
                val prev = captured.get()
                if (prev == null || location.accuracy < prev.accuracy) {
                    captured.set(location)
                }
                latch.countDown()
            }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            handler.post {
                for (p in providers) {
                    try {
                        @Suppress("MissingPermission")
                        lm.requestSingleUpdate(p, listener, handlerThread.looper)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestSingleUpdate($p) failed: ${e.message}")
                    }
                }
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            handlerThread.quitSafely()
        }

        return captured.get()
    }

    private fun storeAndReturn(lat: Double, lng: Double): Pair<Double, Double> {
        cachedLat = lat
        cachedLng = lng
        cachedAt = System.currentTimeMillis()
        return lat to lng
    }
}
