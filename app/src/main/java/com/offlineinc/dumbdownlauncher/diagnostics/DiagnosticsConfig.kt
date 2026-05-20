package com.offlineinc.dumbdownlauncher.diagnostics

/**
 * Compile-time constants for the battery diagnostics module. See
 * battery-diagnostics-plan.md for the full investigation plan that this
 * module implements.
 *
 * All knobs live here so the on-device sampling cadence and retention
 * budget can be tuned without hunting through the service code. None of
 * these values are user-visible — the hidden DiagnosticsActivity exposes
 * only the opt-in toggle, session id, and pull instructions.
 */
internal object DiagnosticsConfig {

    /** Per-minute battery sample cadence. Cheap; cost is dominated by IO not sampling. */
    const val BATTERY_SAMPLE_INTERVAL_MS: Long = 60_000L

    /** Privileged dumpsys snapshot cadence. Hourly + on every screen-on/off. */
    const val DUMPSYS_SNAPSHOT_INTERVAL_MS: Long = 60 * 60_000L

    /** Days of rolling history retained on device. 14 = the spec from the plan. */
    const val RETENTION_DAYS: Int = 14

    /** Cap on the total diagnostics folder size on internal storage (bytes). */
    const val MAX_DIAG_BYTES: Long = 50L * 1024L * 1024L

    /** Notification channel id used by the foreground service. */
    const val NOTIFICATION_CHANNEL_ID = "dumbdown.diagnostics"
    const val NOTIFICATION_ID = 4711

    /** SharedPreferences file for diagnostics opt-in / session state. */
    const val PREFS_FILE = "diagnostics_prefs"

    /** Diagnostics folder name under filesDir + the /sdcard/Android/data mirror. */
    const val DIAG_DIRNAME = "diag"

    /** Shell timeout for the longest dumpsys/bugreport-style calls. */
    const val SHELL_TIMEOUT_MS: Long = 20_000L

    /** Per-line cap when reading `logcat -d` so a runaway log doesn't fill the disk. */
    const val LOGCAT_TAIL_LINES: Int = 10_000

    /** Schema version stamped into every JSONL line for forward compatibility. */
    const val SCHEMA_VERSION: Int = 1
}
