package com.offlineinc.dumbdownlauncher

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/**
 * Ensures the built-in "Dumb Line" support contact is present on the device,
 * replacing the manual provisioning command:
 *
 *   adb shell content insert --uri content://com.android.contacts/data \
 *     --bind raw_contact_id:i:$ID --bind mimetype:s:vnd.android.cursor.item/name \
 *     --bind data2:s:Dumb --bind data3:s:Line
 *   adb shell content insert --uri content://com.android.contacts/data \
 *     --bind raw_contact_id:i:$ID --bind mimetype:s:vnd.android.cursor.item/phone_v2 \
 *     --bind data1:s:14047163605 --bind data2:i:2
 *   adb shell content insert --uri content://com.android.contacts/data \
 *     --bind raw_contact_id:i:$ID --bind mimetype:s:vnd.android.cursor.item/email_v2 \
 *     --bind data1:s:support@dumb.co --bind data2:i:1
 *
 * Idempotent: skips cleanly if a raw contact already exists under our
 * ([ACCOUNT_TYPE], [ACCOUNT_NAME], [SOURCE_ID]) triple.
 *
 * Uses its own private ACCOUNT_TYPE so the iPhone contact-sync's
 * [com.offlineinc.dumbdownlauncher.contactsync.icloud.AndroidContactsUpserter.deleteMissing]
 * pass (which scopes by `com.offlineinc.dumbcontactsync`) can never touch this row.
 *
 * Driven from the [DumbDownApp] one-time-migration framework so it runs once,
 * survives boots cheaply, and benefits from the same boot-time retry behaviour
 * the other migrations get.
 */
object DumbLineContactInstaller {
    private const val TAG = "DumbLineContactInstaller"

    // Keep these strings stable forever — they are the lookup key for
    // idempotency. Changing them would re-insert a duplicate contact.
    internal const val ACCOUNT_TYPE = "com.offlineinc.dumbdownlauncher.support"
    internal const val ACCOUNT_NAME = "Dumb"
    internal const val SOURCE_ID = "support-dumb-line-v1"

    private const val DISPLAY_NAME = "Dumb Line"
    private const val GIVEN_NAME = "Dumb"
    private const val FAMILY_NAME = "Line"
    private const val PHONE_NUMBER = "14047163605"
    private const val EMAIL = "support@dumb.co"

    /**
     * Inserts the contact if it isn't already present. Safe to call repeatedly.
     *
     * Returns true if a new row was created on this call, false if it was
     * already there (or insert failed and was logged).
     */
    fun ensureInstalled(ctx: Context): Boolean {
        val existing = findRawContactId(ctx)
        if (existing != null) {
            Log.d(TAG, "Dumb Line contact already present (rawId=$existing) — skipping")
            return false
        }

        return try {
            insert(ctx)
            Log.i(TAG, "Inserted Dumb Line contact ($PHONE_NUMBER / $EMAIL)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to insert Dumb Line contact: ${e.message}", e)
            false
        }
    }

    private fun findRawContactId(ctx: Context): Long? {
        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val selection =
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
                    "${ContactsContract.RawContacts.SOURCE_ID}=?"
        val args = arrayOf(ACCOUNT_TYPE, ACCOUNT_NAME, SOURCE_ID)

        ctx.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    private fun insert(ctx: Context) {
        val ops = ArrayList<ContentProviderOperation>()

        val rawUri = asSyncAdapter(ContactsContract.RawContacts.CONTENT_URI)
        val dataUri = asSyncAdapter(ContactsContract.Data.CONTENT_URI)

        // 1. Raw contact row under our private account.
        ops += ContentProviderOperation.newInsert(rawUri)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
            .withValue(ContactsContract.RawContacts.SOURCE_ID, SOURCE_ID)
            .build()

        // 2. Structured name (mimetype vnd.android.cursor.item/name).
        //    Mirrors the legacy adb command which bound data2=Dumb (given) and
        //    data3=Line (family). Set DISPLAY_NAME explicitly so it shows up
        //    immediately without waiting for the provider's name regeneration.
        ops += ContentProviderOperation.newInsert(dataUri)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
            )
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, DISPLAY_NAME)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, GIVEN_NAME)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, FAMILY_NAME)
            .build()

        // 3. Phone (mimetype vnd.android.cursor.item/phone_v2, TYPE_MOBILE=2).
        ops += ContentProviderOperation.newInsert(dataUri)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            )
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, PHONE_NUMBER)
            .withValue(
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            )
            .build()

        // 4. Email (mimetype vnd.android.cursor.item/email_v2, TYPE_HOME=1).
        ops += ContentProviderOperation.newInsert(dataUri)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
            )
            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, EMAIL)
            .withValue(
                ContactsContract.CommonDataKinds.Email.TYPE,
                ContactsContract.CommonDataKinds.Email.TYPE_HOME
            )
            .build()

        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun asSyncAdapter(uri: Uri): Uri =
        uri.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build()
}
