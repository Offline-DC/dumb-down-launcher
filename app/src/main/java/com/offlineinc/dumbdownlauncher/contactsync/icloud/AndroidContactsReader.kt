package com.offlineinc.dumbdownlauncher.contactsync.icloud

import android.content.Context
import android.provider.ContactsContract

data class AndroidContact(
    val id: String,
    val displayName: String?,
    val phones: List<String>,
    val emails: List<String>
) {
    fun contentHash(): String =
        (displayName.orEmpty() + phones.sorted().joinToString() + emails.sorted().joinToString())
            .hashCode().toString()
}

object AndroidContactsReader {

    // Keep this stable — it must match the ACCOUNT_TYPE used by the standalone
    // contact-sync app so that already-synced contacts are recognized.
    private const val SYNC_ACCOUNT_TYPE = "com.offlineinc.dumbcontactsync"

    fun readNativeContacts(ctx: Context): List<AndroidContact> {
        val contactsWithNativeRaw = readContactIdsWithNativeRaw(ctx)
        val resolver = ctx.contentResolver

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )

        val contacts = mutableListOf<AndroidContact>()

        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx).toString()
                if (!contactsWithNativeRaw.contains(id)) continue

                val name = cursor.getString(nameIdx)
                val phones = readPhonesForContact(ctx, id)
                val emails = readEmailsForContact(ctx, id)

                contacts += AndroidContact(
                    id = id,
                    displayName = name,
                    phones = phones,
                    emails = emails
                )
            }
        }

        return contacts
    }

    private fun readContactIdsWithNativeRaw(ctx: Context): Set<String> {
        val resolver = ctx.contentResolver
        val projection = arrayOf(ContactsContract.RawContacts.CONTACT_ID)
        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE}!=? OR ${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL"
        val args = arrayOf(SYNC_ACCOUNT_TYPE)

        val ids = mutableSetOf<String>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            selection, args, null
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
            while (c.moveToNext()) {
                ids += c.getLong(idx).toString()
            }
        }
        return ids
    }

    private fun readPhonesForContact(ctx: Context, contactId: String): List<String> {
        val phones = mutableListOf<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
            arrayOf(contactId), null
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val num = c.getString(idx)?.trim()
                if (!num.isNullOrBlank()) phones += num
            }
        }
        return phones.distinct()
    }

    private fun readEmailsForContact(ctx: Context, contactId: String): List<String> {
        val emails = mutableListOf<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?",
            arrayOf(contactId), null
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (c.moveToNext()) {
                val addr = c.getString(idx)?.trim()
                if (!addr.isNullOrBlank()) emails += addr
            }
        }
        return emails.distinct()
    }
}
