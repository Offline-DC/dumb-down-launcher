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

    /**
     * Stripe product IDs associated with this phone line's subscription.
     * Stored as a comma-separated string; null if not yet fetched or not found.
     */
    var stripeProductIds: List<String>?
        get() {
            val raw = prefs.getString("stripe_product_ids", null) ?: return null
            return if (raw.isEmpty()) emptyList() else raw.split(",")
        }
        set(v) = prefs.edit().putString("stripe_product_ids", v?.joinToString(",")).apply()

    /**
     * Write all pairing credentials in a single atomic commit so downstream
     * readers never see a partial state (e.g. isPaired=true but secret empty).
     */
    fun savePairing(phoneNumber: String, secret: String, pairingId: Int) {
        prefs.edit()
            .putString("flip_phone_number", phoneNumber)
            .putString("shared_secret", secret)
            .putInt("pairing_id", pairingId)
            .putBoolean("is_paired", true)
            .commit()   // synchronous — guarantees data is persisted before returning
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
