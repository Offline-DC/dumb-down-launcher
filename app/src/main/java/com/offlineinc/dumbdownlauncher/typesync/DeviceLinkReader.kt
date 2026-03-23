package com.offlineinc.dumbdownlauncher.typesync

import android.content.Context
import android.util.Log
import com.offlineinc.dumbdownlauncher.pairing.PairingStore

/**
 * Reads device-link pairing data from the launcher's own PairingStore.
 * Previously this read from the contact-sync app's ContentProvider;
 * now the launcher owns pairing state directly.
 */
object DeviceLinkReader {
    private const val TAG = "DeviceLinkReader"

    data class PairingInfo(
        val isPaired: Boolean,
        val sharedSecret: String,
        val flipPhoneNumber: String,
        val pairingId: Int
    )

    fun readPairing(context: Context): PairingInfo? {
        return try {
            val store = PairingStore(context)
            if (store.isPaired && !store.sharedSecret.isNullOrEmpty()) {
                PairingInfo(
                    isPaired = true,
                    sharedSecret = store.sharedSecret!!,
                    flipPhoneNumber = store.flipPhoneNumber ?: "",
                    pairingId = store.pairingId
                )
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
