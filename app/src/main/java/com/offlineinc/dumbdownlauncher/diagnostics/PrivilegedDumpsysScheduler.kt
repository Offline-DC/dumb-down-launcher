package com.offlineinc.dumbdownlauncher.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the privileged dumpsys / logcat snapshots described in
 * battery-diagnostics-plan.md §1.4. Executes via `su -c` (root through
 * Magisk) since the launcher app itself doesn't hold READ_LOGS /
 * BATTERY_STATS / DUMP — the device-wide perms are surfaced through
 * the existing root path the launcher already uses for `pm grant`.
 *
 * Cadence: every DUMPSYS_SNAPSHOT_INTERVAL_MS (1 hour), plus one-shot
 * snapshots requested by the service on every screen-on / screen-off.
 *
 * Output layout (per snapshot, timestamped):
 *   <diagRoot>/dumpsys/batterystats-checkin-<ts>.txt
 *   <diagRoot>/dumpsys/sensorservice-<ts>.txt
 *   <diagRoot>/dumpsys/deviceidle-<ts>.txt
 *   <diagRoot>/dumpsys/alarm-<ts>.txt
 *   <diagRoot>/dumpsys/jobscheduler-<ts>.txt
 *   <diagRoot>/dumpsys/power-<ts>.txt
 *   <diagRoot>/dumpsys/procstats-<ts>.txt
 *   <diagRoot>/dumpsys/activity_processes-<ts>.txt
 *   <diagRoot>/dumpsys/netstats-<ts>.txt
 *   <diagRoot>/logcat-<ts>.txt
 *
 * Files are mirrored to the /sdcard/Android/data/<pkg>/files/diag dir
 * as well so an ADB pull (no root needed) can retrieve them.
 */
internal class PrivilegedDumpsysScheduler(
    private val context: Context,
    private val diagRoot: File,
    private val mirrorRoot: File?,
    private val store: DiagnosticsStore,
    private val eventsWriter: JsonlWriter,
) {

    private val tag = "DiagDumpsys"
    private var executor: ScheduledExecutorService? = null
    private var hourlyHandle: ScheduledFuture<*>? = null

    fun start(scheduler: ScheduledExecutorService) {
        this.executor = scheduler
        hourlyHandle = scheduler.scheduleAtFixedRate(
            { runSnapshot(reason = "hourly") },
            // First run after 30s so the device has settled before we hammer su.
            30_000L,
            DiagnosticsConfig.DUMPSYS_SNAPSHOT_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun stop() {
        try { hourlyHandle?.cancel(false) } catch (_: Throwable) {}
    }

    fun requestSnapshotAsync(reason: String) {
        val e = executor ?: return
        e.execute { runSnapshot(reason = reason) }
    }

    // ── Snapshot impl ────────────────────────────────────────────────────

    private fun runSnapshot(reason: String) {
        try {
            val ts = timestamp()
            val started = SystemClock.elapsedRealtime()
            val dumpsysDir = DiagnosticsPaths.dumpsysDir(diagRoot)
            val mirrorDumpsysDir = mirrorRoot?.let { DiagnosticsPaths.dumpsysDir(it) }
            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<String>()

            for (job in JOBS) {
                val filename = "${job.name}-$ts.txt"
                val target = File(dumpsysDir, filename)
                val ok = ShellRunner.runToFile(
                    command = job.command,
                    dest = target,
                    timeoutMs = DiagnosticsConfig.SHELL_TIMEOUT_MS,
                )
                if (ok) {
                    succeeded.add(job.name)
                    mirrorDumpsysDir?.let { mDir ->
                        try {
                            target.copyTo(File(mDir, filename), overwrite = true)
                        } catch (_: Throwable) { /* mirror is best-effort */ }
                    }
                } else {
                    failed.add(job.name)
                    // Don't leave a 0-byte file lying around.
                    target.delete()
                }
            }

            // logcat lives outside the dumpsys subdir but follows the same pattern.
            val logcatFilename = "logcat-$ts.txt"
            val logcatTarget = File(diagRoot, logcatFilename)
            val logcatOk = ShellRunner.runToFile(
                command = "logcat -d -v threadtime -t ${DiagnosticsConfig.LOGCAT_TAIL_LINES}",
                dest = logcatTarget,
                timeoutMs = DiagnosticsConfig.SHELL_TIMEOUT_MS,
            )
            if (logcatOk) {
                succeeded.add("logcat")
                mirrorRoot?.let { mRoot ->
                    try { logcatTarget.copyTo(File(mRoot, logcatFilename), overwrite = true) } catch (_: Throwable) {}
                }
            } else {
                failed.add("logcat")
                logcatTarget.delete()
            }

            // Drop a snapshot summary event so the post-processor has a marker
            // even if all the underlying dumpsys files failed (e.g. lost root).
            eventsWriter.append(
                DiagEvents.encode(
                    type = "dumpsys_snapshot",
                    source = "launcher",
                    tsMs = System.currentTimeMillis(),
                    monotonicMs = SystemClock.elapsedRealtime(),
                    sessionId = store.captureSessionId,
                    screenState = null,
                    lidState = null,
                    charging = null,
                    batteryLevelPct = null,
                    inDoze = null,
                    payload = mapOf(
                        "reason" to reason,
                        "elapsed_ms" to (SystemClock.elapsedRealtime() - started),
                        "succeeded" to succeeded,
                        "failed" to failed,
                        "snapshot_timestamp" to ts,
                    ),
                )
            )

            // Enforce retention on the dumpsys subdir too — files grow fast.
            enforceDumpsysRetention(dumpsysDir)
            mirrorDumpsysDir?.let { enforceDumpsysRetention(it) }
        } catch (t: Throwable) {
            Log.w(tag, "snapshot failed", t)
        }
    }

    private fun enforceDumpsysRetention(dir: File) {
        val cutoffMs = System.currentTimeMillis() -
            DiagnosticsConfig.RETENTION_DAYS.toLong() * 24L * 60L * 60L * 1000L
        dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoffMs }?.forEach { it.delete() }
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    // ── Command catalog ──────────────────────────────────────────────────

    private data class Job(val name: String, val command: String)

    private companion object {
        // The command list maps 1:1 to battery-diagnostics-plan.md §1.4.
        // Anything that doesn't exist on a given Android variant fails
        // gracefully — failed jobs are recorded in the snapshot event.
        val JOBS: List<Job> = listOf(
            Job("batterystats-checkin", "dumpsys batterystats --checkin"),
            Job("procstats", "dumpsys procstats --hours 24"),
            Job("deviceidle", "dumpsys deviceidle"),
            Job("alarm", "dumpsys alarm"),
            Job("jobscheduler", "dumpsys jobscheduler"),
            Job("power", "dumpsys power"),
            Job("sensorservice", "dumpsys sensorservice"),
            Job("activity_processes", "dumpsys activity processes"),
            Job("netstats", "dumpsys netstats detail"),
        )
    }
}
