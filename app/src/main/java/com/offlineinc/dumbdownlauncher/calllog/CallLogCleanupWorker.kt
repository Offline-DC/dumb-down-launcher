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
 * Keeps the newest [MAX_KEPT_ENTRIES] (currently 50) rows in
 * `content://call_log/calls` and deletes the rest. The TCL Flip Go's
 * `calllog.db` grows to tens of thousands of rows over a few months of
 * normal use and the stock dialer / our launcher both get sluggish once
 * the table is large — capping the table at a small fixed size keeps the
 * launcher snappy regardless of call frequency.
 *
 * Implemented as a two-step against the content provider: first find the
 * `date` of the [MAX_KEPT_ENTRIES]-th newest row, then `DELETE WHERE date <
 * that`. Going through the provider (rather than poking the SQLite file
 * directly) keeps the provider's caches and any concurrent dialer reads
 * consistent.
 *
 * Runs once every 24 hours with the first fire anchored to the next
 * occurrence of [TARGET_HOUR_LOCAL] (2 AM local time). 2 AM is chosen
 * because the phone is almost certainly idle, no calls are landing, and
 * any WorkManager flex-window jitter still lands well before morning use.
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

        /**
         * How many of the most recent call-log rows to keep. Anything older
         * than the [MAX_KEPT_ENTRIES]-th newest row is deleted on each run.
         */
        const val MAX_KEPT_ENTRIES = 50

        /** Target local-time hour for the nightly run. */
        private const val TARGET_HOUR_LOCAL = 2  // 2 AM local time

        /**
         * Synchronous cleanup. Self-grants `WRITE_CALL_LOG` via root if
         * needed, then trims `call_log` down to the newest
         * [MAX_KEPT_ENTRIES] rows.
         *
         * Two-step: first looks up the `date` of the
         * [MAX_KEPT_ENTRIES]-th newest row (via `ORDER BY date DESC LIMIT
         * 1 OFFSET MAX_KEPT_ENTRIES-1`), then deletes every row strictly
         * older than that. If the table already has ≤ [MAX_KEPT_ENTRIES]
         * rows the cutoff query returns nothing and we no-op.
         *
         * Returns the number of rows deleted, `0` if there was nothing to
         * trim or the delete itself threw, or `-1` if the permission
         * couldn't be obtained.
         *
         * Safe to call from any background thread; not safe on the main
         * thread (does I/O via `su` and the contacts provider).
         */
        @JvmStatic
        fun runCleanupNow(context: Context): Int {
            val ctx = context.applicationContext

            // Both READ and WRITE are needed: READ for the cutoff lookup
            // (Step 1, ContentResolver.query) and WRITE for the actual
            // delete (Step 2). Self-grant both via root if missing.
            ensureCallLogPermissions(ctx)
            val readGranted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.READ_CALL_LOG,
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_CALL_LOG,
            ) == PackageManager.PERMISSION_GRANTED
            if (!readGranted || !writeGranted) {
                Log.w(TAG, "runCleanupNow: call-log perms not granted (read=$readGranted write=$writeGranted) and root self-grant failed — skipping")
                return -1
            }

            // Step 1: find the date of the Nth-newest row. The CallLog
            // provider passes `sortOrder` straight through to SQLite, so
            // standard `ORDER BY ... LIMIT 1 OFFSET N-1` works. If the
            // table has < MAX_KEPT_ENTRIES rows the cursor is empty.
            val cutoffDate: Long? = try {
                ctx.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.DATE),
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC LIMIT 1 OFFSET ${MAX_KEPT_ENTRIES - 1}",
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            } catch (e: Exception) {
                Log.w(TAG, "runCleanupNow: cutoff query failed — ${e.javaClass.simpleName}: ${e.message}")
                return 0
            }

            if (cutoffDate == null) {
                Log.i(TAG, "runCleanupNow: ≤ $MAX_KEPT_ENTRIES rows in call log — nothing to delete")
                return 0
            }

            // Step 2: delete everything strictly older than the cutoff. We
            // use `<` rather than `<=` so any tied-timestamp rows at the
            // boundary are kept — date is in milliseconds so ties are
            // essentially impossible in practice, but keeping a couple
            // extra rows is benign while accidentally pruning the 50th
            // would not be.
            return try {
                val deleted = ctx.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls.DATE} < ?",
                    arrayOf(cutoffDate.toString()),
                )
                Log.i(TAG, "runCleanupNow: deleted $deleted call-log rows, keeping newest $MAX_KEPT_ENTRIES (cutoff date=$cutoffDate ms)")
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
         * Self-grants `READ_CALL_LOG` and `WRITE_CALL_LOG` via
         * `su -c "pm grant …"` for any of them that aren't already
         * granted. Mirrors `PhoneNumberReader.grantCallPhoneViaRoot` and
         * the rest of the codebase's `su` use. No-ops on devices without
         * root (the caller then logs and skips).
         */
        private fun ensureCallLogPermissions(ctx: Context) {
            val perms = listOf(
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALL_LOG,
            )
            for (perm in perms) {
                val already = ContextCompat.checkSelfPermission(ctx, perm) ==
                    PackageManager.PERMISSION_GRANTED
                if (already) continue
                grantViaRoot(ctx, perm)
            }
        }

        /**
         * Runs `su -c "pm grant <pkg> <perm>"` once with a 5 s timeout.
         * 5 s rather than 3 s: first su call after process start on a
         * cold-booted device can take 3–5 s as Magisk warms up — same
         * rationale as `PhoneNumberReader.grantCallPhoneViaRoot`.
         */
        private fun grantViaRoot(ctx: Context, perm: String) {
            val cmd = "pm grant ${ctx.packageName} $perm"
            try {
                val proc = ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                if (!proc.waitFor(5_000, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "grantViaRoot($perm): su timed out after 5s")
                    proc.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "grantViaRoot($perm): ${e.javaClass.simpleName} — ${e.message}")
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
