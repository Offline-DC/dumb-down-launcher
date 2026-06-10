package com.offlineinc.dumbdownlauncher.diagnostics

import android.content.Context

/**
 * Runtime opt-in flag for the diagnostic-logging module. Single boolean:
 * the rolling-logcat tail only runs when this is true.
 *
 * Flipped from the diagnostics screen opened by long-pressing "quack" in All Apps —
 * mirroring the long-press-on-updates beta-tester toggle. Also writable
 * directly from adb without UI, mainly for support escalations:
 *
 *   adb shell run-as com.offlineinc.dumbdownlauncher \
 *     "sed -i 's/reboot_logging_enabled\">true/reboot_logging_enabled\">false/' \
 *     shared_prefs/reboot_logging_prefs.xml"
 *
 * The "_since_ms" companion timestamp is set whenever the flag flips on
 * and surfaced in the foreground-service notification so the user can
 * see how long collection has been running.
 */
internal class RebootLoggingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(RebootLoggingConfig.PREFS_FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var enabledSinceMs: Long
        get() = prefs.getLong(KEY_ENABLED_SINCE_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_ENABLED_SINCE_MS, value).apply()

    private companion object {
        const val KEY_ENABLED = "reboot_logging_enabled"
        const val KEY_ENABLED_SINCE_MS = "reboot_logging_enabled_since_ms"
    }
}
