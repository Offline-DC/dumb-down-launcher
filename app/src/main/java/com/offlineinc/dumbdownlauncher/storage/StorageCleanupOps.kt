package com.offlineinc.dumbdownlauncher.storage

import android.content.Context
import android.util.Log
import com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesOps
import com.offlineinc.dumbdownlauncher.whatsapp.WhatsAppOps
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
 *   - Returns a [ClearResult]; never throws on missing dirs or missing
 *     packages — just returns size=0.
 *
 * Previously each op also persisted a `last_run_at_ms` + `bytes_freed`
 * record so the UI could show a "last cleared 2h ago" subtitle on each
 * row. That history is no longer surfaced (rows always render their
 * static description), and the persistence step has been removed to
 * keep this object stateless. The transient "just cleared $N MB" green
 * banner at the top of the Free Up Space screen is driven from the
 * returned [ClearResult] directly, so it survives without the prefs.
 */
object StorageCleanupOps {

    /** Hard cap on `su` invocations (Magisk warm-up tolerance). */
    private const val SU_TIMEOUT_MS = 30_000L

    /**
     * Auto-clear threshold for the nightly Spotify worker. The on-disk
     * layout makes streaming cache and user-marked offline downloads
     * inseparable externally (both live in `Storage/<hex>/` as content-
     * addressed hash chunks; only Spotify's internal LevelDB knows which
     * is which — see STORAGE_PLAN.md §0.3). So the auto path uses a size
     * gate as a probabilistic preservation:
     *
     *  - Cache under 400 MB → almost certainly no GB-scale streaming-cache
     *    bloat. Worker skips the wipe, downloads survive.
     *  - Cache over 400 MB → likely accumulating streaming cache the
     *    user didn't ask for. Worker wipes. If the user *did* have
     *    >400 MB of downloads, they pay a Wi-Fi re-download on next play.
     *
     * The threshold is a compromise: lower values recover more aggressively
     * but catch more downloaders, higher values protect downloaders more
     * but leave more bloat on disk. 400 MB picked because (a) a typical
     * Spotify album is ~50–100 MB so 4 albums comfortably fit underneath,
     * (b) the in-the-wild 1.3 GB cache figure is well past it, and (c)
     * it's tight enough that streaming cache from one binge listening
     * session triggers the wipe without waiting another day.
     *
     * The MANUAL "Clear Spotify offline" button in [FreeUpSpaceScreen]
     * stays unconditional — that path is for users explicitly choosing to
     * nuke everything, and gets its own confirm dialog.
     */
    const val SPOTIFY_AUTO_CLEAR_THRESHOLD_BYTES: Long = 400L * 1024L * 1024L  // 400 MB

    /** Filesystem path of Spotify's combined cache + offline-download store. */
    private const val SPOTIFY_CACHE_DIR =
        "/data/user_de/0/com.spotify.music/Android/data/com.spotify.music/files/spotifycache"

    /** Android package name for AntennaPod. */
    private const val ANTENNAPOD_PKG = "de.danoeh.antennapod"

    /**
     * Every dir AntennaPod 3.x is known to write episodes / caches to on
     * the audit device (TCL Flip 6 / Android 11). The headline number is
     * `files/media/` under external app-private storage — confirmed at
     * 101 MB by `antennapod_audit.sh` against an internal-cache footprint
     * of <6 MB, which is why the original single-path `cache/` cleanup
     * looked like a no-op to users.
     *
     *  - `/data/data/<pkg>/cache` — internal cache (legacy episodes,
     *    transient blobs). Small on modern AntennaPod but kept in the
     *    wipe list as belt-and-suspenders.
     *  - `/data/media/0/Android/data/<pkg>/files/media` — actual default
     *    download location for episodes. We use the `/data/media/0/...`
     *    real path rather than the `/sdcard/...` symlink because root
     *    shells on some Android builds (incl. this TCL) don't always
     *    have the sdcardfs/fuse view set up — same convention the rest
     *    of this file uses for shared storage.
     *  - `/data/media/0/Android/data/<pkg>/files/cache` — AntennaPod's
     *    external cache dir. Empty on the audit device but listed in case
     *    a future version starts using it.
     *
     * If a user has manually re-pointed the data folder in AntennaPod's
     * settings, only the internal `cache/` will be in this list; the
     * audit script's section 6 dumps `shared_prefs` so we can extend the
     * list if that case shows up in the wild.
     */
    private val ANTENNAPOD_CLEAR_DIRS = listOf(
        "/data/data/$ANTENNAPOD_PKG/cache",
        "/data/media/0/Android/data/$ANTENNAPOD_PKG/files/media",
        "/data/media/0/Android/data/$ANTENNAPOD_PKG/files/cache",
    )

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
    enum class Target(internal val logKey: String) {
        APP_CACHES("app_caches"),
        ANTENNAPOD("antennapod"),
        SPOTIFY_OFFLINE("spotify_offline"),
        APPLE_MUSIC_OFFLINE("apple_music_offline"),
        WHATSAPP_MEDIA("whatsapp_media"),
        OPENBUBBLES_ATTACHMENTS("openbubbles_attachments"),
    }

