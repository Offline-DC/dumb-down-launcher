package com.offlineinc.dumbdownlauncher.quack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Single entry point for foreground code that needs a (lat, lng).
 *
 * The two apps that use location — quack and weather — used to duplicate
 * this logic verbatim. Both want the same behaviour: prefer a usable
 * persisted fix (instant, no GPS spin-up); if nothing's persisted, fall
 * back to a live fix via [QuackLocationHelper] (BeaconDB → cached → GPS,
 * with the standard timeouts). Returns null only if both paths fail.
 *
 * NOTE: this is *not* a consent gate. Each app still asks the user
 * individually before location is requested — [QuackLocationConsentStore]
 * and [com.offlineinc.dumbdownlauncher.weather.WeatherLocationConsentStore]
 * track those answers separately. This object only knows how to *fetch* a
 * fix once an app has decided to request one.
 */
object LocationProvider {

    private const val TAG = "LocationProvider"

    /**
     * Get a fix using the standard quack/weather priority:
     *   1. persisted location (< [QuackLocationStore.STALE_MAX_AGE_MS])
     *   2. live fix via [QuackLocationHelper] (which itself layers BeaconDB,
     *      system cache, GPS, and persisted-stale fallbacks)
     *
     * Returns null only if both paths fail. Callers should surface a
     * "location unavailable" error to the user in that case.
     */
    suspend fun fetch(context: Context): Pair<Double, Double>? {
        QuackLocationStore.loadIfUsable(context)?.let {
            Log.d(TAG, "fetch: persisted hit lat=${it.first} lng=${it.second}")
            return it
        }
        Log.d(TAG, "fetch: no persisted fix — requesting live")
        return live(context)
    }

    /**
     * Force a live fix via [QuackLocationHelper], skipping the persisted
     * cache. Most callers should prefer [fetch]; this exists for the rare
     * case where freshness matters more than latency.
     */
    suspend fun live(context: Context): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            val helper = QuackLocationHelper(context, object : QuackLocationHelper.Callback {
                private var resumed = false
                override fun onLocation(lat: Double, lng: Double) {
                    if (resumed) return
                    resumed = true
                    if (cont.isActive) cont.resume(lat to lng)
                }
                override fun onError(reason: String) {
                    if (resumed) return
                    resumed = true
                    Log.w(TAG, "live: helper error: $reason")
                    if (cont.isActive) cont.resume(null)
                }
            })
            cont.invokeOnCancellation { helper.cancel() }
            helper.request()
        }
}
