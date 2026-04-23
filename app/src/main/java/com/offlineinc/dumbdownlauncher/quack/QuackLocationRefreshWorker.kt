package com.offlineinc.dumbdownlauncher.quack

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "QuackLocRefreshWorker"

/**
 * Periodic background worker that re-acquires the user's coarse location
 * every 6 hours so the persisted [QuackLocationStore] cache stays fresh
 * even when the user hasn't opened the quack screen recently.
 *
 * The worker drives [QuackLocationHelper] with the long [PREWARM_TIMEOUT_MS]
 * so a cold GPS chip has up to ten minutes to find satellites in the
 * background. The helper persists every fix it gets as a side effect, so
 * the next time the user opens quack the < 6 h freshness window short-
 * circuits the live providers and the feed loads instantly.
 */
class QuackLocationRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "doWork: starting periodic 6h location refresh")
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
                hardTimeoutMs = QuackLocationHelper.PREWARM_TIMEOUT_MS,
            ).request()
        }
        // Wait slightly longer than the helper's own hard timeout so we
        // always observe its callback rather than racing it.
        val finished = latch.await(
            QuackLocationHelper.PREWARM_TIMEOUT_MS + 30_000L,
            TimeUnit.MILLISECONDS,
        )
        Log.i(TAG, "doWork: finished=$finished")
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "quack_location_refresh"
        private const val INTERVAL_HOURS = 6L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<QuackLocationRefreshWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP so we don't reset the 6-hour clock on every app start
                ExistingPeriodicWorkPolicy.KEEP,
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
