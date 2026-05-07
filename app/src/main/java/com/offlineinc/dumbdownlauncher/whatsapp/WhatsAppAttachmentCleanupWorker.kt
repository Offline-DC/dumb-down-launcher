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
 * Nightly rolling cleanup of WhatsApp attachments older than 7 days,
 * restricted to the three subdirs in [WhatsAppOps.TARGET_SUBDIRS] —
 * `.Links/`, `WhatsApp Images/`, and `WhatsApp Video/`.
 *
 * Pairs with the autodownload-disable migration applied via
 * `whatsapp_setup_v1` in [com.offlineinc.dumbdownlauncher.DumbDownApp] —
 * together they keep WhatsApp's media footprint to roughly the last week
 * of stuff the user explicitly opened, in the categories where the
 * "view on primary phone" or "tap to redownload" recovery paths actually
 * work. Voice notes and documents are deliberately preserved (see
 * [WhatsAppOps.TARGET_SUBDIRS] for rationale).
 *
 * Runs once every 24 hours, anchored to the next 2 AM local time —
 * same hour as [com.offlineinc.dumbdownlauncher.calllog.CallLogCleanupWorker]
 * and [com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesAttachmentCleanupWorker].
 * Phone is idle, no calls landing, no one looking at the dialer. (OB's
 * cleanup is currently weekly; this one runs nightly because the
 * `.Links/` thumbnail dir grows continuously and benefits from a tighter
 * trim cycle.)
 *
 * Implementation delegates to [WhatsAppOps.clearOldAttachments], which
 * excludes `.nomedia` sentinels (critical: deleting them would unhide
 * WhatsApp's private/voice-note dirs in the system Photos app) and
 * skips any of the three target subdirs that don't exist yet on a fresh
 * install.
 *
 * Unlike its OpenBubbles sibling, this worker does NOT stop WhatsApp
 * first. WhatsApp's media lives on `/sdcard` rather than in `/data/data`,
 * so there's no in-memory cache to race; and the `-mtime +7` predicate
 * means we never touch a file currently being downloaded (those are
 * by definition < 8 days old).
 */
class WhatsAppAttachmentCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
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
        private const val WORK_NAME = "whatsapp_attachment_cleanup"
        /** Nightly cadence (24 h between fires). */
        private const val PERIOD_DAYS = 1L
        /**
         * Files strictly older than this many days are eligible for
         * deletion. `find -mtime +N` means "more than N*24h ago", so
         * `CUTOFF_DAYS = 7` deletes files at least 8 days old — which
         * matches "older than 7 days" in conversational English.
         */
        private const val CUTOFF_DAYS = 7
        private const val TARGET_HOUR_LOCAL = 2  // 2 AM local time

        /**
         * Schedules the nightly cleanup. First fire is anchored to the
         * next 2 AM local time, subsequent fires are 24 hours apart.
         * [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling on every
         * boot (we are the HOME launcher) is a no-op once queued.
         *
         * Note: if a prior install of this app queued this worker under
         * the same WORK_NAME but with a different period (e.g., during
         * dev iteration when the cadence was weekly), KEEP means the old
         * schedule survives. To force the new schedule on a device with
         * a stale entry, clear app data or call [cancel] + reschedule.
         */
        fun schedule(context: Context) {
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
