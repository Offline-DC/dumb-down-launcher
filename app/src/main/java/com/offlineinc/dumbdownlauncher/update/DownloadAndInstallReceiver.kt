package com.offlineinc.dumbdownlauncher.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import java.io.File

class DownloadAndInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Top-level guard: an uncaught throw in a manifest-declared
        // BroadcastReceiver takes down the whole launcher process. Catch
        // *Throwable* (not just Exception, so Errors are logged too), dump the
        // full stack, and swallow it so the launcher survives — and so the
        // crash is actually diagnosable from logcat.
        try {
            Log.i(TAG, "onReceive: action=${intent.action}")
            when (intent.action) {
                UpdateNotificationManager.ACTION_DOWNLOAD_APK -> {
                    val url = intent.getStringExtra(UpdateNotificationManager.EXTRA_DOWNLOAD_URL)
                    if (url == null) {
                        Log.w(TAG, "ACTION_DOWNLOAD_APK with no download URL — ignoring")
                        return
                    }
                    val appKey = intent.getStringExtra(UpdateNotificationManager.EXTRA_APP_KEY)
                        ?: "app"
                    Log.i(TAG, "download requested: appKey=$appKey url=$url")

                    // Cellular guard for the (large) OpenBubbles APK.
                    if (appKey == "openbubbles-messaging") {
                        val onWifi = NetworkUtils.isOnWifi(context)
                        Log.i(TAG, "openbubbles wifi check: onWifi=$onWifi")
                        if (!onWifi) {
                            UpdateNotificationManager.notifyWifiRequired(context, appKey, url)
                            return
                        }
                    }

                    // De-dupe: if a download/install for this app is already
                    // running, ignore the repeat tap instead of enqueuing again.
                    // Checked synchronously here so rapid taps can't race past it;
                    // cleared on terminal outcome (notifyFailed / notifyInstalled).
                    if (UpdateNotificationManager.isUpdateInProgress(context, appKey)) {
                        Log.i(TAG, "update already in progress for $appKey — ignoring repeat tap")
                        return
                    }
                    UpdateNotificationManager.markUpdateInProgress(context, appKey)

                    // startDownload() talks to DownloadManager, which can block
                    // or throw. Hand off to a background thread via goAsync()
                    // and funnel any failure into an "update failed"
                    // notification instead of crashing.
                    val pending = goAsync()
                    Thread {
                        try {
                            Log.i(TAG, "bg: starting download work for $appKey")
                            // NOTE: we do NOT grant REQUEST_INSTALL_PACKAGES at
                            // runtime — changing the launcher's own appop makes
                            // Android kill the launcher process. It's already
                            // granted once at provisioning (configure_dumbdown_
                            // launcher.sh), so the normal installer in
                            // triggerInstall() works with no prompt and no kill.
                            startDownload(context, url, appKey)
                            Log.i(TAG, "bg: download enqueued for $appKey")
                        } catch (t: Throwable) {
                            Log.e(TAG, "bg: start download failed for $appKey", t)
                            try {
                                UpdateNotificationManager.notifyFailed(context, appKey)
                            } catch (t2: Throwable) {
                                Log.e(TAG, "bg: notifyFailed also threw for $appKey", t2)
                            }
                        } finally {
                            try {
                                pending.finish()
                            } catch (t3: Throwable) {
                                Log.e(TAG, "bg: pending.finish() threw", t3)
                            }
                        }
                    }.start()
                }
                DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                    val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    Log.i(TAG, "download complete: id=$downloadId")
                    // triggerInstall() can stream a 200 MB zip into an install
                    // session — keep it off the BroadcastReceiver main thread.
                    val pending = goAsync()
                    Thread {
                        try {
                            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            for (appKey in APP_KEYS) {
                                val key = downloadIdKey(appKey)
                                val savedId = prefs.getLong(key, -1L)
                                if (downloadId == savedId) {
                                    Log.i(TAG, "download complete matched appKey=$appKey — installing")
                                    prefs.edit().remove(key).apply()
                                    triggerInstall(context, downloadId, appKey)
                                    break
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "download-complete handling failed", t)
                        } finally {
                            try {
                                pending.finish()
                            } catch (_: Throwable) {
                            }
                        }
                    }.start()
                }
                UpdateNotificationManager.ACTION_INSTALL_RESULT -> {
                    handleInstallResult(context, intent)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onReceive CRASHED (action=${intent.action}) — swallowed to keep launcher alive", t)
        }
    }

    private fun startDownload(context: Context, url: String, appKey: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: throw IllegalStateException("DownloadManager service unavailable (download provider disabled?)")
        // OpenBubbles is a .zip of split APKs; everything else is a single .apk.
        val isSplitZip = appKey == "openbubbles-messaging"
        val fileName = if (isSplitZip) "$appKey.zip" else "$appKey.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading $appKey update")
            // Hidden: the system's DownloadManager notification only shows
            // "time left" — we post our own progress notification instead
            // (bar + MB/%, see startProgressPolling). Requires the
            // DOWNLOAD_WITHOUT_NOTIFICATION permission in the manifest.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")
        Log.i(TAG, "startDownload: enqueueing $fileName from $url")
        val downloadId = dm.enqueue(request)
        Log.i(TAG, "startDownload: enqueued id=$downloadId for $appKey")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(downloadIdKey(appKey), downloadId)
            .apply()
        UpdateNotificationManager.notifyDownloading(context, appKey)
        startProgressPolling(context, downloadId, appKey)
    }

    /**
     * Poll DownloadManager ~1×/sec and mirror bytes-downloaded/total into the
     * "Downloading update" notification as a progress bar + "X / Y MB (Z%)"
     * text. Runs on a detached daemon thread (NOT tied to the broadcast's
     * goAsync window — downloads outlast it; the launcher process is
     * persistent so the thread survives). Exits when the download reaches a
     * terminal state (the ACTION_DOWNLOAD_COMPLETE receiver then takes over
     * the notification), when its row disappears (user cancelled), or after a
     * 30-min safety cap. Failures only stop the polling — never the download.
     */
    private fun startProgressPolling(context: Context, downloadId: Long, appKey: String) {
        Thread {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    ?: return@Thread
                val deadline = System.currentTimeMillis() + 30 * 60_000L
                while (System.currentTimeMillis() < deadline) {
                    val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                        ?: break
                    var status = -1
                    var downloaded = -1L
                    var total = -1L
                    cursor.use {
                        if (!it.moveToFirst()) return@Thread // row gone: cancelled/removed
                        status = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
                        downloaded = it.getLong(
                            it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                        total = it.getLong(
                            it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                    }
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL,
                        DownloadManager.STATUS_FAILED -> {
                            Log.i(TAG, "progress poll: terminal status=$status for $appKey — stopping")
                            return@Thread
                        }
                        else -> UpdateNotificationManager.notifyDownloadProgress(
                            context, appKey, downloaded, total
                        )
                    }
                    Thread.sleep(1_000L)
                }
                Log.w(TAG, "progress poll: deadline reached for $appKey — stopping")
            } catch (t: Throwable) {
                Log.w(TAG, "progress poll stopped for $appKey (download unaffected)", t)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun triggerInstall(context: Context, downloadId: Long, appKey: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            Log.e(TAG, "triggerInstall: DownloadManager unavailable for $appKey")
            UpdateNotificationManager.notifyFailed(context, appKey)
            return
        }
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)
        if (!cursor.moveToFirst()) {
            cursor.close()
            Log.e(TAG, "triggerInstall: no download row for id=$downloadId ($appKey)")
            UpdateNotificationManager.notifyFailed(context, appKey)
            return
        }
        val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
        val localUri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
        cursor.close()
        Log.i(TAG, "triggerInstall: $appKey status=$status localUri=$localUri")

        if (status != DownloadManager.STATUS_SUCCESSFUL || localUri == null) {
            Log.e(TAG, "triggerInstall: download not successful (status=$status) for $appKey")
            UpdateNotificationManager.notifyFailed(context, appKey)
            return
        }

        val apkFile = File(Uri.parse(localUri).path ?: run {
            Log.e(TAG, "triggerInstall: null path from localUri=$localUri ($appKey)")
            UpdateNotificationManager.notifyFailed(context, appKey)
            return
        })

        // OpenBubbles ships as a .zip of split APKs — install the whole set via
        // a PackageInstaller session (ACTION_VIEW can't install splits). The
        // success/failure result arrives asynchronously at handleInstallResult.
        if (appKey == "openbubbles-messaging") {
            val sizeMb = apkFile.length() / (1024 * 1024)
            val freeMb = (apkFile.parentFile?.freeSpace ?: -1L) / (1024 * 1024)
            Log.i(TAG, "triggerInstall: OB split-zip install size=${sizeMb}MB free=${freeMb}MB")
            // Swap the (now-frozen) download bar for an "Installing…" state for
            // the duration of the split write + commit.
            UpdateNotificationManager.notifyInstalling(context, appKey)
            val started = SplitApkInstaller.installFromZip(context, apkFile, appKey)
            if (!started) {
                Log.e(TAG, "triggerInstall: split install couldn't start for $appKey")
                UpdateNotificationManager.notifyFailed(context, appKey)
            }
            return
        }

        // Diagnostics: the system installer ("App not installed") doesn't tell
        // us *why*, so log how the downloaded APK compares to what's installed
        // (package, versionCode, signature) plus free space, which covers the
        // usual rejection causes: signature mismatch, version downgrade, and
        // insufficient storage.
        logInstallDiagnostics(context, apkFile, appKey)

        // Install via the normal system installer (ACTION_VIEW), exactly like
        // the launcher's own self-update. The launcher already holds the
        // REQUEST_INSTALL_PACKAGES appop (granted at provisioning), so this
        // installs with no "trust this source" prompt — and without any extra
        // on-disk copy of the (large) APK.
        val contentUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "triggerInstall: FileProvider.getUriForFile failed for $appKey", e)
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
            Log.i(TAG, "triggerInstall: launched installer for $appKey")
            UpdateNotificationManager.cancel(context, notificationIdForKey(appKey))
            // Handed off to the system installer (no result callback to us) —
            // release the in-progress guard so future updates aren't blocked.
            UpdateNotificationManager.clearUpdateInProgress(context, appKey)
        } catch (e: Exception) {
            Log.e(TAG, "triggerInstall: startActivity(installer) failed for $appKey", e)
            UpdateNotificationManager.notifyFailed(context, appKey)
        }
    }

    /**
     * Logs why the system installer might reject [apkFile]: package +
     * versionCode + signature of the APK vs the currently-installed app, plus
     * free space. Purely diagnostic; never throws. Deprecated GET_SIGNATURES is
     * fine here — it's the simplest cross-version way to compare signing certs
     * on Android 11.
     */
    @Suppress("DEPRECATION")
    private fun logInstallDiagnostics(context: Context, apkFile: File, appKey: String) {
        try {
            val pm = context.packageManager
            val sizeMb = apkFile.length() / (1024 * 1024)
            val freeMb = (apkFile.parentFile?.freeSpace ?: -1L) / (1024 * 1024)
            Log.i(TAG, "diag[$appKey]: apk size=${sizeMb}MB, free=${freeMb}MB at download dir")

            val archive = pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                android.content.pm.PackageManager.GET_SIGNATURES,
            )
            if (archive == null) {
                Log.e(TAG, "diag[$appKey]: APK could not be parsed (corrupt/incomplete download?)")
                return
            }
            val apkPkg = archive.packageName
            val apkVc = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(archive)
            val apkSig = archive.signatures?.firstOrNull()?.let { sigDigest(it) }
            Log.i(TAG, "diag[$appKey]: apk pkg=$apkPkg vc=$apkVc vn=${archive.versionName} sig=$apkSig")

            try {
                val inst = pm.getPackageInfo(apkPkg, android.content.pm.PackageManager.GET_SIGNATURES)
                val instVc = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(inst)
                val instSig = inst.signatures?.firstOrNull()?.let { sigDigest(it) }
                Log.i(TAG, "diag[$appKey]: installed pkg=$apkPkg vc=$instVc vn=${inst.versionName} sig=$instSig")
                if (apkSig != null && instSig != null && apkSig != instSig) {
                    Log.e(TAG, "diag[$appKey]: SIGNATURE MISMATCH (apk≠installed) — installer will reject; re-sign the release with the installed app's key")
                }
                if (apkVc in 0 until instVc) {
                    Log.e(TAG, "diag[$appKey]: VERSION DOWNGRADE apk vc=$apkVc < installed vc=$instVc — installer will reject")
                }
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                Log.i(TAG, "diag[$appKey]: $apkPkg not currently installed (fresh install)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "diag[$appKey]: install diagnostics failed: ${e.message}", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun sigDigest(sig: android.content.pm.Signature): String =
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.update(sig.toByteArray())
            md.digest().joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) {
            "?"
        }

    /**
     * Handles the async result broadcast from a [SplitApkInstaller] session.
     *   - PENDING_USER_ACTION → launch the system confirm dialog.
     *   - SUCCESS → cancel the update tile, delete the downloaded zip.
     *   - anything else → log the real INSTALL_FAILED_* message, notify failed,
     *     delete the zip.
     */
    private fun handleInstallResult(context: Context, intent: Intent) {
        val appKey = intent.getStringExtra(UpdateNotificationManager.EXTRA_APP_KEY) ?: "app"
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "install result: appKey=$appKey status=$status message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (t: Throwable) {
                        Log.e(TAG, "failed to launch install confirm dialog for $appKey", t)
                        UpdateNotificationManager.notifyFailed(context, appKey)
                    }
                } else {
                    Log.e(TAG, "PENDING_USER_ACTION but no confirm intent for $appKey")
                    UpdateNotificationManager.notifyFailed(context, appKey)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "install SUCCESS for $appKey")
                // Show a brief "Update installed" confirmation (replaces the
                // "Installing…" tile and self-dismisses) instead of silently
                // cancelling, so the user gets a clear "done" signal.
                UpdateNotificationManager.notifyInstalled(context, appKey)
                cleanupDownloadedFile(context, appKey)
                // OpenBubbles is now at the target build (>= MIN_SUPPORTED), so
                // apply the one-time "delete after 3 days" retention toggle now
                // — before its first post-update launch. The one-time flag makes
                // this idempotent; the accessibility OB→background trigger is a
                // fallback if this attempt defers.
                if (appKey == "openbubbles-messaging") {
                    com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesGate
                        .applyRetentionOnceAsync(context)
                }
            }
            else -> {
                Log.e(TAG, "install FAILED for $appKey status=$status: $message")
                UpdateNotificationManager.notifyFailed(context, appKey)
                cleanupDownloadedFile(context, appKey)
            }
        }
    }

    /** Best-effort delete of the downloaded artifact for [appKey]. */
    private fun cleanupDownloadedFile(context: Context, appKey: String) {
        try {
            val ext = if (appKey == "openbubbles-messaging") "zip" else "apk"
            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "$appKey.$ext",
            )
            if (file.exists() && file.delete()) {
                Log.i(TAG, "cleaned up downloaded $appKey.$ext")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cleanup of downloaded file for $appKey failed", t)
        }
    }

    companion object {
        private const val TAG = "DownloadInstall"
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
