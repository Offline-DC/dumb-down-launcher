package com.offlineinc.dumbdownlauncher.openbubbles

import android.content.Context
import android.content.pm.PackageManager
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
     * Wipes OpenBubbles' attachment cache, optionally bounded by an age
     * window. The parent [ATTACHMENTS_DIR] is preserved so OpenBubbles
     * doesn't trip when it next tries to write a new attachment.
     *
     * Age cutoff:
     *  - `days < 0` (the default, and what every current caller uses)
     *    → omit the `-mtime` predicate and remove every entry under
     *    the dir. OB attachments are a download cache that the app
     *    lazily re-fetches from the iMessage relay when the user
     *    scrolls back to a message, so wiping unconditionally is safe
     *    and reclaims the full per-night growth.
     *  - `days >= 0` → only files older than N*24h (`-mtime +N`) are
     *    removed. Empty per-GUID parent dirs are then pruned with
     *    `find -type d -empty -delete` so the listing stays tidy. No
     *    caller exercises this branch today; it's preserved for a
     *    future affordance that wants a rolling-window trim instead.
     *
     * Calls [stopQuietly] first — so this method ALSO throws
     * [IllegalStateException] when OpenBubbles is focused. Callers in a
     * worker context should catch and treat it as "skip this run".
     *
     * Returns a [ClearResult] containing the human-readable "before"
     * size so the caller can include it in its log line. The size is
     * captured from `du -sh` over the whole dir before the trim,
     * matching the old shape — it slightly overestimates the bytes
     * freed when `days >= 0` (since recent attachments survive), but
     * the [StorageCleanupOps] callers compute their own
     * age-filtered before-size for the post-clear toast, so this
     * only affects the in-worker log line.
     */
    @JvmStatic
    fun clearAttachments(days: Int = -1, tag: String = TAG): ClearResult {
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

        if (days >= 0) {
            // Age-filtered file delete + empty-dir prune. -prune-by-empty
            // walks bottom-up so a fully-trimmed GUID subdir is removed
            // after its contents are gone in the same pass.
            val (rmExit, _, rmErr) = rootExec(
                "find $ATTACHMENTS_DIR -mindepth 1 -type f -mtime +$days -delete 2>/dev/null; " +
                    "find $ATTACHMENTS_DIR -mindepth 1 -type d -empty -delete 2>/dev/null; " +
                    "exit 0"
            )
            if (rmExit != 0) {
                // The trailing `exit 0` should keep us out of this branch,
                // but log defensively if some future shell flagged the
                // pipeline as a failure.
                Log.w(
                    tag,
                    "OB clear attachments: age-filtered delete reported exit=$rmExit — " +
                        "err='$rmErr' (continuing, file removal may have partially succeeded)"
                )
            }
        } else {
            // `find … -mindepth 1 -delete` removes every entry inside
            // the directory but preserves the directory itself. Toybox
            // find on Android 6+ supports both `-mindepth` and `-delete`.
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
        }

        Log.d(tag, "OB clear attachments: cleared $ATTACHMENTS_DIR (was $display, days=$days)")
        return ClearResult(bytesFreedDisplay = display)
    }

    /** Where OpenBubbles stores its Flutter SharedPreferences XML. */
    private const val FLUTTER_PREFS_PATH =
        "/data/data/$PKG/shared_prefs/FlutterSharedPreferences.xml"

    /**
     * Re-applies the opinionated OpenBubbles defaults that are also set at
     * provisioning time by the `openbubbles_setup_v1` migration in
     * [com.offlineinc.dumbdownlauncher.DumbDownApp]. Safe to call any number
     * of times — when the values on disk already match, no writes happen.
     *
     * Settings asserted:
     *  - `flutter.autoDownload`  → `false`  (no auto-fetch of attachments)
     *  - `flutter.highPerfMode`  → `true`   (OpenBubbles' high-perf path on)
     *
     * Defense-in-depth: Flutter rewrites its prefs file frequently, and an
     * OpenBubbles update could revert these values. The
     * [OpenBubblesAttachmentCleanupWorker] calls this nightly so a drift can
     * cause at most one day of auto-downloads.
     *
     * Behaviour:
     *  - Returns benignly when OpenBubbles isn't installed.
     *  - Returns benignly when the prefs file doesn't exist yet (OB has
     *    never been opened on this device — file is created lazily on
     *    first launch). The setup migration uses this signal to retry on
     *    the next boot; the nightly worker just no-ops and tries again
     *    tomorrow.
     *  - Calls [stopQuietly] before editing — so this method also THROWS
     *    [IllegalStateException] when OpenBubbles is focused. Callers
     *    should catch and skip.
     *  - Uses [context]'s cacheDir to stage the new content, then `cp` via
     *    root into the target path. Restores original ownership and 660
     *    file mode (matches what FlutterSharedPreferences.xml ships with).
     */
    @JvmStatic
    fun applyAutoDownloadOff(context: Context, tag: String = TAG) {
        // Skip cleanly when OB isn't installed.
        try {
            context.packageManager.getPackageInfo(PKG, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            Log.d(tag, "OB applyAutoDownloadOff: $PKG not installed — skipping")
            return
        }

        // The prefs file doesn't exist until OpenBubbles is launched once.
        val (_, existsOut, _) = rootExec("test -f $FLUTTER_PREFS_PATH && echo y || echo n")
        if (existsOut != "y") {
            Log.d(
                tag,
                "OB applyAutoDownloadOff: $FLUTTER_PREFS_PATH not present yet " +
                    "— skipping (OB has never been opened)"
            )
            return
        }

        // Quietly kill OpenBubbles so its in-memory SharedPreferences cache
        // can't write back over our edits. Throws if OB is currently focused.
        stopQuietly(tag)

        // Read current contents, capture original ownership.
        val (catExit, content, catErr) = rootExec("cat $FLUTTER_PREFS_PATH")
        if (catExit != 0) {
            Log.w(tag, "OB applyAutoDownloadOff: cat failed (exit=$catExit): $catErr")
            return
        }
        val (_, ownerOut, _) = rootExec("stat -c %u:%g $FLUTTER_PREFS_PATH")
        val owner = ownerOut.trim()

        val targets = mapOf(
            "flutter.autoDownload" to "false",
            "flutter.highPerfMode" to "true",
        )

        var modified = content
        var changes = 0
        for ((key, desiredValue) in targets) {
            val pattern = Regex(
                """<boolean\s+name="${Regex.escape(key)}"\s+value="(true|false)"\s*/>"""
            )
            val match = pattern.find(modified)
            if (match != null) {
                val current = match.groupValues[1]
                if (current == desiredValue) continue
                modified = pattern.replace(
                    modified,
                    """<boolean name="$key" value="$desiredValue" />"""
                )
                changes++
                Log.d(tag, "OB applyAutoDownloadOff: $key $current -> $desiredValue")
            } else if (modified.contains("</map>")) {
                modified = modified.replace(
                    "</map>",
                    "    <boolean name=\"$key\" value=\"$desiredValue\" />\n</map>"
                )
                changes++
                Log.d(tag, "OB applyAutoDownloadOff: $key absent — inserted as $desiredValue")
            } else {
                Log.w(
                    tag,
                    "OB applyAutoDownloadOff: prefs file missing </map> close — skipped $key"
                )
            }
        }

        if (changes == 0) {
            Log.d(tag, "OB applyAutoDownloadOff: already in desired state — no write")
            return
        }

        // Stage in cacheDir then cp into place — avoids embedding XML in a
        // shell heredoc. Same approach the migration used.
        val tmp = java.io.File(context.cacheDir, "_ob_prefs_apply.xml")
        try {
            tmp.writeText(modified)
            tmp.setReadable(true, /* ownerOnly = */ false)
            val (cpExit, _, cpErr) = rootExec("cp ${tmp.absolutePath} $FLUTTER_PREFS_PATH")
            if (cpExit != 0) {
                Log.w(tag, "OB applyAutoDownloadOff: cp failed (exit=$cpExit): $cpErr")
                return
            }
            if (owner.isNotEmpty()) rootExec("chown $owner $FLUTTER_PREFS_PATH")
            rootExec("chmod 660 $FLUTTER_PREFS_PATH")
            Log.i(tag, "OB applyAutoDownloadOff: wrote $changes change(s)")
        } finally {
            tmp.delete()
        }
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