    /**
     * Sentinel for both the manual [clearWhatsAppOldMedia] call and the
     * nightly
     * [com.offlineinc.dumbdownlauncher.whatsapp.WhatsAppAttachmentCleanupWorker].
     * Negative value tells
     * [com.offlineinc.dumbdownlauncher.whatsapp.WhatsAppOps.clearOldAttachments]
     * to omit the `-mtime` age predicate, so every photo/video in the
     * three target subdirs is deleted — no rolling-window protection
     * for recent media. The framing is "wipe the device, the originals
     * stay on your other devices and on WhatsApp's CDN."
     *
     * Duplicated here on purpose so the manual button stays bit-for-bit
     * equivalent to the cron (`adb`-driven trigger receiver runs
     * through the same path). The WhatsApp messages themselves are
     * not touched — only the three media subdirs the worker is
     * allowed to clean (`WhatsApp Images`, `WhatsApp Video`, `.Links`).
     */
    private const val WHATSAPP_CRON_CUTOFF_DAYS = -1

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Drains every app's `cache/` and `code_cache/` dirs **except**
     * AntennaPod's — that one is split out into its own
     * [clearAntennaPodEpisodes] row in [FreeUpSpaceScreen] so the user
     * controls it independently, and stays out of this bucket's size
     * estimate too (see [totalAppCachesSizeBytes]).
     *
     * Previously this called `pm trim-caches 99999999999`, which is the
     * OS-blessed "trim everyone" command but takes no exclusion list and
     * thus wiped AntennaPod alongside everything else. We swap to a
     * direct `find -delete` per cache dir (we have root) with the
     * AntennaPod package skipped. The trade-off: we lose the
     * [android.os.storage.StorageManager.allocateBytes] lock protection
     * that `pm trim-caches` honours. That's intentional — cache/ is
     * safe-to-wipe by Android contract and the locks rarely apply on
     * the featurephone-class devices this app targets.
     *
     * Bytes-freed is computed as before-du minus after-du across the
     * same set of dirs we ran the delete on (AntennaPod excluded), so
     * the figure is consistent with what the row's size estimate said.
     */
    @JvmStatic
    fun trimAppCaches(context: Context, tag: String = TAG): ClearResult {
        val before = duSumKb(appCachesSumCmd(excludePackage = ANTENNAPOD_PKG))

        val (delExit, _, delErr) = rootExec(appCachesDeleteCmd(excludePackage = ANTENNAPOD_PKG))
        if (delExit != 0) {
            Log.w(tag, "trimAppCaches: find -delete failed exit=$delExit err=$delErr")
            return record(context, Target.APP_CACHES, 0L)
        }

        val after = duSumKb(appCachesSumCmd(excludePackage = ANTENNAPOD_PKG))
        val freedKb = (before - after).coerceAtLeast(0L)
        val freedBytes = freedKb * 1024L
        Log.i(tag, "trimAppCaches: freed ~${freedKb / 1024} MB (before=${before}KB, after=${after}KB)")
        return record(context, Target.APP_CACHES, freedBytes)
    }

