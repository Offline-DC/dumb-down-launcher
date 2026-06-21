package com.offlineinc.dumbdownlauncher.diagnostics

import android.content.Context
import java.io.File

/**
 * Resolves the on-device rolling-logcat folder layout:
 *
 *   <filesDir>/diag/rolling-logcat/            ← the ONLY write target
 *     current.log                        ← actively being written
 *     segment-YYYYmmdd-HHMMSS.log.gz      ← rotated + gzipped, oldest first
 *     …
 *     segment-pre-YYYYmmdd-HHMMSS.log     ← survivor of the previous process
 *                                           run (renamed at startup so it is
 *                                           not overwritten)
 *
 * Rolling logs are kept ONLY in the app-private dir. There is deliberately
 * no /sdcard mirror: the bundle leaves the device via "submit logs" (which
 * zips and uploads this private dir), and a support engineer with root can
 * also pull it straight from /data/data — see [rootPullCommand]. Keeping a
 * single copy means disabling the toggle cleanly removes everything (see
 * [clearRollingLogs]) with nothing stranded on shared storage.
 */
internal object DiagPaths {

    fun privateDiagDir(context: Context): File =
        File(context.filesDir, RebootLoggingConfig.DIAG_DIRNAME).also { it.mkdirs() }

    /**
     * Rooted pull command. The source of truth is the app-PRIVATE dir
     * (`filesDir/diag/rolling-logcat/`), which always holds the live
     * `current.log` the instant a line is written. On these Magisk devices the
     * support engineer has `su`, so this is the way to grab the current log
     * right now. Requires `adb root` (or a root shell) to read under
     * /data/data.
     */
    fun rootPullCommand(packageName: String): String =
        "adb pull /data/data/$packageName/files/" +
            "${RebootLoggingConfig.DIAG_DIRNAME}/rolling-logcat/"

    /** Subdirectory (under the diag root) the rolling logcat tail writes into. */
    private const val ROLLING_LOGCAT_DIRNAME = "rolling-logcat"

    /**
     * Delete the rolling adb-log tree (`diag/rolling-logcat/`). Called from
     * [RebootLoggingService.onDestroy] when the user has turned rolling adb
     * logs off, so nothing the toggle collected lingers after it's disabled.
     * The battery-diagnostics files (owned by the separate
     * [DiagnosticsPaths]/[DiagnosticsService] stack but living in the same
     * diag root) are left untouched.
     */
    fun clearRollingLogs(context: Context) {
        // Source of truth: the app-private dir is always wiped.
        File(privateDiagDir(context), ROLLING_LOGCAT_DIRNAME).deleteRecursively()

        // Legacy cleanup: earlier builds mirrored to /sdcard. We no longer
        // write there, but delete any leftover tree so disabling truly leaves
        // nothing behind. Don't create the dir if it isn't already present.
        val external = context.getExternalFilesDir(null) ?: return
        val legacyMirror = File(
            File(external, RebootLoggingConfig.DIAG_DIRNAME),
            ROLLING_LOGCAT_DIRNAME,
        )
        if (legacyMirror.exists()) legacyMirror.deleteRecursively()
    }
}
