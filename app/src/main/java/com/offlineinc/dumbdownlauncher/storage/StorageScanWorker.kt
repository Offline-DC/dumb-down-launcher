package com.offlineinc.dumbdownlauncher.storage

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val TAG = "StorageScanWorker"

/**
 * Nightly snapshot of every Free-Up-Space row's size, so the screen
 * loads instantly the next morning instead of paying the multi-second
 * `su` + `nsenter` startup cost on every open.
 *
 * Scheduled at 4 AM local time, the same unified storage-cleanup window
 * as [SpotifyOfflineCleanupWorker], [WhatsAppAttachmentCleanupWorker],
 * etc. — and deliberately ordered AFTER them by virtue of the WorkManager
 * scheduler's natural picking. The scan runs as a single periodic
 * worker so re-scheduling on every boot (we are the HOME launcher) is
 * a no-op once queued.
 *
 * Snapshot semantics: this worker only refreshes the cache, it does NOT
 * trigger any cleanup. The actual delete passes run via the per-target
 * cleanup workers; once those have finished trimming, this worker re-
 * measures the result and persists it via [StorageSnapshotCache] so
 * the morning's Free-Up-Space open reflects the post-cleanup state.
 *
 * The snapshot is not authoritative — callers that need the most-
 * accurate-possible figure (e.g. immediately after a manual clear)
 * should call [StorageCleanupOps.allSizesBytes] directly rather than
 * reading the cache. See [StorageSnapshotCache.loadCachedOrCompute]
 * for the freshness-gated read path.
 */
class StorageScanWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val snapshot = StorageCleanupOps.allSizesBytes()
            StorageSnapshotCache.save(applicationContext, snapshot)
            Log.i(
                TAG,
                "doWork: refreshed snapshot — " +
                    "sizesMB=${snapshot.sizesByTarget.mapValues { it.value / (1024L * 1024L) }
                        .mapKeys { it.key.logKey }}"
            )
            Result.success()
        } catch (e: Exception) {
            // Any failure (root unavailable, find/du error) — log and
            // succeed so the periodic worker isn't dropped from
            // WorkManager. We'll get another shot tomorrow night, and
            // the live-compute fallback in
            // [StorageSnapshotCache.loadCachedOrCompute] keeps the UI
            // working in the meantime.
            Log.w(TAG, "doWork: scan failed — ${e.javaClass.simpleName}: ${e.message}")
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "storage_scan_v1"

        /** Nightly cadence (24 h between fires). */
        private const val PERIOD_DAYS = 1L

        /**
         * 4 AM local time — same as the cleanup workers, intentionally.
         * Running at the same hour means whichever cleanup finishes last
         * is followed promptly by the scan, so the cached figure
         * reflects the freshly-trimmed state. WorkManager doesn't
         * guarantee ordering when multiple jobs share an hour, but on
         * a flip phone the three cleanup workers finish in seconds —
         * by the time this worker's su shell warms up they're typically
         * already done.
         */
        private const val TARGET_HOUR_LOCAL = 4

        /**
         * Schedules the nightly scan. First fire is anchored to the
         * next 4 AM local time, subsequent fires are 24 hours apart.
         * [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling on every
         * boot is a no-op once queued.
         */
        fun schedule(context: Context) {
            val initialDelayMs = millisUntilNextTargetHour()
            val request = PeriodicWorkRequestBuilder<StorageScanWorker>(
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
                "scheduled storage scan every $PERIOD_DAYS day(s), " +
                    "first run in ~${initialDelayMs / 60_000} min"
            )
        }

        /** Cancels the periodic scan. Currently only used by tests. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Returns the milliseconds remaining until the next
         * [TARGET_HOUR_LOCAL] o'clock in the device's local timezone.
         * If the target hour has already passed today, rolls forward
         * to tomorrow.
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

/**
 * Tiny key/value cache for the most-recent storage-size snapshot, so
 * the Free Up Space and Storage Info screens can render their numbers
 * immediately on open instead of waiting on a multi-second `su` shell.
 *
 * Backed by a dedicated SharedPreferences file (`storage_snapshot`) so
 * it stays separate from the deliberately-stateless [StorageCleanupOps]
 * — that object stopped writing the per-target "last cleared" history
 * earlier in this branch, and the snapshot cache is a fundamentally
 * different shape (one Long per Target, plus a single captured-at
 * timestamp, written atomically as a set).
 *
 * Freshness: the cache is considered usable when its timestamp is
 * within the last 24 hours. Anything older falls back to a live
 * compute via [StorageCleanupOps.allSizesBytes].
 */
object StorageSnapshotCache {

    private const val PREFS_NAME = "storage_snapshot"
    private const val KEY_CAPTURED_AT_MS = "captured_at_ms"

    /**
     * Maximum age a cached snapshot may have before callers should
     * recompute. 24 h matches the nightly worker's cadence — so a
     * snapshot persisted at 4 AM is fresh for the whole day, and the
     * next open after midnight falls through to a live compute (which
     * the next 4 AM worker re-caches).
     */
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    /** Persists [snapshot] to the cache, stamping it with `now`. */
    @JvmStatic
    fun save(context: Context, snapshot: StorageCleanupOps.SizesSnapshot) {
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        for (t in StorageCleanupOps.Target.values()) {
            edit.putLong(t.logKey, snapshot.sizesByTarget[t] ?: 0L)
        }
        edit.putLong(KEY_CAPTURED_AT_MS, System.currentTimeMillis())
        edit.apply()
    }

    /**
     * Returns the cached snapshot only if it's younger than [MAX_AGE_MS].
     * `null` when absent or stale — caller should compute live and then
     * call [save] to refresh.
     */
    @JvmStatic
    fun loadFresh(context: Context): StorageCleanupOps.SizesSnapshot? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val capturedAt = prefs.getLong(KEY_CAPTURED_AT_MS, 0L)
        if (capturedAt <= 0L) return null
        val age = System.currentTimeMillis() - capturedAt
        if (age < 0 || age > MAX_AGE_MS) return null
        val sizes = mutableMapOf<StorageCleanupOps.Target, Long>()
        for (t in StorageCleanupOps.Target.values()) {
            sizes[t] = prefs.getLong(t.logKey, 0L)
        }
        // perSectionMs / totalElapsedMs aren't persisted — the cache is
        // about UI render latency, not the original scan timings.
        return StorageCleanupOps.SizesSnapshot(
            sizesByTarget = sizes,
            totalElapsedMs = 0L,
            perSectionMs = emptyMap(),
        )
    }

    /**
     * Returns a fresh cached snapshot if available, otherwise computes
     * a live one via [StorageCleanupOps.allSizesBytes] AND persists it
     * so the next caller benefits. This is the path Free Up Space's
     * initial load should use; the post-clear refresh path should
     * call [recomputeAndCache] directly to bypass the freshness gate.
     */
    @JvmStatic
    fun loadCachedOrCompute(context: Context): StorageCleanupOps.SizesSnapshot {
        loadFresh(context)?.let { return it }
        val snapshot = StorageCleanupOps.allSizesBytes()
        save(context, snapshot)
        return snapshot
    }

    /**
     * Forces a live recompute and updates the cache. Use after a
     * manual cleanup action where the cached snapshot is now stale
     * by design.
     */
    @JvmStatic
    fun recomputeAndCache(context: Context): StorageCleanupOps.SizesSnapshot {
        val snapshot = StorageCleanupOps.allSizesBytes()
        save(context, snapshot)
        return snapshot
    }
}
