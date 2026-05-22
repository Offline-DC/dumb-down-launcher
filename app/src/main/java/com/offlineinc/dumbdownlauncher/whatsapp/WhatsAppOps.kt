package com.offlineinc.dumbdownlauncher.whatsapp

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.util.concurrent.TimeUnit

private const val TAG = "WhatsAppOps"

/**
 * Shared WhatsApp operations used by the auto-download-disable migration in
 * [com.offlineinc.dumbdownlauncher.DumbDownApp] and by
 * [WhatsAppAttachmentCleanupWorker]. Direct analog of
 * [com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesOps] — kept in the
 * same shape so that future maintenance of "kill the messenger app politely
 * and edit its private state" stays predictable across both apps.
 *
 * Everything that touches `/data/data/com.whatsapp` goes through
 * `su -c "nsenter -t 1 -m -- sh -c '…'"` because Android 10+ hides
 * cross-package /data/data behind per-process mount namespaces (same trick
 * `DumbDownApp.rootExec` uses). Operations on `/sdcard` would technically
 * work without nsenter, but we route them the same way for consistency and
 * to be robust if WhatsApp's media root ever moves back to /data/data on a
 * future Android version.
 */
object WhatsAppOps {

    /**
     * WhatsApp's application id. We deliberately don't handle the business
     * build (`com.whatsapp.w4b`) here — the dumb-down-launcher target user
     * doesn't run it, and conflating the two would force the migration and
     * worker to do twice as many `su` round-trips on every device.
     */
    const val PKG = "com.whatsapp"

    /**
     * Modern (Android 11+, scoped storage) WhatsApp media root. Confirmed
     * present on the TCL Flip Go (Android 11, WhatsApp 2.26.13.72) via
     * `scripts/whatsapp_probe.sh` — the legacy `/sdcard/WhatsApp/Media`
     * path is missing on this device, so we don't fall back to it. If we
     * ever need to support a phone running pre-Android-11 WhatsApp, this
     * is the constant to revisit.
     */
    const val MEDIA_ROOT = "/sdcard/Android/media/$PKG/WhatsApp/Media"

    /**
     * Subdirectories of [MEDIA_ROOT] that the cleanup is allowed to delete
     * from. Per-subdir restriction (as opposed to wiping the whole tree)
     * is intentional:
     *
     *  - `.Links/`           — link-preview thumbnails. WhatsApp regenerates
     *                          these from the source URL on demand, so
     *                          wiping is essentially free. Biggest steady-
     *                          state contributor to on-disk media size per
     *                          the probe (5.5 MB / 101 files on a fresh-ish
     *                          install).
     *  - `WhatsApp Images/`  — the user's largest growing chat-media
     *                          bucket. Old images on a companion device
     *                          can be re-fetched from WhatsApp's CDN
     *                          (within retention) or viewed on the
     *                          primary phone, where the canonical copy
     *                          lives.
     *  - `WhatsApp Video/`   — same logic as Images, with bigger per-file
     *                          impact when present.
     *
     * Everything else under [MEDIA_ROOT] (Voice Notes, Documents,
     * Animated Gifs, Audio, Stickers, etc.) is preserved by deliberate
     * omission. Voice notes and documents in particular tend to be
     * time-sensitive comms the user expects to scroll back to, and the
     * storage win from including them is small on this device.
     *
     * Top-level subdir names match what WhatsApp creates on disk —
     * including the spaces, which the find command handles via internal
     * double-quoting (see [clearOldAttachments]).
     */
    private val TARGET_SUBDIRS = listOf(
        ".Links",
        "WhatsApp Images",
        "WhatsApp Video",
    )

    /** Hard cap on `su` invocations (Magisk warm-up tolerance). */
    private const val SU_TIMEOUT_MS = 30_000L

