package com.offlineinc.dumbdownlauncher.pairing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * ContentProvider that exposes device-link pairing state to the contact-sync app.
 * The launcher now owns pairing — contacts-sync reads the shared secret from here.
 *
 * Authority: com.offlineinc.dumbdownlauncher.devicelink
 * Path: /pairing
 *
 * Returns columns: is_paired, shared_secret, flip_phone_number, pairing_id
 *
 * Protected by signature-level permission so only apps signed with the same
 * key can read the secret.
 */
class DeviceLinkProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.offlineinc.dumbdownlauncher.devicelink"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/pairing")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf())
        val store = PairingStore(ctx)

        val columns = arrayOf("is_paired", "shared_secret", "flip_phone_number", "pairing_id")
        val cursor = MatrixCursor(columns)
        cursor.addRow(arrayOf<Any>(
            if (store.isPaired) 1 else 0,
            store.sharedSecret ?: "",
            store.flipPhoneNumber ?: "",
            store.pairingId
        ))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/devicelink"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
