package com.offlineinc.dumbdownlauncher.contactsync.icloud

data class VCardMini(
    val uid: String?,
    val fn: String?,
    val phones: List<String>,
    val emails: List<String>
)

object VCardMiniParser {

    fun parse(raw: String): VCardMini {
        // 1) Convert XML entities like &#13; into real line breaks
        val normalized = normalizeXmlEntities(raw)

        // 2) Unfold after normalization (folding uses real newlines)
        val unfolded = unfold(normalized)

        val lines = unfolded
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("PHOTO", ignoreCase = true) }
            .filterNot { it.startsWith("LOGO", ignoreCase = true) }

        var uid: String? = null
        var fn: String? = null
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()

        for (line in lines) {
            val (kRaw, v) = splitKeyValue(line) ?: continue

            // Apple often uses item1.TEL;item1.X-ABLabel etc.
            val baseKey = canonicalVCardKey(kRaw)

            when (baseKey) {
                "UID" -> uid = v.trim()
                "FN" -> fn = v.trim()
                "TEL" -> phones += v.trim()
                "EMAIL" -> emails += v.trim()
            }
        }

        return VCardMini(
            uid = uid,
            fn = fn,
            phones = phones
                .map { normalizePhone(it) }
                .distinct(),
            emails = emails.distinct()
        )
    }

    fun build(fn: String?, phones: List<String>, emails: List<String>, uid: String? = null): String {
        val sb = StringBuilder()
        sb.appendLine("BEGIN:VCARD")
        sb.appendLine("VERSION:3.0")
        sb.appendLine("UID:${uid ?: java.util.UUID.randomUUID()}")
        // N: is required by vCard 3.0 (RFC 2426). Split display name into given/family.
        val displayName = fn?.trim().orEmpty()
        val spaceIdx = displayName.indexOf(' ')
        val (given, family) = if (spaceIdx >= 0)
            displayName.substring(0, spaceIdx) to displayName.substring(spaceIdx + 1)
        else
            displayName to ""
        sb.appendLine("N:${escape(family)};${escape(given)};;;")
        if (displayName.isNotBlank()) sb.appendLine("FN:${escape(displayName)}")
        phones.forEach { sb.appendLine("TEL:${escape(it)}") }
        emails.forEach { sb.appendLine("EMAIL:${escape(it)}") }
        sb.appendLine("END:VCARD")
        return sb.toString()
    }

    private fun canonicalVCardKey(k: String): String {
        // Strip params after ';'
        var key = k.substringBefore(';').trim()

        // Strip Apple "itemN." prefix (case-insensitive)
        val lower = key.lowercase()
        if (lower.startsWith("item")) {
            val dot = key.indexOf('.')
            if (dot >= 0 && dot < key.length - 1) {
                key = key.substring(dot + 1)
            }
        }

        return key.uppercase()
    }

    private fun normalizeXmlEntities(s: String): String {
        return s
            .replace("&#13;", "\n")
            .replace("&#10;", "\n")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    private fun splitKeyValue(line: String): Pair<String, String>? {
        val idx = line.indexOf(':')
        if (idx <= 0) return null
        return line.substring(0, idx) to line.substring(idx + 1)
    }

    // vCard folding: lines starting with space/tab continue previous line
    private fun unfold(s: String): String {
        val out = StringBuilder()
        val lines = s.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (line in lines) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                out.append(line.drop(1))
            } else {
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
            }
        }
        return out.toString()
    }

    private fun normalizePhone(raw: String): String {
        val trimmed = raw.trim()
        val hasPlus = trimmed.startsWith("+")
        val digitsOnly = trimmed.filter { it.isDigit() }
        return if (hasPlus) "+$digitsOnly" else digitsOnly
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
}
