package com.offlineinc.dumbdownlauncher.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
 * gets the process killed mid-download (observed: ANR at exactly 60 s).
 *
 * Why **expedited + foreground + wake/Wi-Fi locks**: at 4 AM the phone is
 * asleep and (if unplugged) in Doze. A plain background worker leaves the app
 * idle, and DownloadManager defers an idle app's transfer to occasional
 * maintenance windows — observed overnight as a 5 MB APK taking ~9 min and
 * getting cancelled, while the identical download finished in seconds during
 * the day with the screen on. Running expedited promotes the app to a
 * foreground/active state (Doze-exempt, network granted) so DownloadManager
 * transfers immediately, just like it does awake; the PARTIAL_WAKE_LOCK keeps
 * the CPU from suspending mid-transfer and the Wi-Fi lock keeps the radio at
 * full power.
 *
 * Best-effort: any failure returns success (not retry) so a transient problem
 * just waits for the next 4 AM rather than churning retries through the day —
 * matching the "skip until next 4 AM" behaviour.
 */
class AutoUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun getForegroundInfo(): ForegroundInfo {
        val notification = UpdateNotificationManager.autoUpdateForegroundNotification(applicationContext)
        val id = UpdateNotificationManager.NOTIFICATION_ID_AUTO_UPDATE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    override fun doWork(): Result {
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        @Suppress("DEPRECATION") // FULL_HIGH_PERF is deprecated but still the way to keep the radio hot
        val wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKELOCK_TAG)
        return try {
            // Safety timeout > the download cap so a stuck run can't pin the CPU.
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
            wifiLock.acquire()
            AutoUpdateAlarmReceiver.runAutoUpdate(applicationContext)
            Result.success()
        } catch (_: Throwable) {
            Result.success()
        } finally {
            runCatching { if (wifiLock.isHeld) wifiLock.release() }
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
        }
    }

    companion object {
        private const val WORK_NAME = "auto_update_run"
        private const val WAKELOCK_TAG = "dumbdown:autoupdate"
        private const val WAKELOCK_TIMEOUT_MS = 15 * 60_000L

        /**
         * Enqueue a single run. KEEP policy: if a run is already pending/running
         * (e.g. a rapid double-trigger, or the 4 AM alarm overlapping a manual
         * test), don't stack a second one.
         *
         * Expedited so it runs promptly even in Doze. RUN_AS_NON_EXPEDITED_WORK_REQUEST
         * means if the expedited quota is exhausted it still runs, just as a
         * normal background job (no worse than the old behaviour).
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
