package com.offlineinc.dumbdownlauncher.typesync

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Reads device-link pairing data from the contact-sync app's ContentProvider.
 * The contact-sync app owns the pairing and shared secret.
 */
object DeviceLinkReader {
    private const val TAG = "DeviceLinkReader"
    private val CONTENT_URI = Uri.parse("content://com.offlineinc.dumbcontactsync.devicelink/pairing")

    data class PairingInfo(
        val isPaired: Boolean,
        val sharedSecret: String,
        val flipPhoneNumber: String,
        val pairingId: Int
    )

    fun readPairing(context: Context): PairingInfo? {
        return try {
            context.contentResolver.query(CONTENT_URI, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val isPaired = cursor.getInt(cursor.getColumnIndexOrThrow("is_paired")) == 1
                    val secret = cursor.getString(cursor.getColumnIndexOrThrow("shared_secret")).orEmpty()
                    val phone = cursor.getString(cursor.getColumnIndexOrThrow("flip_phone_number")).orEmpty()
                    val pairingId = cursor.getInt(cursor.getColumnIndexOrThrow("pairing_id"))

                    if (isPaired && secret.isNotEmpty()) {
                        Log.d(TAG, "Paired: phone=$phone, pairingId=$pairingId")
                        PairingInfo(isPaired, secret, phone, pairingId)
                    } else {
                        Log.w(TAG, "Not paired or missing secret (isPaired=$isPaired, secretLen=${secret.length})")
                        null
                    }
                } else {
                    Log.w(TAG, "Empty cursor from DeviceLinkProvider")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read pairing from ContentProvider", e)
            null
        }
    }
}
