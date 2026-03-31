package com.offlineinc.dumbdownlauncher.quack

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Gets a single location fix using GPS, falling back to network provider.
 * Designed for flip phones: requests one fix then immediately removes updates
 * to preserve battery. Calls back on the main thread.
 */
class QuackLocationHelper(context: Context, private val callback: Callback) {

    interface Callback {
        fun onLocation(lat: Double, lng: Double)
        fun onError(reason: String)
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val MIN_TIME_MS = 0L
        private const val MIN_DIST_M = 0f
        // For a 25-mile feed radius, a 30-min-old location is plenty accurate
        private const val CACHE_MAX_AGE_MS = 30 * 60 * 1000L
    }

    private val appContext: Context = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var delivered = false

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = deliver(loc)
        override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private val netListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = deliver(loc)
        override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    @SuppressLint("MissingPermission")
    fun request() {
        // Accept any cached location under 30 min — 25-mile radius doesn't need fresh GPS
        val last = bestLastKnown()
        if (last != null && last.time > System.currentTimeMillis() - CACHE_MAX_AGE_MS) {
            deliver(last)
            return
        }

        val netAvail = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val gpsAvail = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

        if (!netAvail && !gpsAvail) {
            callback.onError("No location providers enabled")
            return
        }

        // Request network first — it responds in <1s vs 5+ for GPS.
        // Both race; first deliver() wins, so network usually takes it.
        if (netAvail) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_TIME_MS, MIN_DIST_M, netListener)
        if (gpsAvail) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DIST_M, gpsListener)

        handler.postDelayed({
            if (!delivered) {
                cleanup()
                val stale = bestLastKnown()
                if (stale != null) deliver(stale) else callback.onError("Location timed out")
            }
        }, TIMEOUT_MS)
    }

    fun cancel() = cleanup()

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? {
        val gps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
        val net = try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
        return when {
            gps == null -> net
            net == null -> gps
            gps.accuracy <= net.accuracy -> gps
            else -> net
        }
    }

    private fun deliver(loc: Location) {
        if (delivered) return
        delivered = true
        cleanup()
        handler.post { callback.onLocation(loc.latitude, loc.longitude) }
    }

    private fun cleanup() {
        try { lm.removeUpdates(gpsListener) } catch (_: Exception) {}
        try { lm.removeUpdates(netListener) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }
}
