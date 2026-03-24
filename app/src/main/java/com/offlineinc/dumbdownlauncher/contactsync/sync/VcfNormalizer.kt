package com.offlineinc.dumbdownlauncher.contactsync.sync

import com.offlineinc.dumbdownlauncher.contactsync.icloud.AndroidContact
import com.offlineinc.dumbdownlauncher.contactsync.icloud.VCardMiniParser

object VcfNormalizer {
    fun normalize(contacts: List<AndroidContact>): String {
        val sorted = contacts.sortedBy { (it.displayName ?: "").lowercase() }
        val sb = StringBuilder()
        for (contact in sorted) {
            val phones = contact.phones.map { normalizePhone(it) }.distinct().sorted()
            val emails = contact.emails.map { it.lowercase().trim() }.distinct().sorted()
            sb.append(VCardMiniParser.build(
                fn = contact.displayName,
                phones = phones,
                emails = emails
            ))
        }
        return sb.toString()
    }

    private fun normalizePhone(raw: String): String {
        val trimmed = raw.trim()
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (hasPlus) "+$digits" else digits
    }
}
