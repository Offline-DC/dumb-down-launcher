package com.offlineinc.dumbdownlauncher.update

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.offlineinc.dumbdownlauncher.BuildConfig
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import java.util.concurrent.TimeUnit

/**
 * Daily check-in for beta testers.
 *
 * Scheduled when the user opts in via long-press on the "updates" entry in
 * AllAppsActivity (see [PairingStore.betaTesterMode]). Each run:
 *
 *  1. Verifies the user is still opted in. The opt-out path
 *     ([AllAppsActivity] long-press toggle, or
 *     `PlatformPreferences.clearAll` from the Device Setup factory-reset
 *     long-press) cancels the unique work, but a race where the worker
 *     fires between flag-flip and cancel is possible — bail cleanly so we
 *     don't spam a user who just opted out.
 *
 *  2. Fetches the highest-versionCode release across stable + prerelease
 *     channels. If it beats [BuildConfig.VERSION_CODE], posts BOTH the
 *     high-priority "Update available" notification stable users see AND
 *     the low-priority beta-channel reminder pointing at the same build.
 *     If there's nothing newer, the worker exits quietly — no daily ping
 *     when the channel is idle, which keeps the shade clean on weeks with
 *     no beta cuts.
 *
 * Uses a 1-day periodic interval. WorkManager's flex window means the
 * fire time drifts a little day-to-day; that's fine — exact timing
 * doesn't matter for a daily ping, and using WorkManager (vs. AlarmManager)
 * keeps the reminder battery-friendly and Doze-aware.
 */
class BetaUpdateReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val store = PairingStore(context)
        if (!store.betaTesterMode) {
            // Race with opt-out — cancel the periodic work so we don't fire
            // again, and dismiss any reminder still on screen.
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            UpdateNotificationManager.cancel(
                context,
                UpdateNotificationManager.NOTIFICATION_ID_BETA_REMINDER,
            )
            return Result.success()
        }

        return try {
            // includePrereleases=true is the whole point of beta tester mode:
            // surface prerelease builds the same way stable releases get surfaced.
            val latest = UpdateChecker.fetchLatest(includePrereleases = true)
            val launcherInfo = latest["dumb-down-launcher"]

            if (launcherInfo != null && launcherInfo.versionCode > BuildConfig.VERSION_CODE) {
                // Mirror the high-priority "Update available" notification
                // path so the install shortcut works exactly like it does
                // outside beta mode. This survives even if the daily
                // reminder is dismissed.
                UpdateNotificationManager.notify(
                    context = context,
                    notificationId = UpdateNotificationManager.NOTIFICATION_ID_LAUNCHER,
                    appKey = "dumb-down-launcher",
                    appDisplayName = "Dumb Launcher (beta)",
                    versionName = launcherInfo.versionName,
                    downloadUrl = launcherInfo.downloadUrl,
                )
                UpdateNotificationManager.notifyBetaReminder(
                    context = context,
                    updateVersionName = launcherInfo.versionName,
                    updateDownloadUrl = launcherInfo.downloadUrl,
                )
            }
            // No-update branch is intentionally silent: the user opted into
            // beta tester mode to find out when beta builds drop, not to be
            // told "nothing new today" every 24 h. The next worker fire
            // will catch the build whenever a beta is actually published.

            Result.success()
        } catch (_: Exception) {
            // Network blip or GitHub hiccup — retry with WorkManager's
            // exponential backoff rather than burning the daily slot.
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "beta_update_reminder"

        /**
         * Idempotent schedule — uses [ExistingPeriodicWorkPolicy.KEEP] so
         * repeat callers (e.g. DumbDownApp.onCreate on every boot) never
         * disturb a running schedule. The first call enqueues the periodic
         * work; subsequent calls are no-ops.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BetaUpdateReminderWorker>(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Cancel any pending daily reminder. Safe to call when nothing is
         * scheduled — WorkManager treats it as a no-op.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
