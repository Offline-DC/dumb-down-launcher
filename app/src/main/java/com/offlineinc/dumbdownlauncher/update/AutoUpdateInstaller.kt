package com.offlineinc.dumbdownlauncher.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
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

    /**
     * Max time to wait for a download before giving up: if an update can't pull
     * down in this window on the current connection, we cancel it and delete the
     * partial rather than hold the nightly run open. Kept just under
     * WorkManager's ~10 min worker cap so our own cancel+delete runs before the
     * worker would otherwise be killed mid-wait (which would orphan the partial).
     */
    private const val DOWNLOAD_WAIT_MS = 9 * 60_000L
    private const val DOWNLOAD_POLL_INTERVAL_MS = 2_000L

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
        val dl = downloadViaManager(context, url, "auto-launcher.apk") ?: return false
        return try {
            val ok = rootInstall(listOf(dl.file))
            Log.i(TAG, "launcher silent install ok=$ok")
            ok
        } finally {
            // Always bin the downloaded APK, whether the install succeeded or
            // failed (e.g. INSTALL_FAILED_UPDATE_INCOMPATIBLE), so a stale APK
            // never lingers in external storage.
            cleanupDownload(context, dl)
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
        val dl = downloadViaManager(context, url, "auto-openbubbles.zip") ?: return false
        val splitDir = File(context.cacheDir, "auto-ob-splits").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val splits = extractSplits(dl.file, splitDir)
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
            // Always bin the downloaded zip + extracted splits, success or
            // failure, so nothing lingers in external storage.
            cleanupDownload(context, dl)
            splitDir.deleteRecursively()
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Downloads [url] (saved as [fileName] in the app's external Downloads dir)
     * via the system **DownloadManager** and returns the local file once the
     * transfer completes — or null if it failed or didn't finish within
     * [DOWNLOAD_WAIT_MS].
     *
     * Why DownloadManager instead of a hand-rolled HttpURLConnection: the system
     * downloader follows GitHub's release→CDN redirect, transparently
     * resumes/retries across dropped connections, and imposes no per-read socket
     * timeout. The old stream-copy died on exactly those — `unexpected end of
     * stream` (the CDN closed a long-lived connection mid-transfer) and a 60 s
     * `SocketTimeoutException` on slow Wi-Fi.
     *
     * If the download doesn't finish within the wait window we cancel it and
     * delete the partial file (`dm.remove`): an update that can't pull down in
     * ~10 min on the current connection isn't worth holding the nightly run open
     * for, so we bail and let the next 4 AM run start fresh.
     *
     * On success returns the local file *and* its DownloadManager id so the
     * caller can [cleanupDownload] both the file and the row once it's done
     * installing (or failed installing).
     */
    private fun downloadViaManager(context: Context, url: String, fileName: String): Download? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: run {
            Log.e(TAG, "DownloadManager unavailable — cannot download $fileName")
            return null
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Updating $fileName")
            // We surface our own progress notification elsewhere; hide the
            // system one (needs DOWNLOAD_WITHOUT_NOTIFICATION, already held).
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            // Wi-Fi only. The 4 AM guard already required Wi-Fi; this also
            // stops a mid-download network switch from spending cellular data.
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = dm.enqueue(request)
        Log.i(TAG, "DownloadManager enqueued $fileName (id=$id)")

        val deadline = System.currentTimeMillis() + DOWNLOAD_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val row = queryDownload(dm, id) ?: run {
                Log.e(TAG, "download row for $fileName (id=$id) disappeared")
                return null
            }
            when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file = row.localUri?.let { Uri.parse(it).path }?.let(::File)
                    if (file == null || !file.exists()) {
                        Log.e(TAG, "$fileName reported success but file missing (uri=${row.localUri})")
                        dm.remove(id)
                        return null
                    }
                    Log.i(TAG, "downloaded $fileName (${file.length()} bytes)")
                    return Download(file, id)
                }
                DownloadManager.STATUS_FAILED -> {
                    Log.e(TAG, "download of $fileName failed (reason=${row.reason})")
                    dm.remove(id)
                    return null
                }
                // PENDING / RUNNING / PAUSED — keep waiting.
                else -> Thread.sleep(DOWNLOAD_POLL_INTERVAL_MS)
            }
        }
        // Too slow on this connection — cancel the download and bin the partial.
        Log.w(TAG, "$fileName not done after ${DOWNLOAD_WAIT_MS / 60_000} min — cancelling and deleting partial")
        dm.remove(id)
        return null
    }

    /** A completed download: the local file plus its DownloadManager row id. */
    private data class Download(val file: File, val id: Long)

    /**
     * Removes a completed download — deletes both the file and its
     * DownloadManager row ([DownloadManager.remove] deletes the underlying file
     * too), with a plain [File.delete] as a belt-and-suspenders fallback. Safe to
     * call on any exit path; never throws.
     */
    private fun cleanupDownload(context: Context, dl: Download) {
        runCatching {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.remove(dl.id)
        }
        runCatching { dl.file.delete() }
    }

    private data class DownloadRow(val status: Int, val reason: Int, val localUri: String?)

    /** Snapshots the DownloadManager row for [id], or null if it no longer exists. */
    private fun queryDownload(dm: DownloadManager, id: Long): DownloadRow? {
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            return DownloadRow(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
            )
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