    /**
     * Quietly stops the WhatsApp main process so a caller (specifically the
     * prefs-edit migration) can edit `shared_prefs/` without WhatsApp's
     * in-memory SharedPreferences cache writing back over the change.
     *
     *   * If WhatsApp is currently focused: THROWS [IllegalStateException].
     *     The migration framework will leave the migration unapplied and
     *     retry on the next boot — same deferred-retry shape as
     *     [com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesOps.stopQuietly].
     *
     *   * Otherwise: SIGKILLs every PID matching `com.whatsapp`. The probe
     *     captured only the main process running on this device; the
     *     `:voip` and `:gcm` sibling processes don't run continuously and
     *     get auto-restarted by Android if killed anyway. Looping over
     *     pidof's output handles the case where one or both happen to be
     *     alive.
     *
     *   * No-op if WhatsApp isn't running.
     *
     * The cleanup worker does NOT call this method. WhatsApp's media
     * lives on /sdcard, not /data/data, so file deletes there don't
     * race WhatsApp's in-memory state in a way that crashes the app.
     * With the current `delete everything` cutoff an in-flight
     * download could be unlinked mid-write — worst case WhatsApp
     * retries the download on its next sync. The user explicitly
     * opted into this trade-off by asking for "delete all whatsapp
     * media every night."
     */
    @JvmStatic
    fun stopQuietly(tag: String = TAG) {
        // Foreground = focused window. Both `mCurrentFocus` and
        // `mFocusedApp` are checked because the field name shifts across
        // Android versions / OEM builds.
        val (_, focusOut, _) = rootExec("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        if (focusOut.contains(PKG)) {
            throw IllegalStateException(
                "$PKG is currently the focused app — deferring kill to avoid looking like a crash"
            )
        }

        val (_, pidOut, _) = rootExec("pidof $PKG")
        val pids = pidOut.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (pids.isEmpty()) {
            Log.d(tag, "WA stop: $PKG not running — no kill needed")
            return
        }

        for (pid in pids) {
            val (killExit, _, killErr) = rootExec("kill -9 $pid")
            if (killExit != 0) {
                // Best-effort. Log and let the caller decide whether to
                // proceed — the worst case is WA's in-memory cache races
                // us, which we'll catch on the next migration retry.
                Log.w(tag, "WA stop: kill -9 $pid failed — exit=$killExit err=$killErr")
            } else {
                Log.d(tag, "WA stop: SIGKILLed pid=$pid")
            }
        }
    }

    /**
     * Result of one [clearOldAttachments] pass. `bytesFreedDisplay` is
     * best-effort — it captures the `du -sh` output of [MEDIA_ROOT] BEFORE
     * the delete pass, so it's "the size that contained the deleted files"
     * rather than a precise post-delete bytes-freed measurement.
     */
    data class ClearResult(
        val filesDeleted: Int,
        val bytesFreedDisplay: String,
    )

