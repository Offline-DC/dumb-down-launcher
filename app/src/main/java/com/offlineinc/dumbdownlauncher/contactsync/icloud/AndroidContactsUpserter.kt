package com.offlineinc.dumbdownlauncher.contactsync.icloud

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

object AndroidContactsUpserter {
    private const val TAG = "AndroidContactsUpserter"

    // Keep these stable forever — must match the standalone contact-sync app
    // so that already-synced contacts are recognized after migration.
    private const val ACCOUNT_TYPE = "com.offlineinc.dumbcontactsync"
    private const val ACCOUNT_NAME = "iPhone"

    fun upsertByRawId(ctx: Context, rawId: Long, card: VCardMini) {
        val ops = ArrayList<ContentProviderOperation>()
        val dataUri = asSyncAdapter(ContactsContract.Data.CONTENT_URI)

        val where =
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND " +
                    "${ContactsContract.Data.MIMETYPE} IN (?,?,?)"
        val whereArgs = arrayOf(
            rawId.toString(),
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        )

        ops += ContentProviderOperation.newDelete(dataUri)
            .withSelection(where, whereArgs)
            .build()

        addNamePhoneEmailOps(
            ops = ops,
            rawContactBackRef = null,
            rawId = rawId,
            card = card,
            dataUri = dataUri
        )

        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    fun upsert(ctx: Context, sourceId: String, card: VCardMini) {
        val existingRawId = findRawContactIdBySourceId(ctx, sourceId)

        if (existingRawId == null) insert(ctx, sourceId, card)
        else update(ctx, existingRawId, card)
    }

    fun deleteMissing(ctx: Context, keepSourceIds: Set<String>): Int {
        val resolver = ctx.contentResolver
        val rawUri = asSyncAdapter(ContactsContract.RawContacts.CONTENT_URI)

        val projection = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.SOURCE_ID
        )

        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=?"
        val args = arrayOf(ACCOUNT_TYPE, ACCOUNT_NAME)

        val toDelete = mutableListOf<Long>()

        resolver.query(rawUri, projection, selection, args, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            val srcIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.SOURCE_ID)

            while (c.moveToNext()) {
                val rawId = c.getLong(idIdx)
                val sourceId = c.getString(srcIdx) ?: continue
                if (!keepSourceIds.contains(sourceId)) {
                    toDelete += rawId
                }
            }
        }

        var deleted = 0
        for (rawId in toDelete) {
            val rows = resolver.delete(
                rawUri,
                "${ContactsContract.RawContacts._ID}=?",
                arrayOf(rawId.toString())
            )
            deleted += rows
        }

