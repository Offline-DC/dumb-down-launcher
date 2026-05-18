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

private const val TAG = "SpotifyOfflineWorker"

/**
 * Nightly **size-gated** wipe of Spotify's on-disk cache at
 * `/data/user_de/0/com.spotify.music/Android/data/com.spotify.music/files/spotifycache`.
 *
 * Why nightly auto: this dir grows to 1.3 GB on real devices via streaming
 * cache (just-listened tracks Spotify decided to keep), and Spotify
 * Android exposes no cap — the `storage.size=` prefs trick that works on
 * desktop is silently ignored on Android (tested against both the flat
 * `files/settings/prefs` and `shared_prefs/spotify_preferences.xml`, the
 * cache reached 229 MB within one listening session of a 50 MB cap). See
 * STORAGE_PLAN.md §0.3.
 *
 * Why size-gated: the cache directory is a content-addressable store —
 * `Storage/<hex>/` holds both streaming cache and user-marked offline
 * downloads as hash-named chunks, distinguishable only by Spotify's
 * internal LevelDB. An external `find -delete` can't tell them apart.
 * Probing `offline.bnk` for size deltas after a download was inconclusive
 * (it stayed at 7901 bytes regardless), so the size gate is the chosen
 * compromise:
 *
 *  - Cache under [StorageCleanupOps.SPOTIFY_AUTO_CLEAR_THRESHOLD_BYTES]
 *    (500 MB) → no wipe. Users with no downloads + light listening, or
 *    a handful of downloaded albums, stay under this and are never
 *    touched by the auto path.
 *  - Cache at or above the threshold → wipe runs. The 1.3 GB bloat
 *    problem gets solved. Users who happen to have a lot of downloads
 *    AND a lot of cache pay a one-time Wi-Fi re-download — that cost
 *    is unavoidable given the on-disk layout.
 *
 * The MANUAL "Clear Spotify offline" button in [FreeUpSpaceScreen]
 * stays unconditional (calls [StorageCleanupOps.clearSpotifyOffline]
 * directly) — that's the path for users who explicitly want to nuke
 * everything including downloads.
 *
 * Single-step run — delegates to
 * [StorageCleanupOps.clearSpotifyOfflineIfOverThreshold], which when
 * over-threshold further delegates to the same `clearSpotifyOffline`
 * op that backs the manual button and the adb trigger receiver, so
 * `last_run_at_ms` + `bytes_freed` are recorded the same way and the
 * Free Up Space UI's "last cleared" subtitles stay consistent.
 *
 * Runs once every 24 hours, anchored to the next 4 AM local time — the
 * unified storage-cleanup window across all workers. No prefs-assertion
 * step (unlike the WhatsApp/OpenBubbles workers): Spotify exposes no
 * relevant pref for us to keep nudged.
 */
class SpotifyOfflineCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val result = StorageCleanupOps.clearSpotifyOfflineIfOverThreshold(
                applicationContext, TAG,
            )
            // Empty display string == under-threshold no-op. The op
            // already logged the size + threshold + decision; nothing
            // more to add here in that case.
            if (result.bytesFreedDisplay.isNotEmpty()) {
                Log.i(TAG, "doWork: wiped Spotify cache — freed=${result.bytesFreedDisplay}")
            }
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
        private const val WORK_NAME = "spotify_offline_cleanup_v1"

        /** Nightly cadence (24 h between fires). */
        private const val PERIOD_DAYS = 1L
        private const val TARGET_HOUR_LOCAL = 4  // 4 AM local time

        /**
         * Schedules the nightly cleanup. First fire is anchored to the
         * next 4 AM local time, subsequent fires are 24 hours apart.
         * [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling on every
         * boot (we are the HOME launcher) is a no-op once queued.
         */
        fun schedule(context: Context) {
            val initialDelayMs = millisUntilNextTargetHour()
            val request = PeriodicWorkRequestBuilder<SpotifyOfflineCleanupWorker>(
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
                "scheduled spotify offline cleanup every $PERIOD_DAYS day(s), " +
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
