package com.offlineinc.dumbdownlauncher.quack

import android.content.Context
import java.util.UUID

/**
 * Generates and persists a random UUID for this install.
 * No account, no email — just a stable anonymous identity.
 * Reset by clearing app data.
 */
object QuackDeviceId {

    private const val PREFS = "quack_prefs"
    private const val KEY = "device_id"

    private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY, it).apply()
        }
        cached = id
        return id
    }
}
