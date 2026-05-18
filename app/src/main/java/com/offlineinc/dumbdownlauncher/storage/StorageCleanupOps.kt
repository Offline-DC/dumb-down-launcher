package com.offlineinc.dumbdownlauncher.storage

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

private const val TAG = "StorageCleanupOps"

/**
 * Manual-only storage cleanup operations exposed through the in-launcher
 * "free up space" screen ([FreeUpSpaceActivity]).
 *
 * Distinct from the auto/nightly cleanups in [com.offlineinc.dumbdownlauncher.calllog.CallLogCleanupWorker],
 * [com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesAttachmentCleanupWorker],
 * and [com.offlineinc.dumbdownlauncher.whatsapp.WhatsAppAttachmentCleanupWorker]:
 *
 *   - Auto / nightly: things the user didn't *curate*. Message attachments,
 *     thumbnails, system call log. Losing them costs at worst a re-fetch
 *     from a cloud they were already syncing from.
 *
 *   - Manual / on-demand (this file): things the user *did* curate. Offline
 *     music they downloaded, podcast episodes they queued. Wiping these on
 *     a schedule would surprise the user; we expose them as buttons so the
 *     user opts in each time.
 *
 * Each op:
 *   - Computes a `du -sk` "before" size for the [ClearResult].
 *   - Runs the delete pass via root + `nsenter` (`/data/data` is hidden
 *     across mount namespaces; same trick the other Ops files use).
 *   - Persists a `last_run_at_ms` + `bytes_freed` record to the
 *     "storage_cleanup" SharedPreferences file so the UI can show a
 *     "last cleared 2h ago" subtitle on each row.
 *   - Returns a [ClearResult]; never throws on missing dirs or missing
 *     packages — just returns size=0.
 */
object StorageCleanupOps {

    /** Hard cap on `su` invocations (Magisk warm-up tolerance). */
    private const val SU_TIMEOUT_MS = 30_000L

    /** SharedPreferences file where "last cleared" records live. */
    const val PREFS_NAME = "storage_cleanup"

    /**
     * Auto-clear threshold for the nightly Spotify worker. The on-disk
     * layout makes streaming cache and user-marked offline downloads
     * inseparable externally (both live in `Storage/<hex>/` as content-
     * addressed hash chunks; only Spotify's internal LevelDB knows which
     * is which — see STORAGE_PLAN.md §0.3). So the auto path uses a size
     * gate as a probabilistic preservation:
     *
     *  - Cache under 500 MB → almost certainly no GB-scale streaming-cache
     *    bloat. Worker skips the wipe, downloads survive.
     *  - Cache over 500 MB → likely accumulating streaming cache the
     *    user didn't ask for. Worker wipes. If the user *did* have
     *    >500 MB of downloads, they pay a Wi-Fi re-download on next play.
     *
     * The threshold is a compromise: lower values recover more aggressively
     * but catch more downloaders, higher values protect downloaders more
     * but leave more bloat on disk. 500 MB picked because (a) a typical
     * Spotify album is ~50–100 MB so 5+ albums comfortably fit underneath,
     * and (b) the in-the-wild 1.3 GB cache figure is well past it.
     *
     * The MANUAL "Clear Spotify offline" button in [FreeUpSpaceScreen]
     * stays unconditional — that path is for users explicitly choosing to
     * nuke everything, and gets its own confirm dialog.
     */
    const val SPOTIFY_AUTO_CLEAR_THRESHOLD_BYTES: Long = 500L * 1024L * 1024L  // 500 MB

    /** Filesystem path of Spotify's combined cache + offline-download store. */
    private const val SPOTIFY_CACHE_DIR =
        "/data/user_de/0/com.spotify.music/Android/data/com.spotify.music/files/spotifycache"

    // ── Public types ──────────────────────────────────────────────────────

    /**
     * Result of one cleanup call. [bytesFreed] is the kilobytes returned
     * by `du -sk` on the target dir BEFORE the delete pass, multiplied by
     * 1024. Best-effort — if `du` failed, falls back to 0 (still "success",
     * just unmeasurable).
     */
    data class ClearResult(
        /** Best-effort approximation of how many bytes were reclaimed. */
        val bytesFreed: Long,
        /** Human-readable display value, e.g. "109M". Empty when 0. */
        val bytesFreedDisplay: String,
    )

