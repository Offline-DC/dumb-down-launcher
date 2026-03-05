package com.offlineinc.dumbdownlauncher.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

class DownloadAndInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UpdateNotificationManager.ACTION_DOWNLOAD_APK -> {
                val url = intent.getStringExtra(UpdateNotificationManager.EXTRA_DOWNLOAD_URL)
                    ?: return
                val appKey = intent.getStringExtra(UpdateNotificationManager.EXTRA_APP_KEY)
                    ?: "app"
                startDownload(context, url, appKey)
            }
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val savedId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
                if (downloadId == savedId) {
                    prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                    triggerInstall(context, downloadId)
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
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .apply()
    }

    private fun triggerInstall(context: Context, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)
        if (!cursor.moveToFirst()) {
            cursor.close()
            return
        }
        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
        val status = cursor.getInt(statusIndex)
        val localUri = cursor.getString(localUriIndex)
        cursor.close()

        if (status != DownloadManager.STATUS_SUCCESSFUL || localUri == null) return

        val apkFile = File(Uri.parse(localUri).path ?: return)
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    companion object {
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_DOWNLOAD_ID = "pending_download_id"
    }
}
