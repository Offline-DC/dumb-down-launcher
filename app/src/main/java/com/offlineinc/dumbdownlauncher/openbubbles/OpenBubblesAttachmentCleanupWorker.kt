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
 * Nightly cleanup of OpenBubbles' attachment cache.
 *
 * Pairs with the `flutter.autoDownload = false` setting that the
 * `openbubbles_setup_v1` migration in
 * [com.offlineinc.dumbdownlauncher.DumbDownApp] originally applies — and
 * which this worker now also **re-asserts** on every run, so a Flutter
 * rewrite or app-update revert can cause at most one day of auto-
 * downloads before being papered back over.
 *
 * On the TCL Flip 2's tight storage budget the attachments dir grows by
 * tens of MB per day; the audit captured 78 MB / 39 files on one device.
 * Wiping every night reclaims those bytes while preserving the rest of
 * OpenBubbles' state (auth tokens, message DB, contacts cache) — none
 * of which live under `app_flutter/attachments`.
 *
 * Runs once every 24 hours, anchored to the next 4 AM local time. 4 AM
 * matches the unified storage-cleanup window across all workers.
 *
 * Two-step run:
 *   1. [OpenBubblesOps.applyAutoDownloadOff] re-asserts the
 *      `flutter.autoDownload=false` + `flutter.highPerfMode=true` settings.
 *      No-ops cleanly if the values are already correct.
 *   2. [OpenBubblesOps.clearAttachments] wipes the attachments cache.
 *
 * Both throw [IllegalStateException] when OpenBubbles is in the
 * foreground; we catch and defer to the next nightly tick.
 */
class OpenBubblesAttachmentCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            // Defensive: re-apply auto-download-off before clearing.
            // If this throws (e.g., OB is focused) the clear step is
            // skipped too — both ops kill OB so they can't run while
            // the user is looking at it.
            OpenBubblesOps.applyAutoDownloadOff(applicationContext, TAG)
            val result = OpenBubblesOps.clearAttachments(TAG)
            Log.i(TAG, "doWork: cleared OB attachments (was ${result.bytesFreedDisplay})")
            Result.success()
        } catch (e: IllegalStateException) {
            // Foreground guard tripped — caller is using OpenBubbles
            // right now. Skip cleanly so the periodic schedule stays
            // alive; next nightly tick will try again.
            Log.i(TAG, "doWork: deferred — ${e.message}")
            Result.success()
        } catch (e: Exception) {
            // Any other failure (root unavailable, find/rm error) —
            // log and succeed so the periodic worker isn't dropped
            // from WorkManager. We'll get another shot tomorrow night.
            Log.w(TAG, "doWork: cleanup failed — ${e.javaClass.simpleName}: ${e.message}")
            Result.success()
        }
    }

    companion object {
        /**
         * Pre-v2 work name from when the worker fired weekly at 2 AM.
         * Cancelled in [schedule] so devices upgrading from an older
         * build don't end up with both schedules queued.
         */
        private const val OLD_WORK_NAME = "openbubbles_attachment_cleanup"
        private const val WORK_NAME = "openbubbles_attachment_cleanup_v2"
        private const val PERIOD_DAYS = 1L
        private const val TARGET_HOUR_LOCAL = 4  // 4 AM local time

        /**
         * Schedules the nightly cleanup. First fire is anchored to the
         * next 4 AM local time, subsequent fires are 24 hours apart.
         * [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling on every
         * boot (we are the HOME launcher) is a no-op once queued.
         */
        fun schedule(context: Context) {
            // Cancel the pre-v2 schedule (weekly at 2 AM) so a device
            // upgrading from an older build doesn't end up with both
            // schedules queued. No-op on devices that never had the
            // old entry.
            WorkManager.getInstance(context).cancelUniqueWork(OLD_WORK_NAME)

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
                "scheduled openbubbles attachment cleanup every $PERIOD_DAYS day(s), " +
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
