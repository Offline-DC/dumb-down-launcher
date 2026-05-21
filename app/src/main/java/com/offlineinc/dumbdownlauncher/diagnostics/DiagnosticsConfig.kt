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

    // ── Lid sensor (hall-effect, gpio_keys driver) ───────────────────────
    //
    // The TCL 4058W flip exposes the hall sensor as a raw kernel input
    // event under /dev/input/event3 with a vendor-defined keycode the
    // kernel hasn't given a symbolic name to (so `getevent -l` prints it
    // as the literal hex string "00fc"). It is NOT exposed via
    // SensorManager and it does NOT come through as Android's SW_LID
    // input switch — both were confirmed empty in dumpsys on this
    // hardware. LidSensorReader spawns a long-lived `su -c getevent -lt
    // <path>` subprocess and parses each EV_KEY line out of stdout.

    /** Kernel input device the hall sensor is wired to on TCL 4058W. */
    const val LID_INPUT_DEVICE_PATH: String = "/dev/input/event3"

    /**
     * Hex keycode (as printed by `getevent -l`) that the hall sensor
     * fires. The kernel has no symbolic name for it on this device, so
     * getevent prints it as the raw 4-char hex string. If you port to
     * other flip hardware this is the value most likely to change.
     */
    const val LID_KEYCODE_HEX: String = "00fc"

    /**
     * Polarity of the keycode: when true, EV_KEY <code> DOWN means the
     * lid is CLOSED (magnet engaged → hall sensor latches → kernel
     * reports key-down). Flip to false if a direction test on this
     * hardware shows the opposite convention. The default matches the
     * most common flip-phone wiring; verify with `getevent -lt
     * /dev/input/event3` before trusting a long capture.
     */
    const val LID_KEY_DOWN_MEANS_CLOSED: Boolean = true

    /**
     * If two lid events arrive within this window we tag the second as a
     * `lid_bounce` in events.jsonl. Hypothesis #2 in the plan is hall
     * sensor chatter — a bounce count well above the control cohort's
     * median is the smoking gun. 500 ms is generous enough to catch the
     * obvious physical bounce but tight enough that intentional flips
     * are not flagged.
     */
    const val LID_BOUNCE_WINDOW_MS: Long = 500L

    /**
     * The reader subprocess may die for any number of reasons (Magisk
     * policy change, OOM kill, kernel panic). When stdout closes we wait
     * this long and then respawn it, doubling each retry up to the max
     * so a permanently broken root path doesn't spin a tight loop.
     */
    const val LID_READER_RESPAWN_INITIAL_MS: Long = 5_000L
    const val LID_READER_RESPAWN_MAX_MS: Long = 5L * 60_000L
}
