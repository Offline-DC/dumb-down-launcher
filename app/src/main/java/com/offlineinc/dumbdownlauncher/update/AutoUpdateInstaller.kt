package com.offlineinc.dumbdownlauncher.update

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Unattended download + **silent** install of an update, used by the 4 AM
 * [AutoUpdateAlarmReceiver]. Unlike [DownloadAndInstallReceiver] (which is the
 * tap-an-update-notification path and installs via the system installer /
 * a PackageInstaller session that can surface a "trust this source" or split
 * confirmation dialog), this path installs through root `pm install` so the
 * whole thing completes while the user is asleep with no UI at all.
 *
 * Root is already available throughout the launcher (Magisk `su`); see the
 * `pm`/`su` usage in [com.offlineinc.dumbdownlauncher.DumbDownApp]. A root
 * `pm install` is fully silent and needs no REQUEST_INSTALL_PACKAGES prompt.
 *
 * Everything here blocks (network + multi-MB writes + a `pm` subprocess) — call
 * only from a background thread.
 */
object AutoUpdateInstaller {

    private const val TAG = "AutoUpdate"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /**
     * Downloads the launcher APK at [url] and installs it silently via root.
     *
     * Installing the launcher's own package replaces the running HOME process,
     * so Android tears down and restarts it once `pm install` commits — which
     * is why the caller installs OpenBubbles *first* and the launcher last.
     *
     * Returns true if `pm install` reported success.
     */
    fun installLauncher(context: Context, url: String): Boolean {
        val apk = downloadTo(url, downloadFile(context, "auto-launcher.apk")) ?: return false
        return try {
            val ok = rootInstall(listOf(apk))
            Log.i(TAG, "launcher silent install ok=$ok")
            ok
        } finally {
            apk.delete()
        }
    }