    /** Stable IDs used by the UI + receiver to identify each op. */
    enum class Target(internal val prefsKey: String) {
        APP_CACHES("app_caches"),
        ANTENNAPOD("antennapod"),
        SPOTIFY_OFFLINE("spotify_offline"),
        APPLE_MUSIC_OFFLINE("apple_music_offline"),
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Runs `pm trim-caches 99999999999` to drain every app's `cache/` and
     * `code_cache/` dirs. Will also clear AntennaPod's episode cache
     * (which lives in `cache/`), so callers should make the user-facing
     * label explicit about that.
     *
     * `pm trim-caches` doesn't print bytes-freed; we approximate by
     * `du`-summing `/data/data/*/cache` and `/data/data/*/code_cache`
     * before the trim. Inflated slightly because not every app's cache
     * is reclaimable (apps that called [android.os.storage.StorageManager.allocateBytes]
     * lock their cache against the trim), but close enough for a UI row.
     */
    @JvmStatic
    fun trimAppCaches(context: Context, tag: String = TAG): ClearResult {
        val before = duSumKb(
            "for d in /data/data/*/cache /data/data/*/code_cache; do " +
                "[ -d \"\$d\" ] || continue; du -sk \"\$d\" 2>/dev/null; " +
                "done | awk '{s+=\$1} END {print s+0}'"
        )

        val (trimExit, _, trimErr) = rootExec("pm trim-caches 99999999999")
        if (trimExit != 0) {
            Log.w(tag, "trimAppCaches: pm trim-caches failed exit=$trimExit err=$trimErr")
            return record(context, Target.APP_CACHES, 0L)
        }

        val after = duSumKb(
            "for d in /data/data/*/cache /data/data/*/code_cache; do " +
                "[ -d \"\$d\" ] || continue; du -sk \"\$d\" 2>/dev/null; " +
                "done | awk '{s+=\$1} END {print s+0}'"
        )
        val freedKb = (before - after).coerceAtLeast(0L)
        val freedBytes = freedKb * 1024L
        Log.i(tag, "trimAppCaches: freed ~${freedKb / 1024} MB (before=${before}KB, after=${after}KB)")
        return record(context, Target.APP_CACHES, freedBytes)
    }

    /**
     * Wipes `/data/data/de.danoeh.antennapod/cache/`. The audit captured
     * 105 MB / 144 episodes here on the test device. AntennaPod stores
     * downloaded episodes in `cache/` (against the usual Android contract)
     * which is why they get cleared.
     */
    @JvmStatic
    fun clearAntennaPodEpisodes(context: Context, tag: String = TAG): ClearResult {
        val dir = "/data/data/de.danoeh.antennapod/cache"
        return clearDirectoryContents(context, Target.ANTENNAPOD, dir, tag)
    }

    /**
     * Wipes Spotify's offline-cache dir at [SPOTIFY_CACHE_DIR].
     *
     * Device-Encrypted storage, accessible only with root (we have it).
     * Empties the directory contents but preserves the directory itself
     * so Spotify doesn't trip when re-downloading.
     *
     * **Unconditional.** Wipes everything in the dir — both streaming
     * cache and user-marked offline downloads. The two are inseparable
     * externally (content-addressable store; see [SPOTIFY_AUTO_CLEAR_THRESHOLD_BYTES]
     * for details). Users who want to preserve downloads should rely on
     * the auto-tier path [clearSpotifyOfflineIfOverThreshold], which gates
     * on cache size; this op is the unconditional path used by the
     * manual "Clear Spotify offline" button in [FreeUpSpaceScreen] and
     * the adb trigger receiver.
     */
    @JvmStatic
    fun clearSpotifyOffline(context: Context, tag: String = TAG): ClearResult {
        return clearDirectoryContents(context, Target.SPOTIFY_OFFLINE, SPOTIFY_CACHE_DIR, tag)
    }

