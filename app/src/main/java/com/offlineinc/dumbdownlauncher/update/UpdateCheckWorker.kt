package com.offlineinc.dumbdownlauncher.update

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.offlineinc.dumbdownlauncher.BuildConfig
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import java.util.concurrent.TimeUnit

/**
 * Outcome of a manual update check via [UpdateCheckWorker.runNow]. The
 * three states let the caller distinguish "actually up to date" from
 * "couldn't reach GitHub" — previously both collapsed into Boolean
 * `false`, which presented as "Already up to date" even when the phone
 * had no cellular service.
 */
enum class UpdateCheckResult {
    /** A newer release was found and a notification has been posted. */
    UPDATE_FOUND,
    /** The release list was fetched successfully; nothing beats the installed version. */
    UP_TO_DATE,
    /**
     * Could not reach the GitHub releases API — either no active network
     * (Wi-Fi/cellular off, airplane mode, no service) or the HTTP request
     * failed mid-flight (DNS, TLS, timeout). Callers surface this as a
     * "failed to connect" message instead of a misleading "up to date".
     */
    NETWORK_ERROR,
}

class UpdateCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        return try {
            // Beta testers (opted in via long-press on "updates") are sandboxed
            // onto the beta channel: they see ONLY prerelease builds, never
            // stable ones, so prod releases can't overwrite a beta install.
            val betaChannel = PairingStore(context).betaTesterMode
            val latest = UpdateChecker.fetchLatest(betaChannel)

            // Collect all pending updates, then post a single combined notification
            // so the shade shows one tile instead of one per app.
            val pendingUpdates = mutableListOf<UpdateNotificationManager.PendingUpdate>()

            // Check launcher update
            val launcherInfo = latest["dumb-down-launcher"]
            if (launcherInfo != null && launcherInfo.versionCode > BuildConfig.VERSION_CODE) {
                pendingUpdates.add(UpdateNotificationManager.PendingUpdate(
                    appKey = "dumb-down-launcher",
                    versionName = launcherInfo.versionName,
                    downloadUrl = launcherInfo.downloadUrl,
                ))
            }

            // Contact sync is now integrated — no separate update check needed

            // Check snake update (only if installed)
            val snakeInfo = latest["snake"]
            if (snakeInfo != null) {
                val installedCode = getInstalledVersionCode("com.snake")
                if (installedCode != null && snakeInfo.versionCode > installedCode) {
                    pendingUpdates.add(UpdateNotificationManager.PendingUpdate(
                        appKey = "snake",
                        versionName = snakeInfo.versionName,
                        downloadUrl = snakeInfo.downloadUrl,
                    ))
                }
            }

            // Check OpenBubbles messaging update — only when the user picked
            // iOS for smart txt (OpenBubbles is their linked-device path) AND
            // it's actually installed. If they're on Android/none, don't bother.
            val openBubblesInfo = latest["openbubbles-messaging"]
            if (openBubblesInfo != null && PlatformPreferences.getChoice(context) == "ios") {
                val installedCode = getInstalledVersionCode("com.openbubbles.messaging")
                if (installedCode != null && openBubblesInfo.versionCode > installedCode) {
                    // Record the pending update (with versionCode) BEFORE posting,
                    // so the launch gate + reboot re-post key off it.
                    UpdateNotificationManager.markOpenBubblesUpdatePending(
                        context,
                        openBubblesInfo.downloadUrl,
                        openBubblesInfo.versionName,
                        openBubblesInfo.versionCode,
                    )
                    pendingUpdates.add(UpdateNotificationManager.PendingUpdate(
                        appKey = "openbubbles-messaging",
                        versionName = openBubblesInfo.versionName,
                        downloadUrl = openBubblesInfo.downloadUrl,
                    ))
                }
            }

