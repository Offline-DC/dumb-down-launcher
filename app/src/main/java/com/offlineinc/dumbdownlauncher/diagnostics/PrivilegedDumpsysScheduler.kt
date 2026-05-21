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
        // The command list maps to battery-diagnostics-plan.md §1.4
        // plus the kernel/radio/process additions added after the first
        // smoke test exposed gaps for the linear idle-drain hypothesis.
        // Anything that doesn't exist on a given Android variant fails
        // gracefully — failed jobs are recorded in the snapshot event.
        val JOBS: List<Job> = listOf(
            // ── Plan §1.4 — the original set ──────────────────────────
            Job("batterystats-checkin", "dumpsys batterystats --checkin"),
            Job("procstats", "dumpsys procstats --hours 24"),
            Job("deviceidle", "dumpsys deviceidle"),
            Job("alarm", "dumpsys alarm"),
            Job("jobscheduler", "dumpsys jobscheduler"),
            Job("power", "dumpsys power"),
            Job("sensorservice", "dumpsys sensorservice"),
            Job("activity_processes", "dumpsys activity processes"),
            Job("netstats", "dumpsys netstats detail"),

            // ── Kernel-level wakeup attribution ───────────────────────
            // The single most important data for "why is the SoC waking
            // up": per-source name, active count, total active time.
            //
            // The canonical file `/sys/kernel/debug/wakeup_sources`
            // would be the obvious capture, but on TCL Android 11 the
            // debugfs mountpoint has been stripped entirely (verified
            // empirically — mount fails with ENOENT on the mountpoint
            // itself, not just on the type). So we read the per-source
            // kobject sysfs interface at /sys/class/wakeup/wakeupN/
            // instead and synthesize the same TSV format. Same data,
            // same column order, just iterated from sysfs.
            //
            // If you ever port this to a device that DOES expose
            // debugfs, the post-processor can use the same parser
            // against either output.
            Job(
                "wakeup_sources",
                "printf 'name\\tactive_count\\tevent_count\\twakeup_count\\texpire_count\\tactive_since\\ttotal_time\\tmax_time\\tlast_change\\tprevent_suspend_time\\n'; " +
                    "for d in /sys/class/wakeup/wakeup*; do " +
                        "[ -f \"\$d/name\" ] || continue; " +
                        "n=\$(cat \"\$d/name\" 2>/dev/null); " +
                        "ac=\$(cat \"\$d/active_count\" 2>/dev/null || echo 0); " +
                        "ec=\$(cat \"\$d/event_count\" 2>/dev/null || echo 0); " +
                        "wc=\$(cat \"\$d/wakeup_count\" 2>/dev/null || echo 0); " +
                        "xc=\$(cat \"\$d/expire_count\" 2>/dev/null || echo 0); " +
                        "at=\$(cat \"\$d/active_time_ms\" 2>/dev/null || echo 0); " +
                        "tt=\$(cat \"\$d/total_time_ms\" 2>/dev/null || echo 0); " +
                        "mt=\$(cat \"\$d/max_time_ms\" 2>/dev/null || echo 0); " +
                        "lc=\$(cat \"\$d/last_change_ms\" 2>/dev/null || echo 0); " +
                        "pt=\$(cat \"\$d/prevent_suspend_time_ms\" 2>/dev/null || echo 0); " +
                        "printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' " +
                            "\"\$n\" \"\$ac\" \"\$ec\" \"\$wc\" \"\$xc\" \"\$at\" \"\$tt\" \"\$mt\" \"\$lc\" \"\$pt\"; " +
                    "done"
            ),
            // MediaTek-specific: names the driver/IRQ that woke the
            // system out of the MOST RECENT suspend. Hourly snapshots
            // give us ~24 wake-reason data points per day. Combined
            // with `interrupts` deltas across snapshots and the lid
            // event stream from LidSensorReader, this is what answers
            // "why is the SoC waking up?" on MTK hardware.
            Job(
                "wakeup_reason",
                "echo '--- last_resume_reason ---'; " +
                    "cat /sys/kernel/wakeup_reasons/last_resume_reason 2>/dev/null; " +
                    "echo '--- last_suspend_time ---'; " +
                    "cat /sys/kernel/wakeup_reasons/last_suspend_time 2>/dev/null"
            ),
            // Aggregate suspend success/fail counters plus
            // `last_failed_dev` — which driver blocked the last
            // suspend attempt. If a TCL vendor driver is repeatedly
            // failing suspend, that's a one-line answer for the
            // doze_blocked hypothesis.
            //
            // On this kernel /sys/power/suspend_stats is a DIRECTORY
            // containing one file per stat (success, fail,
            // failed_freeze, last_failed_dev, last_failed_errno,
            // last_failed_step, etc.), not a single file. `grep .`
            // dumps every non-empty file as `path:value` — same
            // parseable shape as the rest of our captures.
            Job(
                "suspend_stats",
                "grep . /sys/power/suspend_stats/* 2>/dev/null"
            ),
            // Kernel ring buffer. Contains the
            //   PM: suspend exit / Abort:Last active Wakeup Source: X
            // lines that name the specific driver/IRQ that broke
            // suspend. `-T` adds wall-clock timestamps so events can be
            // correlated against the launcher's own event log.
            Job("dmesg", "dmesg -T"),
            // Per-IRQ counts since boot. Complements wakeup_sources —
            // if the hall sensor IRQ is incrementing constantly while
            // the device is closed, that's the smoking gun.
            Job("interrupts", "cat /proc/interrupts"),
            // Kernel-level CPU time per state (user / system / idle /
            // iowait / etc.). The macro indicator of whether the SoC
            // is actually reaching deep idle.
            Job("proc_stat", "cat /proc/stat"),
            // Per-process CPU snapshot at the moment the snapshot
            // fires. Cheap second opinion against procstats and
            // activity_processes; useful when a process is bursty and
            // averages hide the spike.
            Job("top", "top -b -n 1 -m 50"),

            // ── App-level attribution gaps from the first smoke run ───
            // Chronological battery event timeline — pairs with the
            // existing --checkin aggregate view. The history view is
            // what Battery Historian renders as the timeline graph.
            Job("batterystats-history", "dumpsys batterystats --history"),
            // Wifi scan / connect counts feed the radio_thrash
            // hypothesis. The existing netstats dump shows bytes
            // per UID but not the scan activity that drains the radio.
            Job("wifi", "dumpsys wifi"),
            // Connectivity state transitions — switching between
            // wifi and cellular repeatedly is a known drain pattern.
            Job("connectivity", "dumpsys connectivity"),
        )
    }
}
