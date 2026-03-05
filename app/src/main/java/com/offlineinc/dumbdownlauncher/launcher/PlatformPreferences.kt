package com.offlineinc.dumbdownlauncher.launcher

import android.content.Context

object PlatformPreferences {

    private const val PREFS_NAME = "launcher_prefs"
    private const val KEY_PLATFORM_CHOICE = "platform_choice"
    private const val KEY_SHOW_PLATFORM_DIALOG = "show_platform_dialog"

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

    fun consumeShowDialog(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val flag = prefs.getBoolean(KEY_SHOW_PLATFORM_DIALOG, false)
        if (flag) {
            prefs.edit().putBoolean(KEY_SHOW_PLATFORM_DIALOG, false).apply()
        }
        return flag
    }
}
