package com.offlineinc.dumbdownlauncher.quack

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "QuackLocRefreshWorker"

/**
 * Periodic background worker that re-acquires the user's coarse location
 * every hour so the persisted [QuackLocationStore] cache stays fresh
 * even when the user hasn't opened the quack screen recently. An hour
 * is short enough that intercity travel (e.g. DC→NYC by train) updates
 * within the trip rather than after it.
 *
 * The worker drives [QuackLocationHelper] with [WORKER_HARD_TIMEOUT_MS]
 * (2 min) — long enough for BeaconDB's ~20s worst case plus a real
 * GPS-acquisition window, but short enough that 24 fires/day can't stack
 * up to hours of GPS-on time if BeaconDB is failing. We deliberately
 * don't use the 10-minute [PREWARM_TIMEOUT_MS] here; that's only
 * appropriate for the once-per-boot prewarm, not the hourly tick. The
 * helper persists every fix it gets as a side effect, so the next time
 * the user opens quack the freshness window short-circuits the live
 * providers and the feed loads instantly.
 *
 * Battery: BeaconDB (Wi-Fi + cell) is the primary path on this hardware
 * and is cheap — a single HTTPS request plus a Wi-Fi scan that Android
 * runs anyway. The expensive GPS fallback only fires when BeaconDB fails,
 * and is now bounded to ~100s per fire (2 min total minus BeaconDB's window).
 */
class QuackLocationRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "doWork: starting periodic 1h location refresh")
        val latch = CountDownLatch(1)
        val callback = object : QuackLocationHelper.Callback {
            override fun onLocation(lat: Double, lng: Double) {
                Log.i(TAG, "doWork: got fix lat=$lat lng=$lng")
                latch.countDown()
            }
            override fun onError(reason: String) {
                Log.w(TAG, "doWork: failed — $reason")
                latch.countDown()
            }
        }
        // QuackLocationHelper posts to the main looper, so build it from the
        // main thread but block this worker thread on the latch.
        Handler(Looper.getMainLooper()).post {
            QuackLocationHelper(
                applicationContext,
                callback,
                hardTimeoutMs = WORKER_HARD_TIMEOUT_MS,
                // Background refresh must NOT spin the GPS chip. On an idle,
                // screen-off device BeaconDB fails whenever Wi-Fi is
                // disconnected, and the GPS fallback firing ~24×/day was the
                // largest idle-battery cost (it breaks Doze and runs the GPS
                // chip for ~5s+ each time). With this false, a failed BeaconDB
                // lookup just keeps the existing cached fix; the next
                // foreground open takes a fresh live fix if needed.
                allowGpsFallback = false,
                // Don't force the Wi-Fi chip awake from the background. Each
                // active startScan() on this MediaTek hardware causes a
                // "WLAN AHB ISR" kernel suspend abort; the hourly refresh
                // reads cached scan results instead. Together with
                // allowGpsFallback=false this makes the background refresh
                // effectively zero-radio-cost.
                activeWifiScan = false,
            ).request()
        }
        // Wait slightly longer than the helper's own hard timeout so we
        // always observe its callback rather than racing it.
        val finished = latch.await(
            WORKER_HARD_TIMEOUT_MS + 30_000L,
            TimeUnit.MILLISECONDS,
        )
        Log.i(TAG, "doWork: finished=$finished")
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "quack_location_refresh"
        private const val INTERVAL_HOURS = 1L

        /**
         * Hard timeout for a single periodic refresh. 2 minutes gives
         * BeaconDB its full ~20s worst-case window plus ~100s for the GPS
         * fallback to acquire — short enough that 24 fires/day can't burn
         * hours of GPS chip time if BeaconDB is consistently failing.
         * The boot prewarm still uses [QuackLocationHelper.PREWARM_TIMEOUT_MS]
         * (10 min) because it only runs once per process start.
         */
        private const val WORKER_HARD_TIMEOUT_MS = 2 * 60_000L

        fun schedule(context: Context) {
            // BeaconDB is the only source the background refresh uses now
            // (GPS fallback is disabled in doWork), and BeaconDB needs the
            // network. Gating on CONNECTED means we never burn a Doze
            // maintenance-window wake just to fail an offline lookup.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<QuackLocationRefreshWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE so devices upgrading from the old 6-hour schedule pick
                // up the new 1-hour interval on next app start. Unlike REPLACE,
                // UPDATE preserves the existing job's next-run time, so we
                // don't lose pending refreshes during the upgrade.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.i(TAG, "scheduled periodic refresh every ${INTERVAL_HOURS}h")
        }

        /**
         * Cancel any previously-scheduled periodic refresh. Called from
         * [com.offlineinc.dumbdownlauncher.DumbDownApp] when no location
         * consent has been granted in either of the apps that use it, so
         * devices that were upgraded from a build that scheduled this work
         * unconditionally stop doing the background refresh.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "cancelled periodic refresh")
        }
    }
}
