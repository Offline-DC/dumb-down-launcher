package com.offlineinc.dumbdownlauncher.whatsapp

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val TAG = "WAAttachmentWorker"

/**
 * Nightly cleanup of WhatsApp media — every file in `.Links/`,
 * `WhatsApp Images/`, and `WhatsApp Video/` gets wiped each run.
 * Unconditional: no rolling window, no age filter.
 *
 * The media is removed from THIS device only. WhatsApp keeps the
 * messages themselves intact, and the originals stay on the user's
 * other devices and on WhatsApp's CDN within retention — so anything
 * deleted here can still be re-fetched on demand from the primary
 * phone or by tapping the thumbnail in a chat.
 *
 * Pairs with the autodownload-disable migration applied via
 * `whatsapp_setup_v1` in [com.offlineinc.dumbdownlauncher.DumbDownApp] —
 * and which this worker now also **re-asserts** on every run, so a
 * WhatsApp update or in-app toggle can cause at most one day of
 * auto-downloads before being papered back over.
 *
 * Voice notes, documents, animated GIFs, audio, stickers, and the
 * `.nomedia` sentinels are preserved — only the three high-churn,
 * easily-redownloaded categories get wiped (see [WhatsAppOps] for
 * rationale).
 *
 * Runs once every 24 hours, anchored to the next 4 AM local time — the
 * unified storage-cleanup window across all workers. Phone is idle,
 * no calls landing.
 *
 * Two-step run:
 *   1. [WhatsAppOps.applyAutoDownloadMaskZero] re-asserts the three
 *      auto-download bitmasks to 0. No-ops cleanly if already correct.
 *      Killing WhatsApp here is fine; user is asleep at 4 AM.
 *   2. [WhatsAppOps.clearOldAttachments] wipes every photo/video in
 *      the three target media subdirs.
 */
class WhatsAppAttachmentCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            // Re-apply the autodownload-off prefs first. Throws if
            // WhatsApp is currently focused — we catch below and defer
            // to the next nightly tick.
            try {
                WhatsAppOps.applyAutoDownloadMaskZero(applicationContext, TAG)
            } catch (e: IllegalStateException) {
                Log.i(TAG, "doWork: prefs re-apply deferred — ${e.message}")
                // Don't return — clearing media still works even if
                // WhatsApp is focused (different rationale, see
                // WhatsAppOps.clearOldAttachments).
            }
            val result = WhatsAppOps.clearOldAttachments(CUTOFF_DAYS, TAG)
            Log.i(
                TAG,
                "doWork: deleted ${result.filesDeleted} file(s) (was ${result.bytesFreedDisplay})"
            )
            Result.success()
        } catch (e: Exception) {
            // Any failure (root unavailable, find/rm error) — log and
            // succeed so the periodic worker isn't dropped from
            // WorkManager. We'll get another shot tomorrow night.
            Log.w(TAG, "doWork: cleanup failed — ${e.javaClass.simpleName}: ${e.message}")
            Result.success()
        }
    }

    companion object {
        /**
         * Pre-v2 work name from when the worker ran at 2 AM with a
         * 7-day rolling retention. Cancelled in [schedule] so devices
         * upgrading from an older build don't end up with both
         * schedules queued.
         */
        private const val OLD_WORK_NAME = "whatsapp_attachment_cleanup"
        private const val WORK_NAME = "whatsapp_attachment_cleanup_v2"
        /** Nightly cadence (24 h between fires). */
        private const val PERIOD_DAYS = 1L
        /**
         * Age cutoff in days. When `>= 0`, only files strictly older
         * than this many days get deleted (the `find -mtime +N`
         * predicate). When negative, the predicate is dropped entirely
         * and the cleanup removes every photo/video in the target
         * subdirs — including ones received within the last 24 h.
         * History: 7 → 0 → -1 → 7 → -1. The current unconditional
         * shape matches the user's intent of "delete all whatsapp
         * media every night, the originals stay on other devices."
         */
        private const val CUTOFF_DAYS = -1
        private const val TARGET_HOUR_LOCAL = 4  // 4 AM local time

        /**
         * Schedules the nightly cleanup. First fire is anchored to the
         * next 4 AM local time, subsequent fires are 24 hours apart.
         * [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling on every
         * boot (we are the HOME launcher) is a no-op once queued.
         *
         * On upgrade from a build that scheduled this worker under
         * [OLD_WORK_NAME] with a different period / hour, the explicit
         * `cancelUniqueWork(OLD_WORK_NAME)` call below drops that stale
         * entry so the new schedule takes effect.
         */
        fun schedule(context: Context) {
            // Cancel the pre-v2 schedule (2 AM / 7-day retention) so a
            // device upgrading from an older build doesn't end up with
            // both schedules queued. No-op on devices that never had
            // the old entry.
            WorkManager.getInstance(context).cancelUniqueWork(OLD_WORK_NAME)

            val initialDelayMs = millisUntilNextTargetHour()
            val request = PeriodicWorkRequestBuilder<WhatsAppAttachmentCleanupWorker>(
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
                "scheduled whatsapp attachment cleanup every $PERIOD_DAYS day(s), " +
                    "first run in ~${initialDelayMs / 60_000} min"
            )
        }

        /** Cancels the periodic cleanup. Currently only used by tests. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Returns the milliseconds remaining until the next
         * [TARGET_HOUR_LOCAL] o'clock in the device's local timezone. If
         * the target hour has already passed today, rolls forward to
         * tomorrow.
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
