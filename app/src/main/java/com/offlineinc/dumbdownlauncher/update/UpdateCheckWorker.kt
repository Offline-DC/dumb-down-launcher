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
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        return try {
            val latest = UpdateChecker.fetchLatest()

            // Check launcher update
            val launcherInfo = latest["dumb-down-launcher"]
            if (launcherInfo != null && launcherInfo.versionCode > BuildConfig.VERSION_CODE) {
                UpdateNotificationManager.notify(
                    context = context,
                    notificationId = UpdateNotificationManager.NOTIFICATION_ID_LAUNCHER,
                    appKey = "dumb-down-launcher",
                    appDisplayName = "Dumb Down Launcher",
                    versionName = launcherInfo.versionName,
                    downloadUrl = launcherInfo.downloadUrl,
                )
            }

            // Check contacts sync update (only if installed)
            val contactsInfo = latest["dumb-contacts-sync"]
            if (contactsInfo != null) {
                val installedCode = getInstalledVersionCode("com.offlineinc.dumbcontactsync")
                if (installedCode != null && contactsInfo.versionCode > installedCode) {
                    UpdateNotificationManager.notify(
                        context = context,
                        notificationId = UpdateNotificationManager.NOTIFICATION_ID_CONTACTS,
                        appKey = "dumb-contacts-sync",
                        appDisplayName = "Dumb Contacts Sync",
                        versionName = contactsInfo.versionName,
                        downloadUrl = contactsInfo.downloadUrl,
                    )
                }
            }

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
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Run the update check immediately on the calling thread (must be off main thread).
         * Returns true if at least one update notification was posted.
         */
        fun runNow(context: Context): Boolean {
            var found = false
            try {
                val latest = UpdateChecker.fetchLatest()

                val launcherInfo = latest["dumb-down-launcher"]
                if (launcherInfo != null && launcherInfo.versionCode > BuildConfig.VERSION_CODE) {
                    UpdateNotificationManager.notify(
                        context = context,
                        notificationId = UpdateNotificationManager.NOTIFICATION_ID_LAUNCHER,
                        appKey = "dumb-down-launcher",
                        appDisplayName = "Dumb Launcher",
                        versionName = launcherInfo.versionName,
                        downloadUrl = launcherInfo.downloadUrl,
                    )
                    found = true
                }

                val contactsInfo = latest["dumb-contacts-sync"]
                if (contactsInfo != null) {
                    val installedCode = try {
                        val info = context.packageManager.getPackageInfo("com.offlineinc.dumbcontactsync", 0)
                        PackageInfoCompat.getLongVersionCode(info).toInt()
                    } catch (_: Exception) { null }
                    if (installedCode != null && contactsInfo.versionCode > installedCode) {
                        UpdateNotificationManager.notify(
                            context = context,
                            notificationId = UpdateNotificationManager.NOTIFICATION_ID_CONTACTS,
                            appKey = "dumb-contacts-sync",
                            appDisplayName = "Dumb Contacts Sync",
                            versionName = contactsInfo.versionName,
                            downloadUrl = contactsInfo.downloadUrl,
                        )
                        found = true
                    }
                }
            } catch (_: Exception) { }
            return found
        }
    }
}
