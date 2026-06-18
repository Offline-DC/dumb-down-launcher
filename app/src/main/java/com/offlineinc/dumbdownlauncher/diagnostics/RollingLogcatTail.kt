package com.offlineinc.dumbdownlauncher.diagnostics

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Continuously tails the device logcat to a size-rotated ring of files
 * under <diagRoot>/rolling-logcat/.
 *
 * Point-in-time `logcat -d -t 10000` captures the last ten thousand
 * lines at the moment they're taken. If the device crashes 59 minutes
 * after a snapshot, that snapshot is stale and the next one never
 * happens. This tail fills the gap: there is always an active segment
 * file on disk that is at most
 * [RebootLoggingConfig.ROLLING_LOGCAT_SEGMENT_BYTES] old, and a crash
 * leaves the in-progress segment behind for the support engineer to
 * harvest on the next boot.
 *
 * Files:
 *
 *   rolling-logcat/
 *     current.log               ← actively being written
 *     segment-YYYYmmdd-HHMMSS.log.gz   (rotated, gzipped)
 *     segment-YYYYmmdd-HHMMSS.log.gz   (older)
 *     ...
 *
 * The tail subprocess is `logcat -v threadtime <filterspec>` (default
 * `*:W` — see RebootLoggingConfig.ROLLING_LOGCAT_FILTERSPEC), started under
 * `su -c` so we get every UID — the launcher process's own buffer is
 * useless for diagnosing a crash that occurs inside system_server or
 * a vendor service. The subprocess is restarted if it dies (Magisk
 * policy change, OOM kill, etc.) with bounded backoff.
 *
 * Retention is layered. The normal eviction path is time-based: on
 * every rotate, any segment older than
 * [RebootLoggingConfig.ROLLING_LOGCAT_RETENTION_HOURS] (24) is
 * deleted. The size-based [RebootLoggingConfig.ROLLING_LOGCAT_MAX_BYTES]
 * cap (500 MB) is the safety net for pathologically chatty devices
 * where a single 24-hour window itself exceeds the cap; when that
 * happens we evict oldest-first until under budget. Per-segment cap
 * forces rotation so a crash always loses less than a full segment's
 * worth of log lines.
 *
 * Started from [RebootLoggingService.onCreate] when the opt-in is set.
 * Stopped from [RebootLoggingService.onDestroy].
 */
