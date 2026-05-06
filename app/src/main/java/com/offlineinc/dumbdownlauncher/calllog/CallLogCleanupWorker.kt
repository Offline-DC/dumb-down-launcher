package com.offlineinc.dumbdownlauncher.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val TAG = "CallLogCleanupWorker"

/**
 * Nightly cleanup of the system call log.
 *
 * Deletes any row in `content://call_log/calls` whose `date` is older than
 * [RETENTION_DAYS] (currently 7 days). The TCL Flip Go's `calllog.db` grows
 * to tens of thousands of rows over a few months of normal use and the
 * stock dialer / our launcher both get sluggish once the table is large
 * — pruning weekly keeps the table small and the launcher snappy.
 *
 * Runs once every 24 hours with the first fire anchored to the next
 * occurrence of [TARGET_HOUR_LOCAL] (2 AM local time). 2 AM is chosen
 * because the phone is almost certainly idle, no calls are landing, and
 * any WorkManager flex-window jitter still lands well before morning use.
 *
 * Designed to be cheap (single SQL DELETE through the official content
 * provider) and safe — deletion goes through the contacts provider rather
 * than poking the SQLite file directly, so the provider's own caches and
 * any concurrent dialer reads stay consistent.
 *
 * [doWork] delegates to [runCleanupNow] in the companion. The same helper
 * is used by [CallLogCleanupTriggerReceiver] for adb-driven manual runs,
 * so both code paths exercise identical logic.
 */
class CallLogCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        runCleanupNow(applicationContext)
        // Always success() — runCleanupNow logs its own outcome and we
        // don't want a transient failure to derail the periodic schedule.
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "call_log_cleanup"

        /** How many days of call history to keep. Anything older is deleted. */
        const val RETENTION_DAYS = 7L

        /** Target local-time hour for the nightly run. */
        private const val TARGET_HOUR_LOCAL = 2  // 2 AM local time

        /**
         * Synchronous cleanup. Self-grants `WRITE_CALL_LOG` via root if
         * needed, then deletes call-log rows older than [RETENTION_DAYS].
         *
         * Returns the number of rows deleted, or `-1` if the permission
         * couldn't be obtained, or `0` if the delete itself threw.
         *
         * Safe to call from any background thread; not safe on the main
         * thread (does I/O via `su` and the contacts provider).
         */
        @JvmStatic
        fun runCleanupNow(context: Context): Int {
            val ctx = context.applicationContext

            ensureWriteCallLogPermission(ctx)
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_CALL_LOG,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "runCleanupNow: WRITE_CALL_LOG not granted and root self-grant failed — skipping")
                return -1
            }

            val cutoffMs = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60L * 60L * 1000L
            return try {
                val deleted = ctx.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls.DATE} < ?",
                    arrayOf(cutoffMs.toString()),
                )
                Log.i(TAG, "runCleanupNow: deleted $deleted call-log rows older than $RETENTION_DAYS days (cutoff=$cutoffMs ms)")
                deleted
            } catch (e: SecurityException) {
                Log.w(TAG, "runCleanupNow: SecurityException — ${e.message}")
                0
            } catch (e: Exception) {
                Log.w(TAG, "runCleanupNow: delete failed — ${e.javaClass.simpleName}: ${e.message}")
                0
            }
        }

        /**
         * Self-grants `WRITE_CALL_LOG` via `su -c "pm grant …"` if it isn't
         * already granted. Mirrors `PhoneNumberReader.grantCallPhoneViaRoot`
         * and the rest of the codebase's `su` use. No-ops on devices without
         * root (the caller then logs and skips).
         */
        private fun ensureWriteCallLogPermission(ctx: Context) {
            val already = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_CALL_LOG,
            ) == PackageManager.PERMISSION_GRANTED
            if (already) return

            val cmd = "pm grant ${ctx.packageName} ${Manifest.permission.WRITE_CALL_LOG}"
            try {
                val proc = ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                // 5s rather than 3s: first su call after process start on a cold-
                // booted device can take 3–5s as Magisk warms up. Same rationale
                // as PhoneNumberReader.grantCallPhoneViaRoot.
                if (!proc.waitFor(5_000, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "ensureWriteCallLogPermission: su timed out after 5s")
                    proc.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "ensureWriteCallLogPermission: ${e.javaClass.simpleName} — ${e.message}")
            }
        }

        /**
         * Schedules the nightly cleanup. The first fire is anchored to the
         * next occurrence of 2 AM local time, then every 24 hours after that.
         *
         * Uses [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling on every
         * boot (we are the HOME launcher, so [com.offlineinc.dumbdownlauncher.DumbDownApp.onCreate]
         * runs on every boot) is a no-op — the existing schedule survives
         * reboots and version upgrades without resetting the timer.
         */
        fun schedule(context: Context) {
            val initialDelayMs = millisUntilNextTargetHour()
            val request = PeriodicWorkRequestBuilder<CallLogCleanupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "scheduled call-log cleanup every 24h, first run in ~${initialDelayMs / 60_000} min")
        }

        /** Cancels the periodic cleanup. Currently only used by tests. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Returns the milliseconds remaining until the next [TARGET_HOUR_LOCAL]
         * o'clock in the device's local timezone. If the target hour has
         * already passed today, rolls forward to tomorrow.
         *
         * Exposed `internal` for unit tests.
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
