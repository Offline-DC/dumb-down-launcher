package com.offlineinc.dumbdownlauncher.openbubbles

import android.util.Log
import java.util.concurrent.TimeUnit

private const val TAG = "OpenBubblesOps"

/**
 * Shared OpenBubbles operations that both [com.offlineinc.dumbdownlauncher.DumbDownApp]'s
 * setup migration and [OpenBubblesAttachmentCleanupWorker] use. Keeps the
 * "kill OpenBubbles politely" and "wipe its attachment cache" routines
 * in one place so changes don't drift across the two callers.
 *
 * Everything here goes through `su -c "nsenter -t 1 -m -- sh -c '…'"`
 * because Android 10+ hides cross-package `/data/data/...` behind mount
 * namespaces — entering init's namespace is the standard workaround
 * (same pattern as `DumbDownApp.rootExec`).
 */
object OpenBubblesOps {

    /** OpenBubbles' application id. Won't change unless the app is renamed again. */
    const val PKG = "com.openbubbles.messaging"

    /** Where OpenBubbles drops downloaded attachments — one subdir per GUID. */
    const val ATTACHMENTS_DIR = "/data/data/$PKG/app_flutter/attachments"

    /** Hard cap on `su` invocations (Magisk warm-up tolerance). */
    private const val SU_TIMEOUT_MS = 30_000L

    /**
     * Quietly stops the OpenBubbles process so callers can edit its
     * private data without it racing them — without making it look
     * like a crash to the user.
     *
     *   * If OpenBubbles is the currently focused (foreground) app:
     *     THROWS [IllegalStateException]. Callers in a migration context
     *     should let the throw propagate so the migration framework
     *     defers retry to the next boot. Callers in a periodic worker
     *     context should catch and skip until the next scheduled run.
     *
     *   * Otherwise: SIGKILLs the OpenBubbles PID. Cleaner than
     *     `am force-stop` because Android's "stopped" state isn't
     *     applied — the foreground service that keeps the iMessage
     *     bridge alive is auto-recreated by the system via
     *     START_STICKY, so the user at most sees a brief notification-
     *     icon blip.
     *
     *   * No-op if OpenBubbles isn't running at all.
     */
    @JvmStatic
    fun stopQuietly(tag: String = TAG) {
        // Foreground = focused window. Both `mCurrentFocus` and
        // `mFocusedApp` are checked because the field name shifts
        // across Android versions / OEM builds.
        val (_, focusOut, _) = rootExec("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        if (focusOut.contains(PKG)) {
            throw IllegalStateException(
                "$PKG is currently the focused app — deferring kill to avoid looking like a crash"
            )
        }

        val (_, pidOut, _) = rootExec("pidof $PKG")
        val pid = pidOut.trim()
        if (pid.isEmpty()) {
            Log.d(tag, "OB stop: $PKG not running — no kill needed")
            return
        }

        val (killExit, _, killErr) = rootExec("kill -9 $pid")
        if (killExit != 0) {
            // Best-effort. Log and let the caller decide whether to
            // proceed — the worst case is OB's in-memory cache races
            // us, which we'll catch on the next periodic run.
            Log.w(tag, "OB stop: kill -9 $pid failed — exit=$killExit err=$killErr")
        } else {
            Log.d(tag, "OB stop: SIGKILLed pid=$pid (services should auto-restart)")
        }
    }

    /**
     * Result of one [clearAttachments] pass. `bytesFreed` is best-effort
     * — `du -sh` is computed before the rm and parsed loosely; pass `0`
     * means "didn't measure", not necessarily "deleted nothing".
     */
    data class ClearResult(val bytesFreedDisplay: String)

    /**
     * Wipes OpenBubbles' attachment cache by deleting every subdirectory
     * (and its contents) under [ATTACHMENTS_DIR]. The parent directory
     * itself is preserved so OpenBubbles doesn't trip when it next tries
     * to write a new attachment.
     *
     * Calls [stopQuietly] first — so this method ALSO throws
     * [IllegalStateException] when OpenBubbles is focused. Callers in a
     * worker context should catch and treat it as "skip this run".
     *
     * Returns a [ClearResult] containing the human-readable "before"
     * size so the caller can include it in its log line.
     */
    @JvmStatic
    fun clearAttachments(tag: String = TAG): ClearResult {
        // Bail benignly if the dir simply doesn't exist (fresh device
        // that has never received an attachment).
        val (_, existsOut, _) = rootExec("test -d $ATTACHMENTS_DIR && echo y || echo n")
        if (existsOut != "y") {
            Log.d(tag, "OB clear attachments: $ATTACHMENTS_DIR doesn't exist — nothing to do")
            return ClearResult(bytesFreedDisplay = "0")
        }

        // Best-effort size for logging. We capture this before the kill
        // because killing OB doesn't change disk usage.
        val (_, beforeSize, _) = rootExec("du -sh $ATTACHMENTS_DIR 2>/dev/null | cut -f1")
        val display = beforeSize.takeIf { it.isNotEmpty() } ?: "?"

        // Kill OB so any in-flight attachment download can't race the rm.
        // Throws if OB is focused — caller decides what to do.
        stopQuietly(tag)

        // `find … -mindepth 1 -delete` removes every entry inside the
        // directory but preserves the directory itself. Toybox find on
        // Android 6+ supports both `-mindepth` and `-delete`.
        val (rmExit, _, rmErr) = rootExec("find $ATTACHMENTS_DIR -mindepth 1 -delete")
        if (rmExit != 0) {
            // Fall back to the brute-force form. Less ideal because
            // empty-glob behaviour varies across shells, but worth
            // trying before giving up.
            val (rm2Exit, _, rm2Err) = rootExec("rm -rf $ATTACHMENTS_DIR/*")
            if (rm2Exit != 0) {
                throw RuntimeException(
                    "OB clear attachments: both find -delete and rm -rf failed " +
                        "(find err='$rmErr', rm err='$rm2Err')"
                )
            }
        }

        Log.d(tag, "OB clear attachments: cleared $ATTACHMENTS_DIR (was $display)")
        return ClearResult(bytesFreedDisplay = display)
    }

    /**
     * Runs a shell command via Magisk `su` and `nsenter -t 1 -m`. Same
     * pattern as `DumbDownApp.rootExec`. Drains both streams before
     * `waitFor()` to avoid pipe deadlocks on long output.
     *
     * Returns Triple(exitCode, stdout, stderr).
     */
    private fun rootExec(cmd: String): Triple<Int, String, String> {
        return try {
            val full = "nsenter -t 1 -m -- sh -c ${shellQuote(cmd)}"
            val proc = ProcessBuilder("su", "-c", full).start()
            val stdout = proc.inputStream.bufferedReader().readText().trim()
            val stderr = proc.errorStream.bufferedReader().readText().trim()
            if (!proc.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "rootExec: timed out after ${SU_TIMEOUT_MS}ms — `$cmd`")
                proc.destroyForcibly()
                return Triple(-1, stdout, stderr)
            }
            Triple(proc.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            Log.w(TAG, "rootExec: ${e.javaClass.simpleName} — ${e.message}")
            Triple(-1, "", e.message ?: "")
        }
    }

    /** Wraps a string in single quotes, escaping any embedded single quotes. */
    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
