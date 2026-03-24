package com.offlineinc.dumbdownlauncher.contactsync.sync

import android.content.Context
import android.util.Log
import com.offlineinc.dumbdownlauncher.pairing.PairingStore

/**
 * Reads device-link pairing data directly from the launcher's PairingStore.
 *
 * Previously (when contact-sync was a separate app) this read from a
 * ContentProvider. Now that contact-sync is integrated into the launcher,
 * we read directly from PairingStore — no IPC needed.
 */
object DeviceLinkReader {
    private const val TAG = "DeviceLinkReader"

    data class PairingInfo(
        val isPaired: Boolean,
        val sharedSecret: String,
        val flipPhoneNumber: String,
        val pairingId: Int
    )

    /**
     * Reads pairing data from the launcher's PairingStore and caches it
     * into the local ContactSyncStore. Returns null if not paired.
     */
    fun readAndCache(context: Context): PairingInfo? {
        val info = readFromPairingStore(context) ?: return null
        if (!info.isPaired || info.sharedSecret.isEmpty()) return null

        // Cache into local store so sync logic can use it
        val store = ContactSyncStore(context)

        // If the pairing changed (unpair + re-pair), clear old sync state
        // so we don't try to auth with a stale shared secret or skip contacts
        // that were hashed under the old pairing.
        val oldPairingId = store.pairingId
        if (oldPairingId != 0 && oldPairingId != info.pairingId) {
            Log.i(TAG, "Pairing changed ($oldPairingId -> ${info.pairingId}) — clearing old sync data")
            store.clear()
        }

        store.isPaired = info.isPaired
        store.sharedSecret = info.sharedSecret
        store.flipPhoneNumber = info.flipPhoneNumber
        store.pairingId = info.pairingId
        Log.i(TAG, "Cached pairing data: pairingId=${info.pairingId}")

        return info
    }

    /**
     * Reads pairing data directly from the launcher's PairingStore.
     */
    fun readFromPairingStore(context: Context): PairingInfo? {
        return try {
            val pairingStore = PairingStore(context)

            val isPaired = pairingStore.isPaired
            val secret = pairingStore.sharedSecret ?: ""
            val phone = pairingStore.flipPhoneNumber ?: ""
            val pairingId = pairingStore.pairingId

            if (isPaired && secret.isNotEmpty()) {
                PairingInfo(isPaired, secret, phone, pairingId)
            } else {
                Log.w(TAG, "Not paired or missing secret")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read pairing from PairingStore", e)
            null
        }
    }
}
