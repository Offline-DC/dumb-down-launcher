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
}
