package com.offlineinc.dumbdownlauncher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Installs a set of split APKs that were published as a single `.zip`
 * (`base.apk` + `config.*` splits — e.g. the per-device set Google Play
 * generates from an App Bundle). OpenBubbles ships this way so a one-ABI,
 * Google-signed set can be sideloaded without the 400 MB universal APK.
 *
 * Each APK is streamed straight from the zip into a [PackageInstaller] session
 * (no on-disk extraction), then the session is committed. Split APKs must be
 * installed as a single atomic transaction — a plain `ACTION_VIEW` on one file
 * fails with "missing splits".
 *
 * The outcome arrives asynchronously as a broadcast
 * ([UpdateNotificationManager.ACTION_INSTALL_RESULT]) to
 * [DownloadAndInstallReceiver].
 */
object SplitApkInstaller {

    private const val TAG = "SplitApkInstaller"
    private const val MB = 1024L * 1024L

    /**
     * Free space (relative to the total split size) we insist on before even
     * starting: an *upgrade* transiently needs the staged copy + the new
     * install + extracted native libs while the old version is still present.
     * 2.5× is a conservative floor — below it the commit is almost certain to
     * fail with INSTALL_FAILED_INSUFFICIENT_STORAGE, so we reject up front with
     * a clear log instead of after a multi-minute write.
     */
    private const val FREE_SPACE_MULTIPLIER = 5L // numerator of 5/2 = 2.5×

    private fun isInstallableApk(entry: ZipEntry): Boolean {
        if (entry.isDirectory) return false
        val name = entry.name.substringAfterLast('/')
        // Real .apk only — exclude macOS AppleDouble resource forks
        // ("._foo.apk", 212 B junk) and the __MACOSX dir that `zip` on a Mac
        // adds; they'd otherwise be written as bogus splits.
        return name.endsWith(".apk") &&
            !name.startsWith("._") &&
            !entry.name.startsWith("__MACOSX/")
    }

    /**
     * Streams every installable `.apk` entry in [zipFile] into a new session
     * and commits it. Returns true if the session was created, written, and
     * committed (the *result* still arrives later via broadcast); false if it
     * couldn't (bad/empty zip, insufficient storage, write error) — caller
     * should surface a failure immediately.
     *
     * Heavy/blocking — call off the main thread.
     */
    fun installFromZip(context: Context, zipFile: File, appKey: String): Boolean {
        // Pass 1: total install size from the real .apk entries (also catches
        // an empty/garbage zip before we create a session).
        val totalSize: Long = try {
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().filter { isInstallableApk(it) }
                    .sumOf { it.size.coerceAtLeast(0L) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "could not read zip $zipFile", t)
            return false
        }
        if (totalSize <= 0L) {
            Log.e(TAG, "no installable .apk entries in $zipFile — rejecting set")
            return false
        }

        // Reject early if there clearly isn't room — an upgrade needs several
        // times the set size free during commit.
        val freeBytes = try {
            StatFs(Environment.getDataDirectory().path).availableBytes
        } catch (_: Throwable) {
            Long.MAX_VALUE // can't measure → don't block on it
        }
        val needed = totalSize * FREE_SPACE_MULTIPLIER / 2
        if (freeBytes < needed) {
            Log.e(
                TAG,
                "reject $appKey: insufficient storage — set=${totalSize / MB}MB needs " +
                    "~${needed / MB}MB free, only ${freeBytes / MB}MB available",
            )
            return false
        }
        Log.i(TAG, "$appKey: set=${totalSize / MB}MB, free=${freeBytes / MB}MB — proceeding")

        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setSize(totalSize)
            sessionId = installer.createSession(params)
            Log.i(TAG, "created session $sessionId for $appKey")

            installer.openSession(sessionId).use { session ->
                ZipFile(zipFile).use { zip ->
                    zip.entries().asSequence().filter { isInstallableApk(it) }.forEach { entry ->
                        val name = entry.name.substringAfterLast('/')
                        session.openWrite(name, 0, entry.size).use { out ->
                            zip.getInputStream(entry).use { input -> input.copyTo(out) }
                            session.fsync(out)
                        }
                        Log.i(TAG, "wrote split $name (${entry.size}B) to session $sessionId")
                    }
                }

                // Free the downloaded zip (150 MB+) BEFORE the storage-heavy
                // commit — its bytes are already staged in the session, so it's
                // dead weight now, and the commit needs all the room it can get.
                if (zipFile.delete()) {
                    Log.i(TAG, "deleted source zip pre-commit to free space")
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
