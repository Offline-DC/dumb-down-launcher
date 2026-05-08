package com.offlineinc.dumbdownlauncher.openbubbles

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val TAG = "OBAttachmentWorker"

/**
 * Weekly scheduled wipe of OpenBubbles' attachment cache.
 *
 * Pairs with the `flutter.autoDownload = false` setting that the
 * `openbubbles_setup_v1` migration in
 * [com.offlineinc.dumbdownlauncher.DumbDownApp] applies — together they
 * keep the on-disk attachment dir to roughly "the last week of stuff
 * the user explicitly opened". On the TCL Flip Go's tight storage budget
 * that's the difference between hundreds of MB of stale image cache vs.
 * a few MB.
 *
 * Runs once every 7 days, anchored to the next 2 AM local time. 2 AM
 * is the same hour we pick for [com.offlineinc.dumbdownlauncher.calllog.CallLogCleanupWorker]
 * — phone is idle, no calls landing, no one looking at the dialer.
 *
 * Implementation delegates entirely to [OpenBubblesOps.clearAttachments],
 * which also throws when OpenBubbles is in the foreground (i.e., the
 * user is actively looking at it). The worker catches the throw and
 * returns success — the periodic schedule stays alive and the next
 * weekly tick will try again.
 */
class OpenBubblesAttachmentCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val result = OpenBubblesOps.clearAttachments(TAG)
            Log.i(TAG, "doWork: cleared OB attachments (was ${result.bytesFreedDisplay})")
            Result.success()
        } catch (e: IllegalStateException) {
            // Foreground guard tripped — caller is using OpenBubbles
            // right now. Skip cleanly so the periodic schedule stays
            // alive; next weekly tick will try again.
            Log.i(TAG, "doWork: deferred — ${e.message}")
            Result.success()
        } catch (e: Exception) {
            // Any other failure (root unavailable, find/rm error) —
            // log and succeed so the periodic worker isn't dropped
            // from WorkManager. We'll get another shot in a week.
            Log.w(TAG, "doWork: cleanup failed — ${e.javaClass.simpleName}: ${e.message}")
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "openbubbles_attachment_cleanup"
        private const val PERIOD_DAYS = 7L
        private const val TARGET_HOUR_LOCAL = 2  // 2 AM local time

        /**
         * Schedules the weekly cleanup. First fire is anchored to the
         * next 2 AM local time, subsequent fires are 7 days apart.
         * [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling on every
         * boot (we are the HOME launcher) is a no-op once queued.
         */
        fun schedule(context: Context) {
            val initialDelayMs = millisUntilNextTargetHour()
            val request = PeriodicWorkRequestBuilder<OpenBubblesAttachmentCleanupWorker>(
                PERIOD_DAYS, TimeUnit.DAYS,
            )
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.i(
                TAG,
                "scheduled openbubbles attachment cleanup every $PERIOD_DAYS days, " +
                    "first run in ~${initialDelayMs / 60_000} min"
            )
        }

        /** Cancels the periodic cleanup. Currently only used by tests. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Returns the milliseconds remaining until the next [TARGET_HOUR_LOCAL]
         * o'clock in the device's local timezone. If the target hour has
         * already passed today, rolls forward to tomorrow.
         */
        internal fun millisUntilNextTargetHour(now: Long = System.currentTimeMillis()): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, TARGET_HOUR_LOCAL)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
            }
            return cal.timeInMillis - now
        }
    }
}
