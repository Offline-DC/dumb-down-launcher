package com.offlineinc.dumbdownlauncher.diagnostics

/**
 * Tiny hand-rolled JSON encoder. We avoid pulling in gson/moshi to keep
 * the diagnostics module zero-dep — every other launcher module already
 * gets away without a JSON library and the schema is closed-set.
 *
 * Only supports the value types the diag schema needs: String, Long,
 * Int, Double, Boolean, null, and nested Map<String, Any?>. Lists are
 * encoded as JSON arrays via [arr].
 */
internal object DiagJson {

    fun obj(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((k, v) in pairs) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(k)).append('"').append(':')
            appendValue(sb, v)
        }
        sb.append('}')
        return sb.toString()
    }

    fun arr(items: Collection<Any?>): String {
        val sb = StringBuilder()
        sb.append('[')
        var first = true
        for (item in items) {
            if (!first) sb.append(',')
            first = false
            appendValue(sb, item)
        }
        sb.append(']')
        return sb.toString()
    }

    private fun appendValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long -> sb.append(v.toString())
            is Float, is Double -> {
                val d = (v as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d)
            }
            is String -> sb.append('"').append(escape(v)).append('"')
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, mv) in v) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"').append(escape(k.toString())).append('"').append(':')
                    appendValue(sb, mv)
                }
                sb.append('}')
            }
            is Collection<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, item)
                }
                sb.append(']')
            }
            else -> sb.append('"').append(escape(v.toString())).append('"')
        }
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 2)
        for (ch in s) {
            val code = ch.code
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '"'  -> sb.append("\\\"")
                code == 0x08 -> sb.append("\\b")   // backspace
                code == 0x0C -> sb.append("\\f")   // form feed
                code == 0x0A -> sb.append("\\n")   // newline
                code == 0x0D -> sb.append("\\r")   // carriage return
                code == 0x09 -> sb.append("\\t")   // tab
                code < 0x20 -> sb.append("\\u").append(String.format("%04x", code))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
