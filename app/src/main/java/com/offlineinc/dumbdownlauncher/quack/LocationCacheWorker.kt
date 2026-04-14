package com.offlineinc.dumbdownlauncher.quack

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Periodically caches the device location in the background so that
 * QuackLocationHelper always has a fresh persisted fix on launch.
 *
 * On MediaTek flip phones without Google Play Services,
 * getLastKnownLocation() is often null and a live GPS fix can take
 * 10-30 seconds. By refreshing every 30 minutes the persisted
 * location in QuackLocationStore stays well within the 1-hour
 * "fresh" window, making Quack open instantly.
 */
class LocationCacheWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "LocationCacheWorker"
        private const val WORK_NAME = "location_cache"
        /** How long to wait for a live fix before giving up.
         *  GPS cold start on MediaTek flip phones without AGPS can take 45-90s. */
        private const val FIX_TIMEOUT_SECONDS = 90L

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)

            // One-shot run immediately on launcher start so the cache is warm
            // before the user ever opens Quack (periodic work can take up to
            // 30 min to fire for the first time after install).
            wm.enqueueUniqueWork(
                "${WORK_NAME}_boot",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LocationCacheWorker>().build(),
            )

            // Periodic refresh every 30 minutes to keep the cache fresh.
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LocationCacheWorker>(30, TimeUnit.MINUTES).build(),
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun doWork(): Result {
        Log.d(TAG, "Starting background location cache")

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // ── 1. Check system cached locations first (free, no GPS wake) ────────
        val cached = bestLastKnown(lm)
        if (cached != null) {
            val ageMin = (System.currentTimeMillis() - cached.time) / 60_000
            Log.d(TAG, "System cache hit: provider=${cached.provider} acc=${cached.accuracy}m age=${ageMin}min")
            // If reasonably fresh, just persist it and we're done
            if (ageMin < 60) {
                QuackLocationStore.save(context, cached.latitude, cached.longitude)
                Log.d(TAG, "Persisted cached location (age=${ageMin}min)")
                return Result.success()
            }
        }

        // ── 2. Request a live fix ─────────────────────────────────────────────
        val latch = CountDownLatch(1)
        var bestFix: Location? = null
        val lock = Any()

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                synchronized(lock) {
                    val current = bestFix
                    if (current == null || loc.accuracy < current.accuracy) {
                        bestFix = loc
                    }
                }
                latch.countDown()
            }
            override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }

        val netAvail = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        val gpsAvail = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        Log.d(TAG, "Providers — GPS=$gpsAvail Network=$netAvail")

        // Request updates on a background looper so callbacks fire off the main thread
        val handlerThread = android.os.HandlerThread("LocationCacheThread").apply { start() }
        val looper = handlerThread.looper

        var anyProviderRegistered = false

        try {
            // Try all providers regardless of isProviderEnabled() — some MediaTek devices
            // report a provider as disabled even though it works (e.g. network location
            // enabled via root but isProviderEnabled() hasn't caught up yet).
            for (provider in listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )) {
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, looper)
                    anyProviderRegistered = true
                    Log.d(TAG, "Registered listener on $provider")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not register on $provider: ${e.message}")
                }
            }

            if (!anyProviderRegistered) {
                // Truly no providers accessible — persist whatever cache we have
                Log.w(TAG, "No providers accessible at all")
                if (cached != null) {
                    QuackLocationStore.save(context, cached.latitude, cached.longitude)
                    Log.d(TAG, "No providers — persisted stale cache")
                }
                return Result.success()
            }

            // API 30+: optimised single-fix API as an additional source
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = if (netAvail) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
                try {
                    lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                        if (loc != null) {
                            synchronized(lock) {
                                val current = bestFix
                                if (current == null || loc.accuracy < current.accuracy) {
                                    bestFix = loc
                                }
                            }
                            latch.countDown()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation failed: ${e.message}")
                }
            }

            // Wait for a fix (or timeout)
            latch.await(FIX_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            handlerThread.quitSafely()
        }

        // ── 3. Persist the best result ────────────────────────────────────────
        val fix = synchronized(lock) { bestFix }
        if (fix != null) {
            QuackLocationStore.save(context, fix.latitude, fix.longitude)
            Log.d(TAG, "Persisted live fix: acc=${fix.accuracy}m provider=${fix.provider}")
        } else if (cached != null) {
            // No live fix arrived — persist stale cache so it's at least available
            QuackLocationStore.save(context, cached.latitude, cached.longitude)
            Log.d(TAG, "No live fix — persisted stale system cache")
        } else {
            Log.w(TAG, "No location obtained at all")
        }

        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(lm: LocationManager): Location? {
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
}
