package com.offlineinc.dumbdownlauncher.diagnostics

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference

/**
 * Append-only JSONL writer with daily file rotation, optional gzip on
 * close, and basic disk-budget enforcement.
 *
 * Each writer instance owns a single basename (e.g. "events", "samples")
 * and creates one file per UTC day: `<basename>-YYYY-MM-DD.jsonl`. Older
 * closed files get gzipped on the next rotate, and anything beyond
 * RETENTION_DAYS is deleted outright.
 *
 * Both the private dir and the /sdcard mirror are written to so an ADB
 * pull can grab everything without root.
 */
internal class JsonlWriter(
    private val privateDir: File,
    private val mirrorDir: File?,
    private val basename: String,
) {

    private val tag = "DiagJsonl($basename)"

    private data class OpenWriter(
        val day: String,
        val primary: PrintWriter,
        val mirror: PrintWriter?,
    )

    private val current = AtomicReference<OpenWriter?>(null)

    /**
     * Append a single line of JSON. Caller is responsible for the JSON
     * encoding; this class only handles rotation and IO. Thread-safe via
     * the synchronized(this) on the write path — sampling is once a minute
     * and broadcast bursts are small, so contention is negligible.
     */
    @Synchronized
    fun append(json: String) {
        try {
            val today = dayKey()
            val writer = current.get()
            if (writer == null || writer.day != today) {
                writer?.let { close(it) }
                val rotated = open(today) ?: return
                current.set(rotated)
                // Best-effort housekeeping on rotate.
                gzipClosedFilesExcept(today)
                enforceRetention()
                enforceDiskBudget()
            }
            current.get()?.let { active ->
                active.primary.println(json)
                active.primary.flush()
                active.mirror?.println(json)
                active.mirror?.flush()
            }
        } catch (t: Throwable) {
            Log.w(tag, "append failed", t)
        }
    }

    @Synchronized
    fun close() {
        current.getAndSet(null)?.let { close(it) }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun open(day: String): OpenWriter? {
        val filename = "$basename-$day.jsonl"
        val primaryFile = File(privateDir, filename).also { it.parentFile?.mkdirs() }
        val primary = try {
            PrintWriter(FileWriter(primaryFile, /* append = */ true))
        } catch (t: Throwable) {
            Log.w(tag, "open primary failed for $filename", t)
            return null
        }
        val mirror = mirrorDir?.let { mDir ->
            try {
                val mFile = File(mDir, filename).also { it.parentFile?.mkdirs() }
                PrintWriter(FileWriter(mFile, /* append = */ true))
            } catch (t: Throwable) {
                Log.w(tag, "open mirror failed for $filename", t)
                null
            }
        }
        return OpenWriter(day, primary, mirror)
    }

    private fun close(w: OpenWriter) {
        try { w.primary.close() } catch (_: Throwable) {}
        try { w.mirror?.close() } catch (_: Throwable) {}
    }

    /**
     * Gzip files whose day != today's day and which aren't already gzipped.
     * Best-effort: a failure leaves the original file in place.
     */
    private fun gzipClosedFilesExcept(today: String) {
        for (dir in listOfNotNull(privateDir, mirrorDir)) {
            val files = dir.listFiles { f ->
                f.isFile &&
                    f.name.startsWith("$basename-") &&
                    f.name.endsWith(".jsonl") &&
                    !f.name.contains("-$today.")
            } ?: continue
            for (file in files) {
                try {
                    val gz = File(file.parentFile, file.name + ".gz")
                    file.inputStream().use { input ->
                        java.util.zip.GZIPOutputStream(gz.outputStream()).use { output ->
                            input.copyTo(output)
                        }
                    }
                    file.delete()
                } catch (t: Throwable) {
                    Log.w(tag, "gzip failed for ${file.name}", t)
                }
            }
        }
    }

    /** Delete files older than RETENTION_DAYS. */
    private fun enforceRetention() {
        val cutoffMs = System.currentTimeMillis() -
            DiagnosticsConfig.RETENTION_DAYS.toLong() * 24L * 60L * 60L * 1000L
        for (dir in listOfNotNull(privateDir, mirrorDir)) {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("$basename-") } ?: continue
            files.filter { it.lastModified() < cutoffMs }.forEach { it.delete() }
        }
    }

    /**
     * If the diag tree exceeds MAX_DIAG_BYTES, delete the oldest files
     * until it doesn't. This is a coarse safety net — retention by date
     * is the primary mechanism.
     */
    private fun enforceDiskBudget() {
        for (dir in listOfNotNull(privateDir, mirrorDir)) {
            var total = DiagnosticsPaths.diagTreeSize(dir)
            if (total <= DiagnosticsConfig.MAX_DIAG_BYTES) continue
            val files = dir.walkTopDown().filter { it.isFile }
                .sortedBy { it.lastModified() }
                .toList()
            for (file in files) {
                if (total <= DiagnosticsConfig.MAX_DIAG_BYTES) break
                val sz = file.length()
                if (file.delete()) total -= sz
            }
        }
    }

    private fun dayKey(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
