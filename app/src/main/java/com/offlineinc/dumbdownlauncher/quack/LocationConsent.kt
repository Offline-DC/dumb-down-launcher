package com.offlineinc.dumbdownlauncher.quack

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.offlineinc.dumbdownlauncher.weather.WeatherLocationConsentStore

private const val TAG = "LocationConsent"

/**
 * Central gate for the launcher's background location cache.
 *
 * The launcher used to unconditionally prewarm + periodically refresh the
 * persisted location cache (via [QuackLocationHelper] / [QuackLocationRefreshWorker]).
 * That meant every boot fetched coarse location and a periodic worker fetched
 * it again hourly — even if the user never opened the two apps that
 * use location (quack and weather). Now we wait until the user has accepted
 * the location ask in either of those apps before doing any of that work.
 */
object LocationConsent {

    /**
     * Returns true if the user has accepted the location ask in either the
     * quack app or the weather app. The prewarm + periodic refresh of the
     * location cache is gated on this.
     */
    fun hasAnyConsent(context: Context): Boolean {
        val ctx = context.applicationContext
        return QuackLocationConsentStore.hasConsented(ctx) ||
            WeatherLocationConsentStore.hasConsented(ctx)
    }

    /**
     * Called when the user has just accepted the location ask in quack or
     * weather. Kicks off the same background prewarm + periodic refresh the
     * launcher used to do unconditionally at boot, so the persisted location
     * cache is populated from now on.
     */
    fun onConsentGranted(context: Context) {
        val appCtx = context.applicationContext
        Log.i(TAG, "consent granted — starting location prewarm + periodic refresh")
        // Self-grant perms via `su pm grant` if they're missing, then kick off
        // a silent coarse-location prewarm so the persisted cache is populated
        // before the user returns to the app.
        Thread({
            LocationPermissionGranter.ensureGranted(appCtx)
            val noop = object : QuackLocationHelper.Callback {
                override fun onLocation(lat: Double, lng: Double) { /* persisted internally */ }
                override fun onError(reason: String) { /* ignore */ }
            }
            Handler(Looper.getMainLooper()).post {
                QuackLocationHelper(
                    appCtx,
                    noop,
                    hardTimeoutMs = QuackLocationHelper.PREWARM_TIMEOUT_MS,
                ).request()
            }
        }, "LocationConsent-prewarm").start()

        // Schedule the periodic 1-hour background refresh so the cache
        // stays fresh without the user having to reopen the app.
        QuackLocationRefreshWorker.schedule(appCtx)
    }
}