    /**
     * Deletes WhatsApp media files from the three [TARGET_SUBDIRS] under
     * [MEDIA_ROOT]. Other top-level subdirs (Voice Notes, Documents,
     * Animated Gifs, etc.) are NOT touched — see [TARGET_SUBDIRS] for
     * the rationale.
     *
     * Age cutoff: when [days] is `>= 0` we keep `-mtime +N` (files
     * strictly older than N*24h). When [days] is negative — the
     * current default for the nightly cron and the Free Up Space
     * button — we omit the `-mtime` predicate entirely and remove
     * every matching file in the target subdirs. The rolling-window
     * mode is preserved for callers that may want it later, but no
     * production path uses it today.
     *
     * **Critical: excludes `.nomedia` sentinel files.** WhatsApp creates
     * one in nearly every media subdirectory at install time
     * (e.g. `WhatsApp Images/Private/.nomedia`, `.Links/.nomedia`). They
     * tell Android's MediaScanner "do not index this folder" — deleting
     * them would cause WhatsApp's private/voice-note/link-thumbnail
     * content to start showing up in the device's Photos / Gallery apps.
     * The probe captured every `.nomedia` file dating back to install
     * day, so without the `! -name .nomedia` predicate the very first
     * cleanup run would silently break MediaScanner isolation for every
     * WhatsApp subdirectory on the device.
     *
     * Skips a missing subdir benignly (a fresh install creates these
     * lazily on first receive of that media type), and continues past
     * a per-subdir delete failure rather than aborting — one subdir
     * borking shouldn't poison the others.
     *
     * Bails benignly if [MEDIA_ROOT] itself doesn't exist. Does NOT call
     * [stopQuietly]; see that method's doc-comment for why.
     *
     * Per-subdir paths contain spaces (`WhatsApp Images`, `WhatsApp
     * Video`); the find command quotes them with internal double quotes,
     * which survive the outer single-quote wrap that [rootExec] applies.
     */
    @JvmStatic
    fun clearOldAttachments(days: Int, tag: String = TAG): ClearResult {
        // Bail benignly if the dir simply doesn't exist (fresh install
        // that has never received any media).
        val (_, existsOut, _) = rootExec("test -d $MEDIA_ROOT && echo y || echo n")
        if (existsOut != "y") {
            Log.d(tag, "WA clear: $MEDIA_ROOT doesn't exist — nothing to do")
            return ClearResult(filesDeleted = 0, bytesFreedDisplay = "0")
        }

        // Best-effort total size before — captured BEFORE the delete pass
        // so the log line correctly reflects "the dir was this big before
        // we trimmed it". `du -sh` walks the whole tree and is cheap on a
        // dir with hundreds of files at most.
        val (_, beforeSize, _) = rootExec("du -sh $MEDIA_ROOT 2>/dev/null | cut -f1")
        val display = beforeSize.takeIf { it.isNotEmpty() } ?: "?"

        var totalDeleted = 0
        for (subdir in TARGET_SUBDIRS) {
            val subPath = "$MEDIA_ROOT/$subdir"

            // Skip cleanly if a particular subdir doesn't exist yet.
            // WhatsApp creates these on first receive of that media type,
            // so on a fresh-ish install some may genuinely be absent.
            val (_, subExistsOut, _) = rootExec(
                "test -d \"$subPath\" && echo y || echo n"
            )
            if (subExistsOut != "y") {
                Log.d(tag, "WA clear: $subPath missing — skipping")
                continue
            }

            // The age predicate is only included when `days >= 0`; the
            // negative-sentinel path drops it so we wipe every matching
            // file. Composed once, used by both the count and delete
            // passes so they can't drift.
            val agePredicate = if (days >= 0) "-mtime +$days " else ""

            // Count first so the per-subdir log line is informative.
            // Cheap; find walks the same tree the delete pass will.
            val (_, countOut, _) = rootExec(
                "find \"$subPath\" -type f $agePredicate! -name .nomedia | wc -l"
            )
            val n = countOut.trim().toIntOrNull() ?: 0

            // The actual delete. With days<0 the agePredicate above is
            // empty, so the find matches every file in the subdir
            // (still excluding the `.nomedia` sentinel — that exclusion
            // is non-negotiable; see the method-level doc-comment).
            val (delExit, _, delErr) = rootExec(
                "find \"$subPath\" -type f $agePredicate! -name .nomedia -delete"
            )
            if (delExit != 0) {
                // Don't fall back to `rm -rf` style wipes — those would
                // break the .nomedia exclusion. Log and continue so a
                // failure in one subdir doesn't block cleanup of the
                // others. The next nightly run will retry.
                Log.w(
                    tag,
                    "WA clear: delete in $subdir failed (exit=$delExit): $delErr — continuing"
                )
                continue
            }

            Log.d(tag, "WA clear: deleted $n file(s) older than $days days from $subdir")
            totalDeleted += n
        }

        Log.d(
            tag,
            "WA clear: deleted $totalDeleted total file(s) across " +
                "${TARGET_SUBDIRS.size} subdir(s) of $MEDIA_ROOT (was $display)"
        )
        return ClearResult(filesDeleted = totalDeleted, bytesFreedDisplay = display)
    }

    /** Where WhatsApp stores the auto-download bitmask prefs. */
    private const val PREFS_PATH =
        "/data/data/$PKG/shared_prefs/com.whatsapp_preferences_light.xml"

