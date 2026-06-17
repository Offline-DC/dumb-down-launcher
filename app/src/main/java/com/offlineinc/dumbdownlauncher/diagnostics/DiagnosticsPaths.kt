package com.offlineinc.dumbdownlauncher.diagnostics

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Resolves the diagnostics folder layout described in the plan:
 *
 *   <filesDir>/diag/                        ← canonical write target (private)
 *   <getExternalFilesDir(null)>/diag/       ← mirror on /sdcard/Android/data
 *                                              for easy `adb pull`
 *
 * Every per-device bundle ends up looking like:
 *   diag/
 *     manifest.json
 *     samples.jsonl
 *     events.jsonl
 *     dumpsys/
 *       batterystats-checkin-<ts>.txt
 *       sensorservice-<ts>.txt
 *       deviceidle-<ts>.txt
 *       …
 *     logcat-<ts>.txt
 */
internal object DiagnosticsPaths {

    fun privateDiagDir(context: Context): File =
        File(context.filesDir, DiagnosticsConfig.DIAG_DIRNAME).also { it.mkdirs() }

    /**
     * Mirror under /sdcard/Android/data/<pkg>/files/diag/ — readable by `adb pull`
     * without root. Returns null on the rare device where external files dir
     * isn't mounted; the service falls back to the private dir in that case.
     */
    fun mirrorDiagDir(context: Context): File? {
        val external = context.getExternalFilesDir(null) ?: return null
        if (Environment.getExternalStorageState(external) != Environment.MEDIA_MOUNTED) return null
        return File(external, DiagnosticsConfig.DIAG_DIRNAME).also { it.mkdirs() }
    }

    fun dumpsysDir(diagRoot: File): File =
        File(diagRoot, "dumpsys").also { it.mkdirs() }

    /** Pull instructions surfaced to the support engineer in DiagnosticsActivity. */
    fun adbPullCommand(packageName: String): String =
        "adb pull /sdcard/Android/data/$packageName/files/${DiagnosticsConfig.DIAG_DIRNAME}/"

    /**
     * Total size of the diagnostics tree, used to enforce MAX_DIAG_BYTES.
     */
    fun diagTreeSize(root: File): Long {
        if (!root.exists()) return 0L
        var total = 0L
        root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /**
     * Subdirectory (under each diag root) that the rolling adb-log tail
     * writes into. [clearBatteryLogs] excludes it so turning battery
     * analysis off doesn't take the rolling adb logs down with it. The
     * rolling tree's own wipe lives on [DiagPaths.clearRollingLogs] (the
     * path object the rolling-logs service uses); kept in sync by name.
     */
    private const val ROLLING_LOGCAT_DIRNAME = "rolling-logcat"

    /**
     * Delete only the BATTERY-DIAGNOSTICS files in both roots — everything
     * in the diag root EXCEPT the rolling-logcat subdir
     * (`samples-*.jsonl`, `events-*.jsonl`, `manifest.json`, the `dumpsys/`
     * snapshots, and the `logcat-*.txt` captures). Called when the user
     * turns the "battery analysis" toggle off. Defined by exclusion so a
     * future battery output added to the root is cleared automatically
     * without revisiting this list.
     */
    fun clearBatteryLogs(context: Context) = forEachRoot(context) { root ->
        root.listFiles()?.forEach { entry ->
            if (entry.name != ROLLING_LOGCAT_DIRNAME) entry.deleteRecursively()
        }
    }

    /**
     * Delete the ENTIRE diag tree (both subsystems) in both roots. Used by
     * the "reset session" action, which wipes every existing log file
     * before a fresh capture session id is minted so the next bundle only
     * contains the new session's data.
     */
    fun clearAllLogs(context: Context) = forEachRoot(context) { root ->
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Run [action] against each existing diag root (private + adb-pull
     * mirror). [privateDiagDir]/[mirrorDiagDir] both mkdirs() the root, so
     * the services keep a valid directory to write into after a wipe; the
     * mirror is skipped only when external storage isn't mounted.
     */
    private inline fun forEachRoot(context: Context, action: (File) -> Unit) {
        listOfNotNull(privateDiagDir(context), mirrorDiagDir(context)).forEach(action)
    }
}
