package com.offlineinc.dumbdownlauncher.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import java.io.File

class DownloadAndInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UpdateNotificationManager.ACTION_DOWNLOAD_APK -> {
                val url = intent.getStringExtra(UpdateNotificationManager.EXTRA_DOWNLOAD_URL)
                    ?: return
                val appKey = intent.getStringExtra(UpdateNotificationManager.EXTRA_APP_KEY)
                    ?: "app"

                // OpenBubbles-specific guardrails — see the class doc on
                // [startDownload] for why they're scoped to this app.
                if (appKey == "openbubbles-messaging") {
                    if (!NetworkUtils.isOnWifi(context)) {
                        // Replace the "Update available" tile with a "needs
                        // Wi-Fi" tile so the user notices, and bail without
                        // queueing a cellular download.
                        UpdateNotificationManager.notifyWifiRequired(context, appKey)
                        return
                    }
                    // Skip the "trust this source / allow from unknown sources"
                    // consent dialog by granting REQUEST_INSTALL_PACKAGES to the
                    // launcher itself before kicking off the install. Best-effort
                    // — if su is unavailable (e.g. an un-rooted dev build) the
                    // user will just see the normal trust prompt and proceed
                    // the old-fashioned way.
                    preGrantInstallPermission(context)
                }

                startDownload(context, url, appKey)
            }
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                for (appKey in APP_KEYS) {
                    val key = downloadIdKey(appKey)
                    val savedId = prefs.getLong(key, -1L)
                    if (downloadId == savedId) {
                        prefs.edit().remove(key).apply()
                        triggerInstall(context, downloadId, appKey)
                        break
                    }
                }
            }
        }
    }

    private fun startDownload(context: Context, url: String, appKey: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "$appKey.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading $appKey update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")
        val downloadId = dm.enqueue(request)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(downloadIdKey(appKey), downloadId)
            .apply()
        UpdateNotificationManager.notifyDownloading(context, appKey)
    }

    private fun triggerInstall(context: Context, downloadId: Long, appKey: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)
        if (!cursor.moveToFirst()) {
            cursor.close()
            UpdateNotificationManager.notifyFailed(context, appKey)
            return
        }
        val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
        val localUri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
        cursor.close()

        if (status != DownloadManager.STATUS_SUCCESSFUL || localUri == null) {
            UpdateNotificationManager.notifyFailed(context, appKey)
            return
        }

        val apkFile = File(Uri.parse(localUri).path ?: run {
            UpdateNotificationManager.notifyFailed(context, appKey)
            return
        })
        val contentUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } catch (_: Exception) {
            UpdateNotificationManager.notifyFailed(context, appKey)
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(installIntent)
            UpdateNotificationManager.cancel(context, notificationIdForKey(appKey))
        } catch (_: Exception) {
            UpdateNotificationManager.notifyFailed(context, appKey)
        }
    }

    /**
     * Pre-grant the install-from-unknown-sources permission to the launcher so
     * the OpenBubbles update install doesn't stall on a "trust this source"
     * consent dialog. Runs `appops set <pkg> REQUEST_INSTALL_PACKAGES allow`
     * via su; failure is logged but swallowed because we don't want to block
     * the update on missing root — the worst case is the user sees the normal
     * trust prompt, which is exactly the pre-change behaviour.
     */
    private fun preGrantInstallPermission(context: Context) {
        val pkg = context.packageName
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "appops set $pkg REQUEST_INSTALL_PACKAGES allow")
            )
            // Drain stdout/stderr so the process can exit cleanly. We don't
            // care about the contents — appops is silent on success.
            proc.inputStream.bufferedReader().use { it.readText() }
            proc.errorStream.bufferedReader().use { it.readText() }
            proc.waitFor()
        } catch (e: Exception) {
            Log.w("UpdateReceiver", "preGrantInstallPermission failed: ${e.message}")
        }
    }

    companion object {
        private const val PREFS_NAME = "update_prefs"
        private val APP_KEYS = listOf("dumb-down-launcher", "snake", "openbubbles-messaging")
        private fun downloadIdKey(appKey: String) = "pending_download_id_$appKey"

        fun notificationIdForKey(appKey: String) = when (appKey) {
            "dumb-down-launcher" -> UpdateNotificationManager.NOTIFICATION_ID_LAUNCHER
            "snake" -> UpdateNotificationManager.NOTIFICATION_ID_SNAKE
            "openbubbles-messaging" -> UpdateNotificationManager.NOTIFICATION_ID_OPENBUBBLES
            else -> UpdateNotificationManager.NOTIFICATION_ID_LAUNCHER
        }
    }
}
