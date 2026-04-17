package com.offlineinc.dumbdownlauncher.quack

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val TAG = "QuackFirstQuackWorker"

/**
 * Periodic worker that polls for the first quack in the user's area after
 * the Monday morning "be the first to quack" notification.
 *
 * Runs every ~15 minutes for up to 24 hours. When a non-empty feed is
 * detected, posts the "somebody quacked" notification and cancels itself.
 * Also self-cancels after 24 hours even if nobody quacked.
 *
 * Network errors are silently swallowed — the worker returns [Result.success]
 * to keep the periodic schedule alive for the next interval.
 */
class QuackFirstQuackWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    companion object {
        private const val WORK_NAME = "quack_first_quack_poll"
        private const val PREFS = "quack_monday_alarm"
        private const val KEY_ALARM_FIRED_AT = "alarm_fired_at"
        private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<QuackFirstQuackWorker>(
                15, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request,
            )
            Log.i(TAG, "Scheduled 15-min polling for first quack")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Polling cancelled")
        }

        /** Called by the alarm receiver to record when the Monday alarm fired. */
        fun recordAlarmFired(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_ALARM_FIRED_AT, System.currentTimeMillis())
                .apply()
        }

        private fun alarmFiredAt(context: Context): Long {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_ALARM_FIRED_AT, 0L)
        }
    }

    override fun doWork(): Result {
        Log.d(TAG, "doWork: polling for first quack")

        // 24-hour cutoff
        val firedAt = alarmFiredAt(applicationContext)
        if (firedAt > 0 && System.currentTimeMillis() - firedAt > TWENTY_FOUR_HOURS_MS) {
            Log.i(TAG, "doWork: 24h cutoff reached — cancelling")
            cancel(applicationContext)
            return Result.success()
        }

        // Need a location to poll
        val loc = QuackLocationStore.load(applicationContext)
        if (loc == null || loc.ageMs >= QuackLocationStore.STALE_MAX_AGE_MS) {
            Log.w(TAG, "doWork: no usable location — skipping this poll")
            return Result.success()
        }

        return try {
            val posts = QuackApiClient.fetchPosts(loc.lat, loc.lng)
            Log.d(TAG, "doWork: fetched ${posts.length()} posts")
            if (posts.length() > 0) {
                val muted = applicationContext.getSharedPreferences("quack_prefs", Context.MODE_PRIVATE)
                    .getBoolean("notifications_muted", false)
                if (!muted) {
                    QuackNotificationManager.notifySomebodyQuacked(applicationContext)
                }
                cancel(applicationContext)
                Log.i(TAG, "doWork: somebody quacked — ${if (muted) "muted" else "notified"} and cancelled polling")
            }
            Result.success()
        } catch (e: Exception) {
            // Network error — keep polling at next interval
            Log.w(TAG, "doWork: fetch failed — ${e.message}")
            Result.success()
        }
    }
}
