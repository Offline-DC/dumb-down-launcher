package com.offlineinc.dumbdownlauncher.diagnostics

import android.content.Context

/**
 * Runtime opt-in flag for the reboot-logging module. Single boolean:
 * the rolling-logcat tail only runs when this is true, regardless of
 * whether the build was compiled with [com.offlineinc.dumbdownlauncher
 * .BuildConfig.REBOOT_LOGGING_ENABLED].
 *
 * Backed by its own SharedPreferences file so an investigation can be
 * ended on-device by flipping the bool from adb without touching any
 * other launcher state:
 *
 *   adb shell run-as com.offlineinc.dumbdownlauncher \
 *     "sed -i 's/reboot_logging_enabled\">true/reboot_logging_enabled\">false/' \
 *     shared_prefs/reboot_logging_prefs.xml"
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
