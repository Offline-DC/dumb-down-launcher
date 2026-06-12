package com.offlineinc.dumbdownlauncher.update

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils

/**
 * Headless trampoline for taps on the "connect to wifi to update" notification
 * (posted by [UpdateNotificationManager.notifyWifiRequired]).
 *
 * Decides what to do at tap time, based on the *current* connectivity:
 *   - On Wi-Fi    → re-fire the [UpdateNotificationManager.ACTION_DOWNLOAD_APK]
 *                   broadcast to [DownloadAndInstallReceiver], which downloads
 *                   and installs the update. This is what fixes "I connected to
 *                   Wi-Fi but tapping still just reopened Wi-Fi settings".
 *   - Not on Wi-Fi → open the system Wi-Fi settings so the user can connect,
 *                   then come back and tap again.
 *
 * Why an Activity (not a branching broadcast): a notification tap that needs to
 * start an activity (Wi-Fi settings) is blocked on Android 10+/12+ unless the
 * PendingIntent targets an activity directly. Same rationale as
 * [com.offlineinc.dumbdownlauncher.wifinudge.WifiNudgeTapActivity]. No UI — does
 * its work in onCreate and finishes immediately. Works from both the system
 * shade (platform fires the contentIntent) and the in-launcher notifications
 * page (NotificationsActivity.onOpen calls `pi.send()` on the same intent).
 */
class WifiThenUpdateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(UpdateNotificationManager.EXTRA_DOWNLOAD_URL)
        val appKey = intent.getStringExtra(UpdateNotificationManager.EXTRA_APP_KEY) ?: "app"

        try {
            if (NetworkUtils.isOnWifi(this) && url != null) {
                // On Wi-Fi: re-fire the download. DownloadAndInstallReceiver
                // re-checks connectivity and kicks off the APK download (and
                // replaces this tile with the "downloading" one).
                sendBroadcast(
                    Intent(UpdateNotificationManager.ACTION_DOWNLOAD_APK).apply {
                        setPackage(packageName)
                        putExtra(UpdateNotificationManager.EXTRA_DOWNLOAD_URL, url)
                        putExtra(UpdateNotificationManager.EXTRA_APP_KEY, appKey)
                    }
                )
            } else {
                // Not on Wi-Fi (or missing URL): help the user get connected.
                startActivity(
                    Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "tap handling failed: ${e.message}", e)
        }

        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "WifiThenUpdate"
    }
}
