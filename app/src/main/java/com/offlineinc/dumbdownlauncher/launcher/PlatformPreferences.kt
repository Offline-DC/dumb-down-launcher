package com.offlineinc.dumbdownlauncher.launcher

import android.content.Context

object PlatformPreferences {

    private const val PREFS_NAME = "launcher_prefs"
    private const val KEY_PLATFORM_CHOICE = "platform_choice"
    private const val KEY_SHOW_PLATFORM_DIALOG = "show_platform_dialog"
    private const val KEY_LINKING_CHOICE = "linking_choice" // "yes" | "no" | absent = not set

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
}