    /**
     * Downloads the OpenBubbles split-APK `.zip` at [url], extracts the real
     * `.apk` entries, and installs the whole set atomically via a single root
     * `pm install-multiple` (split APKs can't be installed one-by-one). On
     * success, applies the one-time OpenBubbles retention toggle — mirroring
     * the tap-to-install path in [DownloadAndInstallReceiver.handleInstallResult].
     *
     * Returns true if `pm install-multiple` reported success.
     */
    fun installOpenBubbles(context: Context, url: String): Boolean {
        val zip = downloadTo(url, downloadFile(context, "auto-openbubbles.zip")) ?: return false
        val splitDir = File(context.cacheDir, "auto-ob-splits").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val splits = extractSplits(zip, splitDir)
            if (splits.isEmpty()) {
                Log.e(TAG, "OB zip had no installable .apk entries — aborting")
                return false
            }
            val ok = rootInstall(splits)
            Log.i(TAG, "openbubbles silent install ok=$ok (${splits.size} splits)")
            if (ok) {
                // Clear the sticky "update available" tile and apply the
                // post-update retention toggle, same as the tap path does.
                UpdateNotificationManager.cancel(
                    context,
                    UpdateNotificationManager.NOTIFICATION_ID_OPENBUBBLES,
                )
                UpdateNotificationManager.clearUpdateInProgress(context, "openbubbles-messaging")
                com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesGate
                    .applyRetentionOnceAsync(context)
            }
            return ok
        } finally {
            zip.delete()
            splitDir.deleteRecursively()
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun downloadFile(context: Context, name: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.cacheDir
        return File(dir, name)
    }

    /**
     * Streams [url] to [dest], following GitHub's redirect to the asset CDN.
     * Returns the file on a complete 200 download, or null on any failure
     * (the partial file is deleted so a later retry starts clean).
     */
    private fun downloadTo(url: String, dest: File): File? {
        var conn: HttpURLConnection? = null
        return try {
            dest.parentFile?.mkdirs()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "download $url failed: HTTP $code")
                return null
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            Log.i(TAG, "downloaded ${dest.name} (${dest.length()} bytes)")
            dest
        } catch (t: Throwable) {
            Log.e(TAG, "download of $url failed", t)
            dest.delete()
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Extracts the genuine `.apk` entries from [zip] into [outDir]. */
    private fun extractSplits(zip: File, outDir: File): List<File> {
        val out = mutableListOf<File>()
        ZipFile(zip).use { zf ->
            zf.entries().asSequence().filter { isInstallableApk(it) }.forEach { entry ->
                val name = entry.name.substringAfterLast('/')
                val target = File(outDir, name)
                zf.getInputStream(entry).use { input ->
                    target.outputStream().use { o -> input.copyTo(o) }
                }
                out.add(target)
            }
        }
        return out
    }

    /** Mirrors [SplitApkInstaller.isInstallableApk] — skip macOS junk forks. */
    private fun isInstallableApk(entry: ZipEntry): Boolean {
        if (entry.isDirectory) return false
        val name = entry.name.substringAfterLast('/')
        return name.endsWith(".apk") &&
            !name.startsWith("._") &&
            !entry.name.startsWith("__MACOSX/")
    }

    /**
     * Installs [apks] (one for a single APK, many for a split set) atomically as
     * root via the PackageInstaller **session** shell API:
     *
     *   pm install-create -r -S <total>   → "Success: created install session [N]"
     *   pm install-write  -S <size> N <name> -   (once per apk; APK streamed via stdin)
     *   pm install-commit N
     *
     * NOTE: there is no device-side `pm install-multiple` — that's a host/adb
     * convenience that itself drives this same create/write/commit sequence.
     * Calling it returns "Unknown command", which is the bug this replaces.
     *
     * We stream each APK into `install-write` over **stdin** (the trailing `-`)
     * rather than passing a file path. When a path is passed, it's system_server
     * (the PackageInstaller session, SELinux context u:r:system_server:s0) that
     * opens it — and it cannot read files under the app's external-files dir,
     * which carry the u:object_r:sdcardfs:s0 label ("System server has no access
     * to read file context ... sdcardfs"). Streaming has the root shell read the
     * bytes (root *can* read sdcardfs) and hand the session an fd, so the staging
     * location no longer matters.
     *
     * `-r` reinstalls keeping data; we only call this when the release is
     * strictly newer, so no `-d` downgrade flag.
     * Returns true only when the commit reports Success.
     */
    private fun rootInstall(apks: List<File>): Boolean {
        if (apks.isEmpty()) return false
        val total = apks.sumOf { it.length() }

        val (cExit, cOut, cErr) = su("pm install-create -r -S $total")
        Log.i(TAG, "install-create exit=$cExit out=$cOut err=$cErr")
        if (cExit != 0) return false
        val sid = Regex("\\[(\\d+)]").find(cOut)?.groupValues?.get(1)
            ?: Regex("(\\d+)").find(cOut)?.groupValues?.get(1)
            ?: run { Log.e(TAG, "could not parse session id from: $cOut"); return false }

        try {
            for (apk in apks) {
                val (wExit, wOut, wErr) = suStdin(
                    "pm install-write -S ${apk.length()} $sid ${shq(apk.name)} -",
                    apk,
                )
                if (wExit != 0 || !wOut.contains("Success", ignoreCase = true)) {
                    Log.e(TAG, "install-write failed for ${apk.name}: exit=$wExit out=$wOut err=$wErr")
                    su("pm install-abandon $sid")
                    return false
                }
            }
            val (mExit, mOut, mErr) = su("pm install-commit $sid")
            Log.i(TAG, "install-commit exit=$mExit out=$mOut err=$mErr")
            return mExit == 0 && mOut.contains("Success", ignoreCase = true)
        } catch (t: Throwable) {
            Log.e(TAG, "session install failed", t)
            su("pm install-abandon $sid")
            return false
        }
    }

    /** Runs a root shell command, returning (exitCode, stdout, stderr). */
    private fun su(cmd: String): Triple<Int, String, String> {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = proc.inputStream.bufferedReader().readText().trim()
        val err = proc.errorStream.bufferedReader().readText().trim()
        val exit = proc.waitFor()
        return Triple(exit, out, err)
    }

    /**
     * Like [su], but streams [stdinFile]'s bytes into the command's stdin — used
     * to feed an APK to `pm install-write … -` so system_server never has to open
     * the (sdcardfs-labeled) file itself. The bytes are pumped on a separate
     * thread while the main thread drains stdout/stderr, so a full pipe buffer
     * can't deadlock the multi-MB write. A broken pipe (pm exiting early) is
     * swallowed here and surfaced via the non-zero exit / stderr instead.
     */
    private fun suStdin(cmd: String, stdinFile: File): Triple<Int, String, String> {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val pump = Thread {
            try {
                proc.outputStream.use { os -> stdinFile.inputStream().use { it.copyTo(os) } }
            } catch (_: Throwable) {
                // Broken pipe if pm rejected the session before reading all bytes.
            }
        }.apply { start() }
        val out = proc.inputStream.bufferedReader().readText().trim()
        val err = proc.errorStream.bufferedReader().readText().trim()
        val exit = proc.waitFor()
        pump.join()
        return Triple(exit, out, err)
    }

    /** Single-quote a string for safe interpolation into a root shell command. */
    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