    /**
     * Builds the shell snippet that iterates `/data/data/<pkg>/cache` and
     * `code_cache/` for every package, skipping [excludePackage], and
     * runs `du -sk` on each so the caller can pipe through `awk` to sum.
     */
    private fun appCachesSumCmd(excludePackage: String): String =
        "for d in /data/data/*/cache /data/data/*/code_cache; do " +
            "[ -d \"\$d\" ] || continue; " +
            "case \"\$d\" in /data/data/$excludePackage/*) continue ;; esac; " +
            "du -sk \"\$d\" 2>/dev/null; " +
            "done | awk '{s+=\$1} END {print s+0}'"

    /**
     * Builds the shell snippet that empties every cache/ and code_cache/
     * dir, skipping [excludePackage]. Mirrors [appCachesSumCmd] so the
     * size estimate and the actual delete pass agree on which dirs are
     * in-scope.
     */
    private fun appCachesDeleteCmd(excludePackage: String): String =
        "for d in /data/data/*/cache /data/data/*/code_cache; do " +
            "[ -d \"\$d\" ] || continue; " +
            "case \"\$d\" in /data/data/$excludePackage/*) continue ;; esac; " +
            "find \"\$d\" -mindepth 1 -delete 2>/dev/null; " +
            "done; exit 0"

