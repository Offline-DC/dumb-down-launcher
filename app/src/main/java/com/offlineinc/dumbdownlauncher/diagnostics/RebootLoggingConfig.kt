package com.offlineinc.dumbdownlauncher.diagnostics

/**
 * Compile-time knobs for the rolling diagnostic-logging module. See
 * the long-press-on-weather entry point in [AllAppsActivity] for the
 * user-facing on/off switch.
 *
 * Scope is intentionally tight: we keep a rolling logcat tail on disk
 * so when the device randomly reboots (or a call drops, or anything
 * else hard to repro in person), the previous ~24 hours of log lines
 * are still there for the support engineer to harvest. Nothing else —
 * no post-mortem capture, no JSONL, no per-event classification.
 *
 * The 500 MB rolling cap is a hard floor against runaway disk use.
 * In practice the 24-hour time retention is the binding constraint:
 * a chatty Android 11 device produces ~10–15 MB of verbose logcat per
 * hour, gzip rotation gives us ~10× compression, so 24 hours on disk
 * usually lands around 25–40 MB compressed. The cap only matters on a
 * pathologically chatty device (50+ MB/hour) where it bounds the worst
 * case to ~11 % of the TCL Flip 2's ~4.5 GB /data partition.
 *
 * Inherited verbatim from feature/reboot-logging-24h, with one
 * deliberate difference: there is no BuildConfig compile-time gate.
 * The user-facing weather long-press is the only switch — production
 * builds compile the path in but the service stays asleep until the
 * runtime opt-in flips.
 */
internal object RebootLoggingConfig {

    /** Root directory name under filesDir + the /sdcard/Android/data mirror. */
    const val DIAG_DIRNAME = "diag"

    /** Notification channel id used by the foreground service. */
    const val NOTIFICATION_CHANNEL_ID = "dumbdown.diagnostic_logging"
    const val NOTIFICATION_ID = 4712

    /** SharedPreferences file for the user opt-in flag. */
    const val PREFS_FILE = "reboot_logging_prefs"

    // ── Rolling logcat tail ──────────────────────────────────────────────
    //
    // Continuous tail of `su -c logcat -v threadtime *:V` to a size-
    // and time-rotated ring under <filesDir>/diag/rolling-logcat/.
    //
    // 10 MB segments × ~10× gzip ratio land each rotated file around
    // 1 MB on disk. At ~10 MB/hour generation that's roughly one rotate
    // per hour — cheap enough to be background-gzipped, big enough that
    // we're not constantly opening new files. A crash mid-segment loses
    // at most ~one hour of uncompressed lines (still in current.log on
    // disk because we flush each line; the only data we can't get is
    // the bytes that hadn't been flushed by the kernel yet).
    //
    // 500 MB total cap is a safety net. Eviction inside the rolling
    // ring is layered:
    //   1. Time-based: segments older than ROLLING_LOGCAT_RETENTION_HOURS
    //      are deleted on every rotate. This is the normal eviction path.
    //   2. Size-based: if the dir still exceeds ROLLING_LOGCAT_MAX_BYTES,
    //      oldest segments are deleted until it doesn't. Kicks in only on
    //      pathologically chatty devices where a single 24-hour window
    //      exceeds the cap.

    const val ROLLING_LOGCAT_SEGMENT_BYTES: Long = 10L * 1024L * 1024L
    const val ROLLING_LOGCAT_MAX_BYTES: Long = 500L * 1024L * 1024L
    const val ROLLING_LOGCAT_RETENTION_HOURS: Int = 24
    const val ROLLING_LOGCAT_RESPAWN_INITIAL_MS: Long = 5_000L
    const val ROLLING_LOGCAT_RESPAWN_MAX_MS: Long = 5L * 60_000L
}
