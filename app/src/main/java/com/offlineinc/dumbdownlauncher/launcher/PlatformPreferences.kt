package com.offlineinc.dumbdownlauncher.launcher

import android.content.Context

object PlatformPreferences {

    private const val PREFS_NAME = "launcher_prefs"
    private const val KEY_PLATFORM_CHOICE = "platform_choice"
    private const val KEY_SHOW_PLATFORM_DIALOG = "show_platform_dialog"
    private const val KEY_LINKING_CHOICE = "linking_choice" // "yes" | "no" | absent = not set
    // Set to true when the user taps "skip setup" on LinkingChoiceScreen.
    // Persistent: survives reboots so the launcher doesn't keep dragging
    // the user through boot_registration on every startup. Cleared when
    // the user re-enters Device Setup via [consumeShowDialog] so a
    // deliberate re-run isn't silently undone.
    private const val KEY_SETUP_SKIPPED = "setup_skipped"

    fun getChoice(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLATFORM_CHOICE, null)
    }

    fun saveChoice(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLATFORM_CHOICE, value)
            .apply()
    }

    fun requestShowDialog(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_PLATFORM_DIALOG, true)
            .apply()
    }

    /** Persist whether the user wants to link their smartphone (true = yes, false = no). */
    fun saveLinkingChoice(context: Context, willLink: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LINKING_CHOICE, if (willLink) "yes" else "no")
            .apply()
    }

    /**
     * Returns the stored linking choice, or null if the user hasn't answered yet.
     * Existing / migrated users who are already paired are treated as having chosen "yes".
     */
    fun getLinkingChoice(context: Context): Boolean? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LINKING_CHOICE, null) ?: return null
        return raw == "yes"
    }

    fun consumeShowDialog(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val flag = prefs.getBoolean(KEY_SHOW_PLATFORM_DIALOG, false)
        if (flag) {
            prefs.edit().putBoolean(KEY_SHOW_PLATFORM_DIALOG, false).apply()
        }
        return flag
    }

    /**
     * True when the user explicitly tapped "skip setup" on the
     * LinkingChoiceScreen. While set, [MainActivity.onCreate] short-
     * circuits the onboarding step machine so the user lands on the
     * home screen on every launch — they'll only see Device Setup again
     * by tapping it from AllAppsActivity.
     */
    fun isSetupSkipped(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_SKIPPED, false)
    }

    fun setSetupSkipped(context: Context, skipped: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_SKIPPED, skipped)
            .apply()
    }

    /**
     * Wipes all setup state (platform choice, linking choice, platform dialog flag).
     * After calling this the next app launch will return the user to the beginning
     * of the onboarding flow.
     */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
