package com.offlineinc.dumbdownlauncher.contactsync.sync

import android.content.Context

class ContactSyncStore(context: Context) {
    private val prefs = context.getSharedPreferences("contact_sync", Context.MODE_PRIVATE)

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

    var lastSmartHash: String?
        get() = prefs.getString("last_smart_hash", null)
        set(v) = prefs.edit().putString("last_smart_hash", v).apply()

    var lastFlipHash: String?
        get() = prefs.getString("last_flip_hash", null)
        set(v) = prefs.edit().putString("last_flip_hash", v).apply()

    var lastSyncMillis: Long
        get() = prefs.getLong("last_sync_millis", 0L)
        set(v) = prefs.edit().putLong("last_sync_millis", v).apply()

    var smartContactCount: Int
        get() = prefs.getInt("smart_contact_count", 0)
        set(v) = prefs.edit().putInt("smart_contact_count", v).apply()

    var flipContactCount: Int
        get() = prefs.getInt("flip_contact_count", 0)
        set(v) = prefs.edit().putInt("flip_contact_count", v).apply()

    // ── Per-contact hashes ────────────────────────────────────────────────────
    // Keyed by "ch:<sourceId>" — lets us skip upsert for unchanged contacts
    // even when the server reports hasChanges=true for the overall batch.

    fun getContactHash(sourceId: String): String? =
        prefs.getString("ch:$sourceId", null)

    fun setContactHash(sourceId: String, hash: String) =
        prefs.edit().putString("ch:$sourceId", hash).apply()

    fun clearContactHashesNotIn(keepSourceIds: Set<String>) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("ch:") && it.removePrefix("ch:") !in keepSourceIds }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
