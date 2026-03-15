package com.offlineinc.dumbdownlauncher.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Reads TCL Flip 2 key shortcuts from the system settings content provider
 * and launches the assigned app for each D-pad direction.
 *
 * TCL stores shortcuts under these keys:
 *   keyshortcut_upkey    = "Label-package-activity"
 *   keyshortcut_downkey  = "Label-package-activity"
 *   keyshortcut_leftkey  = "Label-package-activity"
 *   keyshortcut_rightkey = "Label-package-activity"
 *
 * Value format: "{AppLabel}-{packageName}-{fully.qualified.ActivityName}"
 * Example: "Settings-com.android.settings-com.android.settings.Settings"
 *
 * NOTE: On TCL Flip 2, `Settings.System.getString()` does NOT return these
 * custom keys. We must use a direct ContentResolver query on
 * `content://settings/system` instead.
 */
object DpadShortcutResolver {

    private const val TAG = "DpadShortcut"
    private val SYSTEM_SETTINGS_URI: Uri = Uri.parse("content://settings/system")

    enum class Direction(val settingsKey: String) {
        UP("keyshortcut_upkey"),
        DOWN("keyshortcut_downkey"),
        LEFT("keyshortcut_leftkey"),
        RIGHT("keyshortcut_rightkey"),
    }

    /**
     * Reads a setting value directly from the system settings content provider.
     * The Android Settings provider expects the key appended to the URI path:
     *   content://settings/system/{keyName}
     *
     * This works for TCL-specific keys that `Settings.System.getString()`
     * cannot find.
     */
    private fun readSystemSetting(context: Context, key: String): String? {
        return try {
            val uri = Uri.withAppendedPath(SYSTEM_SETTINGS_URI, key)
            context.contentResolver.query(
                uri,
                arrayOf("value"),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver query failed for $key: ${e.message}")
            null
        }
    }

    /**
     * Reads the TCL shortcut setting for the given [direction].
     * Returns a parsed [ShortcutInfo] or null if nothing is configured.
     */
    fun resolve(context: Context, direction: Direction): ShortcutInfo? {
        val raw = readSystemSetting(context, direction.settingsKey)

        if (raw.isNullOrBlank()) {
            Log.d(TAG, "${direction.settingsKey} not set")
            return null
        }

        Log.d(TAG, "${direction.settingsKey} = $raw")
        return parse(raw)
    }

    /**
     * Builds a launch [Intent] for the given [direction], or null if
     * no shortcut is configured or the value couldn't be parsed.
     */
    fun buildLaunchIntent(context: Context, direction: Direction): Intent? {
        val info = resolve(context, direction) ?: return null

        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(info.packageName, info.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Parse the TCL shortcut value string.
     *
     * Format: "Label-package.name-fully.qualified.Activity"
     *
     * Since labels can't contain dots but package/activity names do,
     * we split on "-" and reassemble. The first segment is the label,
     * the second is the package, the third is the activity.
     */
    internal fun parse(raw: String): ShortcutInfo? {
        // Split into exactly 3 parts: label, package, activity
        val parts = raw.split("-")
        if (parts.size < 3) {
            Log.w(TAG, "Unexpected shortcut format: $raw")
            return null
        }

        val label = parts[0]
        val packageName = parts[1]
        // Activity might contain dashes (unlikely but safe)
        val activityName = parts.drop(2).joinToString("-")

        if (packageName.isBlank() || activityName.isBlank()) {
            Log.w(TAG, "Empty package or activity in: $raw")
            return null
        }

        return ShortcutInfo(label, packageName, activityName)
    }

    data class ShortcutInfo(
        val label: String,
        val packageName: String,
        val activityName: String,
    )
}
