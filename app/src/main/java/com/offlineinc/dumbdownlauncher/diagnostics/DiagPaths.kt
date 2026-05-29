package com.offlineinc.dumbdownlauncher.diagnostics

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Resolves the on-device diagnostics folder layout:
 *
 *   <filesDir>/diag/                          ← canonical write target (private)
 *   <getExternalFilesDir(null)>/diag/         ← mirror under /sdcard/Android/data
 *                                               so `adb pull` can grab the
 *                                               bundle without root.
 *
 * Layout once populated:
 *
 *   diag/
 *     rolling-logcat/
 *       current.log                        ← actively being written
 *       segment-YYYYmmdd-HHMMSS.log.gz     ← rotated + gzipped, oldest first
 *       …
 *       segment-pre-YYYYmmdd-HHMMSS.log    ← survivor of the previous process
 *                                            run (renamed at startup so it is
 *                                            not overwritten)
 *
 * The /sdcard mirror is what an ADB pull retrieves with USB debugging
 * alone; the private dir is the source of truth and survives if the
 * mirror gets nuked by a "free up space" wipe.
 */
internal object DiagPaths {

    fun privateDiagDir(context: Context): File =
        File(context.filesDir, RebootLoggingConfig.DIAG_DIRNAME).also { it.mkdirs() }

    fun mirrorDiagDir(context: Context): File? {
        val external = context.getExternalFilesDir(null) ?: return null
        if (Environment.getExternalStorageState(external) != Environment.MEDIA_MOUNTED) return null
        return File(external, RebootLoggingConfig.DIAG_DIRNAME).also { it.mkdirs() }
    }

    /** Pull command surfaced to the support engineer for the rolling-logcat ring. */
    fun adbPullCommand(packageName: String): String =
        "adb pull /sdcard/Android/data/$packageName/files/" +
            "${RebootLoggingConfig.DIAG_DIRNAME}/rolling-logcat/"
}