    /**
     * Size-gated variant of [clearSpotifyOffline] used by the nightly
     * worker [SpotifyOfflineCleanupWorker]. Probabilistically preserves
     * user-marked offline downloads on devices where the cache hasn't
     * grown past [SPOTIFY_AUTO_CLEAR_THRESHOLD_BYTES] — see that const's
     * doc-comment for the design rationale.
     *
     * Behaviour:
     *  - Cache size < threshold → returns a zero [ClearResult] without
     *    touching the directory and **without** updating the
     *    `last_run_at_ms` SharedPreferences record. The UI's "last
     *    cleared" subtitle stays whatever it was. This is correct: no
     *    clear actually happened, so reporting one would be misleading.
     *  - Cache size ≥ threshold → delegates to [clearSpotifyOffline],
     *    which performs the wipe and updates the record as usual.
     *
     * Always returns successfully (a zero-bytes result counts as
     * "checked, nothing to do") so a periodic worker calling this
     * doesn't drop from WorkManager on repeated under-threshold runs.
     */
    @JvmStatic
    fun clearSpotifyOfflineIfOverThreshold(
        context: Context,
        tag: String = TAG,
    ): ClearResult {
        val sizeBytes = directorySizeBytes(SPOTIFY_CACHE_DIR)
        val thresholdMb = SPOTIFY_AUTO_CLEAR_THRESHOLD_BYTES / (1024L * 1024L)
        val sizeMb = sizeBytes / (1024L * 1024L)
        if (sizeBytes < SPOTIFY_AUTO_CLEAR_THRESHOLD_BYTES) {
            Log.i(
                tag,
                "spotify offline (auto): cache=${sizeMb}MB under threshold ${thresholdMb}MB — skipping wipe"
            )
            return ClearResult(bytesFreed = 0L, bytesFreedDisplay = "")
        }
        Log.i(
            tag,
            "spotify offline (auto): cache=${sizeMb}MB over threshold ${thresholdMb}MB — running full clear"
        )
        return clearSpotifyOffline(context, tag)
    }

    /**
     * Wipes Apple Music's offline downloads using a heuristic — the audit
     * didn't fully localize where the bulk of `com.apple.android.music`'s
     * 49 MB lives (only ~7 MB in obvious cache subdirs), so we match
     * audio container extensions under `files/` and any obviously-named
     * offline subdir. First runs log what matched so the pattern can be
     * tightened later from a real device's debug log.
     */
    @JvmStatic
    fun clearAppleMusicOffline(context: Context, tag: String = TAG): ClearResult {
        // Best-effort size: sum sizes of the files we're about to delete.
        // Important to compute BEFORE the find -delete pass.
        val sizeCmd =
            "find /data/data/com.apple.android.music/files " +
                "\\( -iname '*.m4a' -o -iname '*.aac' -o -iname '*.mp4' " +
                "   -o -iname '*.movpkg' -o -iname '*.fp4' \\) " +
                "-type f -exec du -sk {} + 2>/dev/null | awk '{s+=\$1} END {print s+0}'"
        val beforeKb = duSumKb(sizeCmd)

        val delCmd =
            "find /data/data/com.apple.android.music/files " +
                "\\( -iname '*.m4a' -o -iname '*.aac' -o -iname '*.mp4' " +
                "   -o -iname '*.movpkg' -o -iname '*.fp4' \\) " +
                "-type f -print -delete 2>/dev/null"
        val (delExit, delOut, delErr) = rootExec(delCmd)
        if (delExit != 0) {
            // Failed root call — record nothing-freed, return.
            Log.w(tag, "clearAppleMusicOffline: find/delete failed exit=$delExit err=$delErr")
            return record(context, Target.APPLE_MUSIC_OFFLINE, 0L)
        }
        if (delOut.isNotBlank()) {
            // Log what we matched so we can refine the pattern on the next
            // audit. Limited to first 1 KB to avoid log spam.
            Log.i(
                tag,
                "clearAppleMusicOffline: deleted files:\n${delOut.take(1024)}" +
                    if (delOut.length > 1024) "\n…(truncated)" else ""
            )
        }
        val freedBytes = beforeKb * 1024L
        return record(context, Target.APPLE_MUSIC_OFFLINE, freedBytes)
    }

    // ── Size queries (used by the UI before showing rows) ─────────────────