    /**
     * Re-applies the opinionated WhatsApp auto-download-off masks that
     * are also set at provisioning time by the `whatsapp_setup_v1` migration
     * in [com.offlineinc.dumbdownlauncher.DumbDownApp]. Safe to call any
     * number of times — when the values on disk already match, no writes
     * happen.
     *
     * Settings asserted (bitmask: 1=images, 2=audio, 4=video, 8=documents):
     *  - `autodownload_cellular_mask` → 0  (no cellular auto-download)
     *  - `autodownload_wifi_mask`     → 0  (no wifi auto-download)
     *  - `autodownload_roaming_mask`  → 0  (no roaming auto-download)
     *
     * Defense-in-depth: WhatsApp rewrites this XML on its own schedule,
     * and an app update could revert these values. The
     * [WhatsAppAttachmentCleanupWorker] calls this nightly so a drift can
     * cause at most one day of auto-downloads.
     *
     * Behaviour:
     *  - Returns benignly when WhatsApp isn't installed.
     *  - Returns benignly when the prefs file doesn't exist yet (WhatsApp
     *    has never been opened — file is created lazily on first launch).
     *  - Calls [stopQuietly] before editing — so this method also THROWS
     *    [IllegalStateException] when WhatsApp is focused. Callers should
     *    catch and skip.
     *  - Uses [context]'s cacheDir to stage the new content, then `cp` via
     *    root into the target path. Restores original ownership and 600
     *    file mode (matches what com.whatsapp_preferences_light.xml ships
     *    with — note: 600, not 660 like the OpenBubbles equivalent).
     */
    @JvmStatic
    fun applyAutoDownloadMaskZero(context: Context, tag: String = TAG) {
        // Skip cleanly when WhatsApp isn't installed.
        try {
            context.packageManager.getPackageInfo(PKG, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            Log.d(tag, "WA applyAutoDownloadMaskZero: $PKG not installed — skipping")
            return
        }

        // The prefs file doesn't exist until WhatsApp is launched once.
        val (_, existsOut, _) = rootExec("test -f $PREFS_PATH && echo y || echo n")
        if (existsOut != "y") {
            Log.d(
                tag,
                "WA applyAutoDownloadMaskZero: $PREFS_PATH not present yet " +
                    "— skipping (WA has never been opened)"
            )
            return
        }

        // Quietly kill WhatsApp so its in-memory SharedPreferences cache
        // can't write back over our edits. Throws if WA is currently focused.
        stopQuietly(tag)

        // Read current contents, capture original ownership.
        val (catExit, content, catErr) = rootExec("cat $PREFS_PATH")
        if (catExit != 0) {
            Log.w(tag, "WA applyAutoDownloadMaskZero: cat failed (exit=$catExit): $catErr")
            return
        }
        val (_, ownerOut, _) = rootExec("stat -c %u:%g $PREFS_PATH")
        val owner = ownerOut.trim()

        val targets = mapOf(
            "autodownload_cellular_mask" to "0",
            "autodownload_wifi_mask" to "0",
            "autodownload_roaming_mask" to "0",
        )

        var modified = content
        var changes = 0
        for ((key, desiredValue) in targets) {
            val pattern = Regex(
                """<int\s+name="${Regex.escape(key)}"\s+value="(\d+)"\s*/>"""
            )
            val match = pattern.find(modified)
            if (match != null) {
                val current = match.groupValues[1]
                if (current == desiredValue) continue
                modified = pattern.replace(
                    modified,
                    """<int name="$key" value="$desiredValue" />"""
                )
                changes++
                Log.d(tag, "WA applyAutoDownloadMaskZero: $key $current -> $desiredValue")
            } else if (modified.contains("</map>")) {
                modified = modified.replace(
                    "</map>",
                    "    <int name=\"$key\" value=\"$desiredValue\" />\n</map>"
                )
                changes++
                Log.d(tag, "WA applyAutoDownloadMaskZero: $key absent — inserted as $desiredValue")
            } else {
                Log.w(
                    tag,
                    "WA applyAutoDownloadMaskZero: prefs file missing </map> close — skipped $key"
                )
            }
        }

        if (changes == 0) {
            Log.d(tag, "WA applyAutoDownloadMaskZero: already in desired state — no write")
            return
        }

        // Stage in cacheDir then cp into place — avoids embedding XML in a
        // shell heredoc. Same approach the migration used.
        val tmp = java.io.File(context.cacheDir, "_wa_prefs_apply.xml")
        try {
            tmp.writeText(modified)
            tmp.setReadable(true, /* ownerOnly = */ false)
            val (cpExit, _, cpErr) = rootExec("cp ${tmp.absolutePath} $PREFS_PATH")
            if (cpExit != 0) {
                Log.w(tag, "WA applyAutoDownloadMaskZero: cp failed (exit=$cpExit): $cpErr")
                return
            }
            if (owner.isNotEmpty()) rootExec("chown $owner $PREFS_PATH")
            // 600 matches what we observed on-device for this file.
            rootExec("chmod 600 $PREFS_PATH")
            Log.i(tag, "WA applyAutoDownloadMaskZero: wrote $changes change(s)")
        } finally {
            tmp.delete()
        }
    }

    /**
     * Runs a shell command via Magisk `su` and `nsenter -t 1 -m`. Same
     * pattern as [com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesOps]'s
     * private rootExec and `DumbDownApp.rootExec`. Drains both streams
     * before `waitFor()` to avoid pipe deadlocks on long output.
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