    /**
     * Wipes every dir AntennaPod is known to store downloaded episodes
     * in — see [ANTENNAPOD_CLEAR_DIRS] for the list and the rationale.
     *
     * Each dir is cleared independently and the freed-byte totals are
     * summed. A missing dir contributes 0 (no failure), so this op is
     * safe on devices where AntennaPod is using only a subset of the
     * tracked locations.
     *
     * Returns a single [ClearResult] for the [Target.ANTENNAPOD] row
     * with the summed bytes — the per-dir calls to [clearDirectoryContents]
     * each construct their own result, but only the summed one is
     * returned and shown to the UI as the "just cleared $N MB" banner.
     */
    @JvmStatic
    fun clearAntennaPodEpisodes(context: Context, tag: String = TAG): ClearResult {
        var totalFreed = 0L
        for (dir in ANTENNAPOD_CLEAR_DIRS) {
            val result = clearDirectoryContents(context, Target.ANTENNAPOD, dir, tag)
            totalFreed += result.bytesFreed
        }
        return record(context, Target.ANTENNAPOD, totalFreed)
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
     *    touching the directory. This is correct: no clear actually
     *    happened, so the worker logs a "skipped" line and moves on.
     *  - Cache size ≥ threshold → delegates to [clearSpotifyOffline],
     *    which performs the wipe.
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
     * Wipes Apple Music's downloaded songs.
     *
     * Pinned by `apple_music_audit_v2.sh` against Apple Music for Android
     * 4.6.0 on the TCL Flip 6 / Android 11 audit device
     * (`key_download_location=APPSPACE`, the device-storage mode).
     * Downloads live as flat `<numeric-id>_HQ.m4p` files under
     * `cache/playback_assets/` — a subdir of the app's internal cache
     * that Apple Music uses as a content-addressable store for both
     * streamed and explicitly-downloaded tracks. The v1 audit's claim of
     * 340 MB at `no_backup/assets/` did not reproduce on v2 (that dir
     * was 222K on this device, with zero `.m4a` files) — so the search
     * paths and extension list below are the union of v1 and v2 findings,
     * keeping the old locations as defensive fallbacks in case Apple
     * Music splits its storage differently across app versions or
     * keystore states.
     *
     * On top of the credential-encrypted `/data/data/...` path, we also
     * probe `/data/user_de/0/com.apple.android.music/...` — the
     * device-encrypted twin — because the in-app "Downloaded" total
     * reported by users (356 MB on the audit device) often outstrips
     * what `du` finds under `/data/data/` alone, suggesting some content
     * lives in the DE tree. Same pattern Spotify uses, see
     * [SPOTIFY_CACHE_DIR].
     *
     * We still match by extension rather than nuking entire directories
     * — `cache/` also holds WebView state, ad-tech caches, and other
     * non-audio blobs that we'd rather leave for Android's normal cache
     * trim. The extension list keeps deletions narrowed to audio
     * container files Apple Music is known to write.
     */
    @JvmStatic
    fun clearAppleMusicOffline(context: Context, tag: String = TAG): ClearResult {
        // Best-effort size: sum sizes of the files we're about to delete.
        // Important to compute BEFORE the find -delete pass.
        val beforeKb = duSumKb(appleMusicMatchCmd(action = "-exec du -sk {} +") +
            " | awk '{s+=\$1} END {print s+0}'")

        val (delExit, delOut, delErr) = rootExec(
            appleMusicMatchCmd(action = "-print -delete"),
        )
        if (delExit != 0) {
            // Failed root call — record nothing-freed, return.
            Log.w(tag, "clearAppleMusicOffline: find/delete failed exit=$delExit err=$delErr")
            return record(context, Target.APPLE_MUSIC_OFFLINE, 0L)
        }
        if (delOut.isNotBlank()) {
            // Log a sample so we can spot a path/extension shift in a
            // future Apple Music update before it silently regresses
            // this row to "0 freed" again.
            Log.i(
                tag,
                "clearAppleMusicOffline: deleted files:\n${delOut.take(1024)}" +
                    if (delOut.length > 1024) "\n…(truncated)" else ""
            )
        }
        val freedBytes = beforeKb * 1024L
        return record(context, Target.APPLE_MUSIC_OFFLINE, freedBytes)
    }

    /**
     * Every directory Apple Music for Android is known to drop encrypted
     * audio containers into, across both CE (`/data/data/...`) and DE
     * (`/data/user_de/0/...`) storage trees. Order matters for legibility
     * only — the find command unions them. See [clearAppleMusicOffline]
     * for the rationale on each entry.
     *
     *  - `cache/playback_assets` — confirmed by `apple_music_audit_v2.sh`
     *    on the audit device: 4× `<id>_HQ.m4p` files totalling ~25 MB.
     *    Apple Music's content-addressable cache for both streamed and
     *    user-downloaded tracks.
     *  - `no_backup/assets` — referenced by the v1 audit (where it was
     *    claimed to hold 340 MB). Kept as a fallback; the v2 audit found
     *    it at 222K. Apple may use this on other builds / device classes.
     *  - The same two subpaths under `/data/user_de/0/...` — DE storage
     *    twin, in case Apple Music writes to either side of the encrypt
     *    boundary. Cheap to probe (missing dirs are skipped silently).
     */
    private val APPLE_MUSIC_SEARCH_PATHS = listOf(
        "/data/data/com.apple.android.music/cache/playback_assets",
        "/data/data/com.apple.android.music/no_backup/assets",
        "/data/user_de/0/com.apple.android.music/cache/playback_assets",
        "/data/user_de/0/com.apple.android.music/no_backup/assets",
    )

    /**
     * Builds the shared shell snippet used by both the size-estimate and
     * the delete pass — runs one `find` per path in
     * [APPLE_MUSIC_SEARCH_PATHS] that actually exists, filtered to
     * audio-container extensions Apple Music is known to write.
     *
     * Implementation note: an earlier shape called `find` with all paths
     * as a single multi-arg invocation, but the GNU/toybox find on
     * Android exits non-zero when *any* path argument is missing
     * (stderr is silenced but the exit code isn't). On this device three
     * of the four candidate paths are expected to be absent, so that
     * shape made the caller's `delExit != 0` check fire and record a
     * spurious zero-bytes-freed result. The per-path loop with an
     * existence guard sidesteps that — each find run is independent and
     * a missing dir simply contributes nothing. The trailing `:` no-op
     * inside the brace group pins the snippet's exit code to 0 even
     * when the last `[ -d ]` test fails.
     *
     * Extension list:
     *  - `.m4p` is the Apple FairPlay-protected AAC container — the
     *    format the v2 audit observed on the TCL Flip 6.
     *  - `.m4a` was the v1 audit's assumption, kept defensively in case
     *    Apple Music uses it on other devices or for non-DRM downloads.
     *  - `.aac` / `.mp4` / `.movpkg` / `.fp4` round out the audio
     *    containers seen across Apple Music platforms historically. A
     *    future build switching to one of these won't silently regress
     *    this row to 0.
     *
     * Stderr is discarded per-find so transient permission errors or
     * race conditions (a tmp file Apple Music wrote then immediately
     * unlinked) don't pollute the worker log.
     */
    private fun appleMusicMatchCmd(action: String): String {
        val extPredicate =
            "\\( -iname '*.m4p' -o -iname '*.m4a' -o -iname '*.aac' " +
                "-o -iname '*.mp4' -o -iname '*.movpkg' -o -iname '*.fp4' \\)"
        // Brace group `{ ... ; }` so the caller can pipe the combined
        // stdout of every per-path find into a single `awk` (which the
        // size-estimate path does — `appleMusicMatchCmd("...") + " | awk ..."`).
        // Without the braces, a trailing pipe would attach only to the
        // last command in the semicolon chain and silently drop every
        // earlier find's output. The trailing `:` is a portable no-op
        // that pins the brace group's exit code to 0 even when the
        // last [ -d ] test fails. `: ; }` ends with a semicolon before
        // the closing brace because POSIX sh requires it.
        val sb = StringBuilder("{ ")
        for (path in APPLE_MUSIC_SEARCH_PATHS) {
            sb.append("[ -d $path ] && find $path $extPredicate -type f $action 2>/dev/null; ")
        }
        sb.append(": ; }")
        return sb.toString()
    }

    /**
     * Bit-for-bit equivalent of one nightly
     * [com.offlineinc.dumbdownlauncher.whatsapp.WhatsAppAttachmentCleanupWorker]
     * pass: re-asserts the auto-download prefs, then trims media older
     * than 24h from the three target subdirs (`.Links`, `WhatsApp Images`,
     * `WhatsApp Video`). Recent media is preserved exactly like the cron.
     *
     * The auto-download prefs step is best-effort — it throws
     * [IllegalStateException] when WhatsApp is foregrounded, but the
     * user can only reach this button via the launcher's Free Up Space
     * screen, so WhatsApp is by construction not focused at call time.
     * We still wrap it in try/catch to be defensive against a race where
     * a notification taps the user back into WhatsApp between confirm
     * and execute.
     *
     * Size accounting: we measure the target subdirs' "older than 24h"
     * total BEFORE the delete pass via [whatsAppMediaSizeBytes] so the
     * recorded bytes-freed lines up with what the row showed.
     */
    @JvmStatic
    fun clearWhatsAppOldMedia(context: Context, tag: String = TAG): ClearResult {
        val beforeBytes = whatsAppMediaSizeBytes()
        try {
            WhatsAppOps.applyAutoDownloadMaskZero(context, tag)
        } catch (e: IllegalStateException) {
            Log.i(tag, "clearWhatsAppOldMedia: prefs re-apply deferred — ${e.message}")
            // Keep going — the file deletion path doesn't care about WA's
            // focus state (see WhatsAppOps.clearOldAttachments comments).
        }
        WhatsAppOps.clearOldAttachments(WHATSAPP_CRON_CUTOFF_DAYS, tag)
        return record(context, Target.WHATSAPP_MEDIA, beforeBytes)
    }

    /**
     * Bit-for-bit equivalent of one nightly OpenBubbles attachment
     * cleanup. Wipes everything under
     * [com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesOps.ATTACHMENTS_DIR],
     * preserving only the parent directory so OB doesn't trip on its
     * next attachment write. No age filter — OB attachments are a
     * download cache that the app lazily re-fetches from the iMessage
     * relay when the user scrolls back to a message, so wiping
     * unconditionally is safe and reclaims the full per-night growth.
     *
     * OpenBubblesOps.clearAttachments calls [OpenBubblesOps.stopQuietly],
     * which throws [IllegalStateException] when OB is foregrounded. The
     * user can only reach this button via the Free Up Space screen so
     * that case is structurally impossible, but we catch defensively
     * the same way clearWhatsAppOldMedia does.
     */
    @JvmStatic
    fun clearOpenBubblesAttachments(context: Context, tag: String = TAG): ClearResult {
        val beforeBytes = openBubblesAttachmentsSizeBytes()
        try {
            OpenBubblesOps.clearAttachments(tag = tag)
        } catch (e: IllegalStateException) {
            Log.i(tag, "clearOpenBubblesAttachments: deferred — ${e.message}")
            return ClearResult(bytesFreed = 0L, bytesFreedDisplay = "")
        }
        return record(context, Target.OPENBUBBLES_ATTACHMENTS, beforeBytes)
    }

    // ── Size queries (used by the UI before showing rows) ─────────────────

    /**
     * Returns the size of [path] in bytes via `du -sk`. Zero if the path
     * doesn't exist, isn't a directory, or the call failed. Cheap — `du`
     * stat-walks one subtree, no read I/O.
     *
     * Private because cross-file callers (`FreeUpSpaceScreen`) ran into
     * a Kotlin / build-cache resolution issue against this overload when
     * called as `StorageCleanupOps.directorySizeBytes(literal)` — see
     * [antennaPodSizeBytes] and [spotifyOfflineSizeBytes] for the no-arg
     * wrappers callers should use instead.
     */
    private fun directorySizeBytes(path: String): Long {
        val (_, exists, _) = rootExec("test -d $path && echo y || echo n")
        if (exists != "y") return 0L
        val (_, out, _) = rootExec("du -sk $path 2>/dev/null")
        return out.split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.times(1024L) ?: 0L
    }

    /**
     * Sum of every dir AntennaPod stashes episodes in (see
     * [ANTENNAPOD_CLEAR_DIRS]), in bytes. Missing dirs contribute 0,
     * so this is safe on devices where AntennaPod uses only a subset.
     *
     * Matches the set of dirs [clearAntennaPodEpisodes] will actually
     * delete, so the row's displayed size and the post-clear "freed N MB"
     * figure agree.
     */
    @JvmStatic
    fun antennaPodSizeBytes(): Long =
        ANTENNAPOD_CLEAR_DIRS.sumOf { directorySizeBytes(it) }

    /** Size of Spotify's combined cache + downloads dir in bytes, 0 if missing. */
    @JvmStatic
    fun spotifyOfflineSizeBytes(): Long = directorySizeBytes(SPOTIFY_CACHE_DIR)

    /**
     * Size of Apple Music's downloaded songs in bytes — uses the same
     * `find` predicate [clearAppleMusicOffline] will run, so the row's
     * displayed size matches the bytes the button will actually free.
     * Returns 0 on a missing dir or a failed root call.
     */
    @JvmStatic
    fun appleMusicOfflineSizeBytes(): Long {
        val sizeCmd = appleMusicMatchCmd(action = "-exec du -sk {} +") +
            " | awk '{s+=\$1} END {print s+0}'"
        return duSumKb(sizeCmd) * 1024L
    }

    /**
     * Size in bytes of WhatsApp media that the manual / nightly cleanup
     * is eligible to delete — every file in the three target subdirs
     * (`.Links`, `WhatsApp Images`, `WhatsApp Video`) excluding the
     * `.nomedia` sentinels, optionally filtered by age when
     * [WHATSAPP_CRON_CUTOFF_DAYS] is non-negative.
     *
     * Matches the exact `find` predicate that
     * [WhatsAppOps.clearOldAttachments] runs at the same cutoff, so
     * the number on screen matches the bytes the action will actually
     * remove. Returns 0 cleanly when WhatsApp isn't installed (media
     * root absent) or root isn't available.
     */
    @JvmStatic
    fun whatsAppMediaSizeBytes(): Long {
        val agePredicate =
            if (WHATSAPP_CRON_CUTOFF_DAYS >= 0) "-mtime +$WHATSAPP_CRON_CUTOFF_DAYS " else ""
        val cmd =
            "MEDIA_ROOT=${WhatsAppOps.MEDIA_ROOT}; " +
                "total=0; " +
                "for sub in \".Links\" \"WhatsApp Images\" \"WhatsApp Video\"; do " +
                    "d=\"\$MEDIA_ROOT/\$sub\"; " +
                    "[ -d \"\$d\" ] || continue; " +
                    "s=\$(find \"\$d\" -type f $agePredicate! -name .nomedia " +
                        "-exec du -sk {} + 2>/dev/null | awk '{s+=\$1} END {print s+0}'); " +
                    "total=\$((total + s)); " +
                "done; " +
                "echo \$total"
        return duSumKb(cmd) * 1024L
    }

    /**
     * Size in bytes of OpenBubbles' attachment cache — every subdirectory
     * (and contents) under [OpenBubblesOps.ATTACHMENTS_DIR]. The manual
     * wipe action removes all of this in one pass; same as the nightly
     * worker, which is itself unconditional.
     *
     * Returns 0 when OpenBubbles isn't installed (dir absent) or root
     * isn't available.
     */
    @JvmStatic
    fun openBubblesAttachmentsSizeBytes(): Long =
        directorySizeBytes(OpenBubblesOps.ATTACHMENTS_DIR)

    /**
     * Sum of every app's cache/ and code_cache/ in bytes, **excluding
     * AntennaPod** — its episode cache is surfaced as its own row in
     * [FreeUpSpaceScreen] via [antennaPodSizeBytes], and the matching
     * [trimAppCaches] delete pass also skips it, so this bucket's
     * displayed size matches what the "app caches" button will actually
     * reclaim.
     */
    @JvmStatic
    fun totalAppCachesSizeBytes(): Long =
        duSumKb(appCachesSumCmd(excludePackage = ANTENNAPOD_PKG)) * 1024L

    /**
     * Snapshot returned by [allSizesBytes] — sizes plus per-section
     * timing so a slow load can be tracked down without re-running each
     * section in isolation.
     */
    data class SizesSnapshot(
        val sizesByTarget: Map<Target, Long>,
        val totalElapsedMs: Long,
        val perSectionMs: Map<Target, Long>,
    )

    /**
     * Batched size query for every UI-surfaced [Target]. Collapses what
     * used to be six separate [rootExec] calls (one per `*SizeBytes()`
     * helper) into a single `su` invocation, so the Magisk auth + nsenter
     * startup cost — measured at multiple seconds per call on the target
     * device — is paid once instead of six times.
     *
     * Per-section timing markers (`date +%s%N`) bracket each block; the
     * Kotlin side parses them out into [SizesSnapshot.perSectionMs] and
     * logs the full breakdown so a slow section can be identified
     * without splitting the shell back up.
     *
     * Robustness: malformed lines / missing dirs contribute 0 to that
     * Target's size, not a crash. Targets absent from the output map
     * appear as 0 to callers.
     *
     * Used by [FreeUpSpaceScreen] and [StorageInfoScreen] for the
     * "checking storage…" load. The individual `*SizeBytes()` helpers
     * remain for callers that only need one number (workers, etc.).
     */
    @JvmStatic
    fun allSizesBytes(): SizesSnapshot {
        // Each section emits one stdout line: "<key> <kb> <epoch_ns_after>"
        // The first marker line ("__T0__ <epoch_ns>") establishes t-zero so
        // the first section's duration can be computed too.
        val antennaPodPaths = ANTENNAPOD_CLEAR_DIRS.joinToString(" ")
        val cmd = buildString {
            append("echo \"__T0__ \$(date +%s%N)\"\n")

            // antennapod — du across every dir we'd also clear, summed.
            append("KB=\$(du -sk $antennaPodPaths 2>/dev/null | awk '{s+=\$1} END {print s+0}')\n")
            append("echo \"antennapod \$KB \$(date +%s%N)\"\n")

            // spotify — single combined cache + downloads dir.
            append("KB=\$(du -sk $SPOTIFY_CACHE_DIR 2>/dev/null | awk '{print \$1+0}')\n")
            append("echo \"spotify \$KB \$(date +%s%N)\"\n")

            // apple music — extension-matched find inside no_backup/assets.
            append("KB=\$(${appleMusicMatchCmd("-exec du -sk {} +")} ")
            append("| awk '{s+=\$1} END {print s+0}')\n")
            append("echo \"apple \$KB \$(date +%s%N)\"\n")

            // whatsapp media — same three subdirs and age predicate as
            // whatsAppMediaSizeBytes(), so the row's displayed size
            // matches what the cron / button will actually delete.
            val waAgePredicate =
                if (WHATSAPP_CRON_CUTOFF_DAYS >= 0) "-mtime +$WHATSAPP_CRON_CUTOFF_DAYS " else ""
            append("WA=0\n")
            append("MEDIA_ROOT='${WhatsAppOps.MEDIA_ROOT}'\n")
            append("for sub in \".Links\" \"WhatsApp Images\" \"WhatsApp Video\"; do\n")
            append("  d=\"\$MEDIA_ROOT/\$sub\"\n")
            append("  [ -d \"\$d\" ] || continue\n")
            append("  s=\$(find \"\$d\" -type f $waAgePredicate! -name .nomedia ")
            append("-exec du -sk {} + 2>/dev/null ")
            append("| awk '{s+=\$1} END {print s+0}')\n")
            append("  WA=\$((WA + s))\n")
            append("done\n")
            append("echo \"whatsapp \$WA \$(date +%s%N)\"\n")

            // openbubbles — flat attachments dir, total size (the OB
            // cleanup is unconditional, so the row's displayed size
            // matches whatever du sees).
            append("KB=\$(du -sk ${OpenBubblesOps.ATTACHMENTS_DIR} 2>/dev/null | awk '{print \$1+0}')\n")
            append("echo \"openbubbles \$KB \$(date +%s%N)\"\n")

            // app caches — the loop is reused verbatim from
            // appCachesSumCmd so this row and the actual trim pass agree.
            append("KB=\$(${appCachesSumCmd(excludePackage = ANTENNAPOD_PKG)})\n")
            append("echo \"appcaches \$KB \$(date +%s%N)\"\n")
        }

        val t0Kt = System.currentTimeMillis()
        val (_, out, err) = rootExec(cmd)
        val rootExecMs = System.currentTimeMillis() - t0Kt

        // Parse: each section line is "<key> <kb> <epoch_ns>". The first
        // line is "__T0__ <epoch_ns>" — we use it to size the first
        // section's duration. Subsequent sections use the previous
        // section's timestamp as their baseline.
        val sizes = mutableMapOf<Target, Long>()
        val perSection = mutableMapOf<Target, Long>()
        var prevTsNs: Long? = null

        out.lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@forEach

            if (parts[0] == "__T0__") {
                prevTsNs = parts.getOrNull(1)?.toLongOrNull()
                return@forEach
            }

            // Each non-marker line: key kb endTs
            val key = parts[0]
            val kb = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val endTs = parts.getOrNull(2)?.toLongOrNull()
            val target = keyToTarget(key) ?: return@forEach

            sizes[target] = kb * 1024L

            // Delta in ms from the previous section's end (or T0 for the
            // first one). Guarded against missing/malformed timestamps —
            // if `date +%s%N` isn't supported on this device the timing
            // collapses to 0 rather than crashing the parse.
            val prev = prevTsNs
            if (prev != null && endTs != null && endTs >= prev) {
                perSection[target] = (endTs - prev) / 1_000_000L
            }
            if (endTs != null) prevTsNs = endTs
        }

        // Shell-side total = last timestamp - T0. Falls back to the
        // Kotlin-measured wall time if timestamps were unavailable.
        val shellTotalMs = perSection.values.sum()
        val totalMs = if (shellTotalMs > 0) shellTotalMs else rootExecMs

        Log.i(
            TAG,
            "allSizesBytes: rootExec=${rootExecMs}ms shellSections=${shellTotalMs}ms " +
                "perSection=${perSection.mapKeys { it.key.logKey }} " +
                "sizesMB=${sizes.mapValues { it.value / (1024L * 1024L) }
                    .mapKeys { it.key.logKey }}"
        )
        if (err.isNotBlank()) {
            Log.d(TAG, "allSizesBytes: stderr=${err.take(512)}")
        }
        return SizesSnapshot(sizes, totalMs, perSection)
    }

    /** Maps the shell-side label back to its [Target]. */
    private fun keyToTarget(key: String): Target? = when (key) {
        "antennapod" -> Target.ANTENNAPOD
        "spotify" -> Target.SPOTIFY_OFFLINE
        "apple" -> Target.APPLE_MUSIC_OFFLINE
        "whatsapp" -> Target.WHATSAPP_MEDIA
        "openbubbles" -> Target.OPENBUBBLES_ATTACHMENTS
        "appcaches" -> Target.APP_CACHES
        else -> null
    }

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

    /**
     * Constructs a [ClearResult] for [bytesFreed]. Previously this also
     * persisted a `last_run_at_ms` + `last_bytes_freed` row to the
     * "storage_cleanup" SharedPreferences file; that history is no longer
     * surfaced, so the function is now a pure builder. The unused
     * `context` and `target` params are kept on the signature for
     * caller-side legibility — every cleanup op already has both in
     * scope, and a future change that wants to thread them somewhere
     * (notification, broadcast, scheduled re-check) can do so without
     * touching call sites.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun record(context: Context, target: Target, bytesFreed: Long): ClearResult {
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
