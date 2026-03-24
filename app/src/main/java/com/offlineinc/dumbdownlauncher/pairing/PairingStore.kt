package com.offlineinc.dumbdownlauncher.pairing

import android.content.Context

/**
 * Stores device-link pairing credentials in SharedPreferences.
 * The launcher owns pairing state — contacts-sync reads it via ContentProvider.
 */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("device_pairing", Context.MODE_PRIVATE)

    var isPaired: Boolean
        get() = prefs.getBoolean("is_paired", false)
        set(v) = prefs.edit().putBoolean("is_paired", v).apply()

    var sharedSecret: String?
        get() = prefs.getString("shared_secret", null)
        set(v) = prefs.edit().putString("shared_secret", v).apply()

    var pairingId: Int
        get() = prefs.getInt("pairing_id", 0)
        set(v) = prefs.edit().putInt("pairing_id", v).apply()

    var flipPhoneNumber: String?
        get() = prefs.getString("flip_phone_number", null)
        set(v) = prefs.edit().putString("flip_phone_number", v).apply()

    var lastReportedVersion: String?
        get() = prefs.getString("last_reported_version", null)
        set(v) = prefs.edit().putString("last_reported_version", v).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }
}
