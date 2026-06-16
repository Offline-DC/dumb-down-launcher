package com.offlineinc.dumbdownlauncher.update

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Runs the nightly auto-update work ([AutoUpdateAlarmReceiver.runAutoUpdate]) in
 * a WorkManager job rather than inside the broadcast receiver.
 *
 * Why a Worker: a [android.content.BroadcastReceiver] — even one using
 * `goAsync()` — must complete within ~60 s or the system raises a broadcast ANR
 * and kills the process. The OpenBubbles update is a large split-APK zip whose
 * download easily exceeds that, so doing the download+install in the receiver
 * gets the process killed mid-download (observed: ANR at exactly 60 s). A Worker
 * runs off the main thread with a ~10 min window and is the same mechanism the
 * launcher already uses for its other nightly jobs (UpdateCheckWorker,
 * CallLogCleanupWorker, etc.).
 *
 * Best-effort: any failure returns success (not retry) so a transient problem
 * just waits for the next 4 AM rather than churning retries through the day —
 * matching the "skip until next 4 AM" behaviour.
 */
class AutoUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            AutoUpdateAlarmReceiver.runAutoUpdate(applicationContext)
            Result.success()
        } catch (_: Throwable) {
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "auto_update_run"

        /**
         * Enqueue a single run. KEEP policy: if a run is already pending/running
         * (e.g. a rapid double-trigger, or the 4 AM alarm overlapping a manual
         * test), don't stack a second one.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutoUpdateWorker>().build(),
            )
        }
    }
}