        Log.d(TAG, "deleteMissing: keep=${keepSourceIds.size} deletedRawContacts=$deleted")
        return deleted
    }

    fun deleteNativeDuplicates(ctx: Context, incomingCards: List<VCardMini>): Int {
        val resolver = ctx.contentResolver

        val nativeRawIds = mutableSetOf<Long>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}!=? OR ${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL",
            arrayOf(ACCOUNT_TYPE), null
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            while (c.moveToNext()) nativeRawIds += c.getLong(idx)
        }
        if (nativeRawIds.isEmpty()) return 0

        val phoneToRawId = mutableMapOf<String, Long>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            val rawIdIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
            val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val rawId = c.getLong(rawIdIdx)
                if (rawId !in nativeRawIds) continue
                val digits = c.getString(numIdx)?.filter { it.isDigit() } ?: continue
                val key = digits.takeLast(10)
                if (key.length >= 7) phoneToRawId[key] = rawId
            }
        }
        if (phoneToRawId.isEmpty()) return 0

        val toDelete = mutableSetOf<Long>()
        for (card in incomingCards) {
            for (phone in card.phones) {
                val key = phone.filter { it.isDigit() }.takeLast(10)
                if (key.length >= 7) phoneToRawId[key]?.let { toDelete += it }
            }
        }
        if (toDelete.isEmpty()) return 0

        val rawUri = asSyncAdapter(ContactsContract.RawContacts.CONTENT_URI)
        var deleted = 0
        for (rawId in toDelete) {
            val rows = resolver.delete(
                rawUri,
                "${ContactsContract.RawContacts._ID}=?",
                arrayOf(rawId.toString())
            )
            if (rows > 0) {
                Log.d(TAG, "deleteNativeDuplicates: deleted native rawId=$rawId (matched incoming phone)")
                deleted++
            }
        }
        Log.i(TAG, "deleteNativeDuplicates: deleted $deleted native raw contacts that duplicated incoming iPhone contacts")
        return deleted
    }

    fun findByNameAndPhone(ctx: Context, name: String, phones: List<String>): Long? {
        if (name.isBlank()) return null
        val resolver = ctx.contentResolver
        val nameLower = name.trim().lowercase()

        val incomingKeys = phones.mapNotNull { p ->
            val key = p.filter { it.isDigit() }.takeLast(10)
            if (key.length >= 7) key else null
        }.toSet()

        val matchingRawIds = mutableSetOf<Long>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME
            ),
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
            while (c.moveToNext()) {
                val displayName = c.getString(nameIdx)?.trim()?.lowercase() ?: continue
                if (displayName == nameLower) {
                    matchingRawIds += c.getLong(idx)
                }
            }
        }
        if (matchingRawIds.isEmpty()) return null

        if (incomingKeys.isEmpty()) {
            return matchingRawIds.firstOrNull()
        }

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            val rawIdIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
            val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val rawId = c.getLong(rawIdIdx)
                if (rawId !in matchingRawIds) continue
                val digits = c.getString(numIdx)?.filter { it.isDigit() }?.takeLast(10) ?: continue
                if (digits.length >= 7 && digits in incomingKeys) {
                    return rawId
                }
            }
        }
        return null
    }

    fun setSourceId(ctx: Context, rawId: Long, sourceId: String) {
        val ops = arrayListOf(
            ContentProviderOperation.newUpdate(
                asSyncAdapter(ContactsContract.RawContacts.CONTENT_URI)
            )
                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rawId.toString()))
                .withValue(ContactsContract.RawContacts.SOURCE_ID, sourceId)
                .build()
        )
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    fun findRawContactIdBySourceIdPublic(ctx: Context, sourceId: String): Long? =
        findRawContactIdBySourceId(ctx, sourceId)

    private fun findRawContactIdBySourceId(ctx: Context, sourceId: String): Long? {
        val uri = asSyncAdapter(ContactsContract.RawContacts.CONTENT_URI)
        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val selection =
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
                    "${ContactsContract.RawContacts.SOURCE_ID}=?"
        val args = arrayOf(ACCOUNT_TYPE, ACCOUNT_NAME, sourceId)

        ctx.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    private fun insert(ctx: Context, sourceId: String, card: VCardMini) {
        val ops = ArrayList<ContentProviderOperation>()

        val rawUri = asSyncAdapter(ContactsContract.RawContacts.CONTENT_URI)
        val dataUri = asSyncAdapter(ContactsContract.Data.CONTENT_URI)

        ops += ContentProviderOperation.newInsert(rawUri)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
            .withValue(ContactsContract.RawContacts.SOURCE_ID, sourceId)
            .build()

        addNamePhoneEmailOps(
            ops = ops,
            rawContactBackRef = 0,
            rawId = null,
            card = card,
            dataUri = dataUri
        )

        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun update(ctx: Context, rawId: Long, card: VCardMini) {
        val ops = ArrayList<ContentProviderOperation>()
        val dataUri = asSyncAdapter(ContactsContract.Data.CONTENT_URI)

        val where =
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND " +
                    "${ContactsContract.Data.MIMETYPE} IN (?,?,?)"
        val whereArgs = arrayOf(
            rawId.toString(),
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        )

        ops += ContentProviderOperation.newDelete(dataUri)
            .withSelection(where, whereArgs)
            .build()

        addNamePhoneEmailOps(
            ops = ops,
            rawContactBackRef = null,
            rawId = rawId,
            card = card,
            dataUri = dataUri
        )

        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun addNamePhoneEmailOps(
        ops: MutableList<ContentProviderOperation>,
        rawContactBackRef: Int?,
        rawId: Long?,
        card: VCardMini,
        dataUri: Uri
    ) {
        fun newInsertData(): ContentProviderOperation.Builder {
            val b = ContentProviderOperation.newInsert(dataUri)
            if (rawId != null) b.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            else b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactBackRef!!)
            return b
        }

        // Name
        val displayName = card.fn ?: "(No name)"
        ops += newInsertData()
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
            .build()

        // Phones
        card.phones.forEach { phone ->
            ops += newInsertData()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        }

        // Emails
        card.emails.forEach { email ->
            ops += newInsertData()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                .build()
        }
    }

    private fun asSyncAdapter(uri: android.net.Uri): android.net.Uri =
        uri.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build()
}
