package com.offlineinc.dumbdownlauncher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Installs a set of split APKs that were published as a single `.zip`
 * (`base.apk` + `config.*` splits — e.g. the per-device set Google Play
 * generates from an App Bundle). OpenBubbles ships this way so a one-ABI,
 * Google-signed set can be sideloaded without the 400 MB universal APK.
 *
 * Each APK is streamed straight from the zip into a [PackageInstaller] session
 * (no on-disk extraction, so peak storage stays near one extra copy, not two),
 * then the session is committed. Because split APKs must be installed as a
 * single atomic transaction, this is the only way to install them — a plain
 * `ACTION_VIEW` on one file fails with "missing splits".
 *
 * The outcome is delivered asynchronously as a broadcast
 * ([UpdateNotificationManager.ACTION_INSTALL_RESULT]) to
 * [DownloadAndInstallReceiver], which handles the user-confirmation step and
 * the final success/failure. Using the session API (vs `ACTION_VIEW`) also
 * means we finally get a real `INSTALL_FAILED_*` status string instead of the
 * opaque "App not installed".
 */
object SplitApkInstaller {

    private const val TAG = "SplitApkInstaller"

    /**
     * Streams every `.apk` entry in [zipFile] into a new install session and
     * commits it. Returns true if the session was created, written, and
     * committed (the *result* still arrives later via broadcast); false if it
     * couldn't even get that far (bad zip, no apk entries, write error) — in
     * which case the caller should surface a failure immediately.
     *
     * Heavy/blocking (reads the whole archive) — call off the main thread.
     */
    fun installFromZip(context: Context, zipFile: File, appKey: String): Boolean {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        try {
            ZipFile(zipFile).use { zip ->
                val apkEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('/').endsWith(".apk") }
                    .toList()
                if (apkEntries.isEmpty()) {
                    Log.e(TAG, "no .apk entries in $zipFile — nothing to install")
                    return false
                }

                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )
                val totalSize = apkEntries.sumOf { it.size.coerceAtLeast(0L) }
                if (totalSize > 0L) params.setSize(totalSize)

                sessionId = installer.createSession(params)
                Log.i(TAG, "created session $sessionId with ${apkEntries.size} splits (${totalSize}B)")

                installer.openSession(sessionId).use { session ->
                    for (entry in apkEntries) {
                        val name = entry.name.substringAfterLast('/')
                        session.openWrite(name, 0, entry.size).use { out ->
                            zip.getInputStream(entry).use { input -> input.copyTo(out) }
                            session.fsync(out)
                        }
                        Log.i(TAG, "wrote split $name (${entry.size}B) to session $sessionId")
                    }

                    val statusIntent = Intent(context, DownloadAndInstallReceiver::class.java).apply {
                        action = UpdateNotificationManager.ACTION_INSTALL_RESULT
                        putExtra(UpdateNotificationManager.EXTRA_APP_KEY, appKey)
                    }
                    // FLAG_MUTABLE so the system can fill in EXTRA_STATUS /
                    // EXTRA_INTENT on the result broadcast (required on API 31+).
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
                    session.commit(pending.intentSender)
                    Log.i(TAG, "committed session $sessionId for $appKey — awaiting result broadcast")
                }
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "installFromZip failed for $appKey", t)
            if (sessionId >= 0) {
                try {
                    installer.abandonSession(sessionId)
                } catch (_: Throwable) {
                }
            }
            return false
        }
    }
}