internal class RollingLogcatTail(
    private val diagRoot: File,
    private val mirrorRoot: File?,
) {

    private val tag = "DiagRollingLogcat"

    @Volatile private var stopped = false
    private var thread: Thread? = null
    private var process: Process? = null

    private var currentFile: File = File(rollingDir(diagRoot), CURRENT_FILENAME)
    private var currentWriter: OutputStreamWriter? = null
    private var currentBytes: Long = 0L

    private var backoffMs: Long = RebootLoggingConfig.ROLLING_LOGCAT_RESPAWN_INITIAL_MS

    fun start() {
        if (thread != null) return
        rollingDir(diagRoot).mkdirs()
        mirrorRoot?.let { rollingDir(it).mkdirs() }

        // First-run housekeeping: if a previous process left a
        // `current.log` behind, rename it to a segment so it survives
        // any future eviction by retention and is named like the other
        // rotated segments. This is also what makes "the log file from
        // before the crash" findable — it's a segment, not current.log.
        try {
            val priorCurrent = File(rollingDir(diagRoot), CURRENT_FILENAME)
            if (priorCurrent.exists() && priorCurrent.length() > 0) {
                val name = "$SEGMENT_PREFIX-pre-${timestamp()}.log"
                priorCurrent.renameTo(File(priorCurrent.parentFile, name))
            }
        } catch (t: Throwable) {
            Log.w(tag, "prior current.log rename failed", t)
        }

        stopped = false
        thread = Thread({ runTailLoop() }, "DiagRollingLogcat").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(tag, "rolling logcat tail started")
    }

    fun stop() {
        stopped = true
        try { process?.destroy() } catch (_: Throwable) {}
        try { currentWriter?.flush() } catch (_: Throwable) {}
        try { currentWriter?.close() } catch (_: Throwable) {}
        currentWriter = null
        thread = null
    }

    // ── Tail loop ────────────────────────────────────────────────────────

    private fun runTailLoop() {
        while (!stopped) {
            try {
                openCurrentForAppend()
                spawnAndRead()
            } catch (t: Throwable) {
                Log.w(tag, "tail subprocess error", t)
            }
            // Respawn with bounded exponential backoff. The subprocess
            // can die for a lot of reasons we don't control (Magisk
            // policy change, kernel OOM kill, log buffer reset on
            // boot, etc.). Stop tightly retrying so a permanently
            // broken root path doesn't spin.
            if (stopped) break
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
            backoffMs = (backoffMs * 2)
                .coerceAtMost(RebootLoggingConfig.ROLLING_LOGCAT_RESPAWN_MAX_MS)
        }
        try { currentWriter?.flush() } catch (_: Throwable) {}
        try { currentWriter?.close() } catch (_: Throwable) {}
    }

    private fun spawnAndRead() {
        // `-v threadtime` gives wall-clock + pid/tid + tag/priority +
        // message — the same format dropboxd-derived crash logs use,
        // so it's parseable by the same tools downstream.
        // Priority-filtered (ROLLING_LOGCAT_FILTERSPEC, default `*:W`) rather
        // than `*:V`: the continuous verbose stream was the module's biggest
        // always-on battery cost, and warning-and-above keeps the crash/reboot
        // signal while dropping the firehose. See RebootLoggingConfig.
        val cmd = arrayOf("su", "-c", "logcat -v threadtime ${RebootLoggingConfig.ROLLING_LOGCAT_FILTERSPEC}")
        val p = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()
        process = p
        BufferedReader(InputStreamReader(p.inputStream), BUFFER_BYTES).use { reader ->
            // Reset backoff on first successful read — the subprocess
            // is healthy enough to be streaming.
            var resetBackoff = true
            val buf = StringBuilder()
            while (!stopped) {
                val line = reader.readLine() ?: break
                if (resetBackoff) {
                    backoffMs = RebootLoggingConfig.ROLLING_LOGCAT_RESPAWN_INITIAL_MS
                    resetBackoff = false
                }
                buf.setLength(0)
                buf.append(line).append('\n')
                writeLine(buf.toString())
            }
        }
        try { p.destroy() } catch (_: Throwable) {}
        process = null
    }

    // ── File handling ────────────────────────────────────────────────────

    private fun openCurrentForAppend() {
        currentFile = File(rollingDir(diagRoot), CURRENT_FILENAME).also {
            it.parentFile?.mkdirs()
            if (!it.exists()) it.createNewFile()
        }
        currentBytes = currentFile.length()
        currentWriter = OutputStreamWriter(FileOutputStream(currentFile, /* append = */ true))
    }

    @Synchronized
    private fun writeLine(text: String) {
        val w = currentWriter ?: return
        w.write(text)
        // Flush each line so the on-disk segment is up-to-date even if
        // the process dies between reads. Logcat is line-buffered on
        // the producing side and the volume is bounded by what the
        // device can actually generate, so the IO cost is fine.
        w.flush()
        currentBytes += text.length

        if (currentBytes >= RebootLoggingConfig.ROLLING_LOGCAT_SEGMENT_BYTES) {
            rotate()
        }
    }

    @Synchronized
    private fun rotate() {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (_: Throwable) {}

        val ts = timestamp()
        val rotated = File(currentFile.parentFile, "$SEGMENT_PREFIX-$ts.log")
        try {
            currentFile.renameTo(rotated)
        } catch (t: Throwable) {
            Log.w(tag, "rename current.log failed", t)
        }

        // Gzip the rotated segment in the background so the writer can
        // resume on a fresh current.log immediately.
        Thread({
            try {
                gzipInPlace(rotated)
                // Mirror the gzipped segment to the /sdcard pull dir.
                mirrorRoot?.let { mDir ->
                    val gz = File(rotated.parentFile, rotated.name + ".gz")
                    if (gz.exists()) {
                        try {
                            val mirrorSegments = rollingDir(mDir)
                            mirrorSegments.mkdirs()
                            gz.copyTo(File(mirrorSegments, gz.name), overwrite = true)
                        } catch (_: Throwable) { /* best-effort */ }
                    }
                }
                // Time-based retention is the normal eviction path —
                // keep only segments newer than the retention window.
                // Size-based eviction is the safety net that runs after.
                enforceRetention()
                enforceBudget()
            } catch (t: Throwable) {
                Log.w(tag, "post-rotate housekeeping failed", t)
            }
        }, "DiagRollingLogcat-rotate").start()

        // Open a fresh current.log to keep writing.
        currentFile = File(rollingDir(diagRoot), CURRENT_FILENAME).also { it.createNewFile() }
        currentBytes = 0L
        currentWriter = OutputStreamWriter(FileOutputStream(currentFile, /* append = */ true))
    }

    private fun gzipInPlace(src: File) {
        if (!src.exists() || src.length() == 0L) {
            src.delete()
            return
        }
        val gz = File(src.parentFile, src.name + ".gz")
        try {
            src.inputStream().use { input ->
                java.util.zip.GZIPOutputStream(gz.outputStream()).use { output ->
                    input.copyTo(output)
                }
            }
            src.delete()
        } catch (t: Throwable) {
            Log.w(tag, "gzip failed for ${src.name}", t)
            gz.delete()
        }
    }

    /**
     * Time-based eviction. Deletes every segment whose mtime is older
     * than the retention window. Reads the [SEGMENT_PREFIX] files only
     * — current.log is never evicted.
     *
     * mtime is the right signal here: on rotate we hand the segment to
     * a background thread which gzips it and writes a `.gz` next to
     * the original. The gzip's mtime is "when the rotation finished",
     * which is within a few seconds of "when the segment was full" —
     * close enough for a 24-hour retention bound.
     */
    private fun enforceRetention() {
        val cutoffMs = System.currentTimeMillis() -
            RebootLoggingConfig.ROLLING_LOGCAT_RETENTION_HOURS.toLong() *
            60L * 60L * 1000L
        val dir = rollingDir(diagRoot)
        val segments = dir.listFiles { f ->
            f.isFile && f.name.startsWith(SEGMENT_PREFIX)
        } ?: return
        for (f in segments) {
            if (f.lastModified() < cutoffMs && f.delete()) {
                // Mirror eviction is best-effort.
                mirrorRoot?.let { mRoot ->
                    File(rollingDir(mRoot), f.name).delete()
                }
            }
        }
    }

    /**
     * Size-based eviction. Safety net for pathologically chatty
     * devices where the 24-hour window itself exceeds the cap.
     * Oldest-first delete until under the budget. We only count files
     * in the launcher's filesDir — that's the constrained partition;
     * /sdcard is much larger and the mirror is best-effort.
     */
    private fun enforceBudget() {
        val dir = rollingDir(diagRoot)
        var total = dir.listFiles()?.sumOf { it.length() } ?: 0L
        if (total <= RebootLoggingConfig.ROLLING_LOGCAT_MAX_BYTES) return

        val segments = dir.listFiles { f ->
            f.isFile && f.name.startsWith(SEGMENT_PREFIX)
        }?.sortedBy { it.lastModified() } ?: return

        for (f in segments) {
            if (total <= RebootLoggingConfig.ROLLING_LOGCAT_MAX_BYTES) break
            val sz = f.length()
            if (f.delete()) {
                total -= sz
                // Mirror eviction is best-effort and ignored on failure.
                mirrorRoot?.let { mRoot ->
                    File(rollingDir(mRoot), f.name).delete()
                }
            }
        }
    }

    private companion object {
        const val CURRENT_FILENAME = "current.log"
        const val SEGMENT_PREFIX = "segment"
        const val BUFFER_BYTES = 64 * 1024

        fun rollingDir(root: File): File = File(root, "rolling-logcat")

        fun timestamp(): String {
            val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date())
        }
    }
}