            // Post one combined notification for all pending updates (or one
            // per-app tile if only a single update was found).
            UpdateNotificationManager.notifyCombined(context, pendingUpdates)

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun getInstalledVersionCode(packageName: String): Int? {
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            PackageInfoCompat.getLongVersionCode(info).toInt()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    companion object {
        private const val WORK_NAME = "update_check"

        fun schedule(context: Context) {
            // Weekly background check. UPDATE (not KEEP) so devices previously
            // scheduled on the old 30-day interval pick up the weekly cadence
            // without a reinstall.
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(7, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /**
         * Run the update check immediately on the calling thread (must be off main thread).
         *
         * Returns:
         *  - [UpdateCheckResult.UPDATE_FOUND] — a newer release was found and a
         *    notification has been posted (caller typically navigates the user
         *    to the notifications screen so they can install).
         *  - [UpdateCheckResult.UP_TO_DATE] — fetch succeeded; installed
         *    version is current.
         *  - [UpdateCheckResult.NETWORK_ERROR] — either no active network at
         *    call time, or the request to the GitHub releases API blew up
         *    mid-flight. Callers surface a "failed to connect" message
         *    instead of pretending everything's fine.
         */
        fun runNow(context: Context): UpdateCheckResult {
            // Fast path: if the OS reports no active internet-capable network,
            // skip the HTTP attempt entirely. Catches airplane mode / no
            // service / Wi-Fi off without paying the connect timeout.
            if (!NetworkUtils.isNetworkAvailable(context)) {
                return UpdateCheckResult.NETWORK_ERROR
            }
            return try {
                val betaChannel = PairingStore(context).betaTesterMode
                val latest = UpdateChecker.fetchLatest(betaChannel)

                val pendingUpdates = mutableListOf<UpdateNotificationManager.PendingUpdate>()

                val launcherInfo = latest["dumb-down-launcher"]
                if (launcherInfo != null && launcherInfo.versionCode > BuildConfig.VERSION_CODE) {
                    pendingUpdates.add(UpdateNotificationManager.PendingUpdate(
                        appKey = "dumb-down-launcher",
                        versionName = launcherInfo.versionName,
                        downloadUrl = launcherInfo.downloadUrl,
                    ))
                }

                // Contact sync is now integrated — no separate update check needed

                val snakeInfo = latest["snake"]
                if (snakeInfo != null) {
                    val installedCode = try {
                        val info = context.packageManager.getPackageInfo("com.snake", 0)
                        PackageInfoCompat.getLongVersionCode(info).toInt()
                    } catch (_: Exception) { null }
                    if (installedCode != null && snakeInfo.versionCode > installedCode) {
                        pendingUpdates.add(UpdateNotificationManager.PendingUpdate(
                            appKey = "snake",
                            versionName = snakeInfo.versionName,
                            downloadUrl = snakeInfo.downloadUrl,
                        ))
                    }
                }

                val openBubblesInfo = latest["openbubbles-messaging"]
                if (openBubblesInfo != null && PlatformPreferences.getChoice(context) == "ios") {
                    val installedCode = try {
                        val info = context.packageManager.getPackageInfo("com.openbubbles.messaging", 0)
                        PackageInfoCompat.getLongVersionCode(info).toInt()
                    } catch (_: Exception) { null }
                    if (installedCode != null && openBubblesInfo.versionCode > installedCode) {
                        UpdateNotificationManager.markOpenBubblesUpdatePending(
                            context,
                            openBubblesInfo.downloadUrl,
                            openBubblesInfo.versionName,
                            openBubblesInfo.versionCode,
                        )
                        pendingUpdates.add(UpdateNotificationManager.PendingUpdate(
                            appKey = "openbubbles-messaging",
                            versionName = openBubblesInfo.versionName,
                            downloadUrl = openBubblesInfo.downloadUrl,
                        ))
                    }
                }

                UpdateNotificationManager.notifyCombined(context, pendingUpdates)
                if (pendingUpdates.isNotEmpty()) UpdateCheckResult.UPDATE_FOUND else UpdateCheckResult.UP_TO_DATE
            } catch (_: Exception) {
                // fetchHighestRelease now propagates network/IO errors instead
                // of swallowing them. Anything reaching here (UnknownHostException,
                // SocketTimeoutException, SSLException, etc.) means the device
                // had an active network when we checked above but the request
                // still failed — most commonly a captive-portal Wi-Fi or a
                // cell tower that dropped mid-request.
                UpdateCheckResult.NETWORK_ERROR
            }
        }
    }
}