    /**
     * Returns the size of [path] in bytes via `du -sk`. Zero if the path
     * doesn't exist, isn't a directory, or the call failed. Cheap — `du`
     * stat-walks one subtree, no read I/O.
     */
    @JvmStatic
    fun directorySizeBytes(path: String): Long {
        val (_, exists, _) = rootExec("test -d $path && echo y || echo n")
        if (exists != "y") return 0L
        val (_, out, _) = rootExec("du -sk $path 2>/dev/null")
        return out.split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.times(1024L) ?: 0L
    }

    /**
     * Heuristic size for Apple Music offline downloads — matches the same
     * extensions [clearAppleMusicOffline] deletes. Returns 0 on a missing
     * dir or a failed root call.
     */
    @JvmStatic
    fun appleMusicOfflineSizeBytes(): Long {
        val sizeCmd =
            "find /data/data/com.apple.android.music/files " +
                "\\( -iname '*.m4a' -o -iname '*.aac' -o -iname '*.mp4' " +
                "   -o -iname '*.movpkg' -o -iname '*.fp4' \\) " +
                "-type f -exec du -sk {} + 2>/dev/null | awk '{s+=\$1} END {print s+0}'"
        return duSumKb(sizeCmd) * 1024L
    }

    /** Sum of every app's cache/ and code_cache/ in bytes. */
    @JvmStatic
    fun totalAppCachesSizeBytes(): Long {
        val cmd =
            "for d in /data/data/*/cache /data/data/*/code_cache; do " +
                "[ -d \"\$d\" ] || continue; du -sk \"\$d\" 2>/dev/null; " +
                "done | awk '{s+=\$1} END {print s+0}'"
        return duSumKb(cmd) * 1024L
    }

    // ── "Last cleared" record ─────────────────────────────────────────────

    /** Returns the timestamp (epoch ms) of the last cleanup of [target], or 0. */
    @JvmStatic
    fun lastRunAtMs(context: Context, target: Target): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("${target.prefsKey}.last_run_at_ms", 0L)

    /** Returns the bytes freed by the last cleanup of [target], or 0. */
    @JvmStatic
    fun lastBytesFreed(context: Context, target: Target): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("${target.prefsKey}.last_bytes_freed", 0L)

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Deletes everything inside [dir] but preserves the directory itself
     * (so the host app doesn't trip when it next tries to write something
     * back into it). Returns the size of [dir] before the delete as
     * [ClearResult.bytesFreed]. Safe to call on missing dirs.
     */
    private fun clearDirectoryContents(
        context: Context,
        target: Target,
        dir: String,
        tag: String,
    ): ClearResult {
        val (_, exists, _) = rootExec("test -d $dir && echo y || echo n")
        if (exists != "y") {
            Log.d(tag, "$target: $dir doesn't exist — nothing to clear")
            return record(context, target, 0L)
        }
        val beforeKb = duSumKb("du -sk $dir 2>/dev/null | awk '{print \$1}'")
        val (delExit, _, delErr) = rootExec("find $dir -mindepth 1 -delete")
        if (delExit != 0) {
            Log.w(tag, "$target: find -delete failed exit=$delExit err=$delErr")
            return record(context, target, 0L)
        }
        val freedBytes = beforeKb * 1024L
        Log.i(tag, "$target: cleared $dir (was ~${beforeKb / 1024} MB)")
        return record(context, target, freedBytes)
    }

    /** Records the cleanup outcome and returns the matching [ClearResult]. */
    private fun record(context: Context, target: Target, bytesFreed: Long): ClearResult {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("${target.prefsKey}.last_run_at_ms", System.currentTimeMillis())
            .putLong("${target.prefsKey}.last_bytes_freed", bytesFreed)
            .apply()
        return ClearResult(
            bytesFreed = bytesFreed,
            bytesFreedDisplay = if (bytesFreed > 0) formatBytes(bytesFreed) else "",
        )
    }

    /** Runs the command, parses the first whitespace-delimited integer as KB, returns it. */
    private fun duSumKb(cmd: String): Long {
        val (_, out, _) = rootExec(cmd)
        return out.split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
    }

    /** Human-readable byte size — same shape `du -h` gives. */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f K".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.0f M".format(mb)
        val gb = mb / 1024.0
        return "%.1f G".format(gb)
    }

    /**
     * Runs a shell command via Magisk `su` and `nsenter -t 1 -m`. Same
     * pattern as the other Ops files. Drains both streams before
     * `waitFor()` to avoid pipe deadlocks.
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

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
