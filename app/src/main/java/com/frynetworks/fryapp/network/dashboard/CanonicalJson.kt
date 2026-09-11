package com.frynetworks.fryapp.network.dashboard

import java.math.BigInteger

/**
 * Byte-identical `JSON.stringify` for request bodies the dashboard signs.
 *
 * The dashboard verifies `x-request-signature` over `${METHOD}|${path}|${JSON.stringify(body)}|${ts}`
 * after PARSING the body it received, so the bytes we sign must match what V8 would print for the
 * same value: insertion-ordered keys, no whitespace, integers without a fraction, JS string
 * escaping (only `"`, `\` and control characters are escaped; non-ASCII stays raw).
 *
 * Deliberately narrow: Double/Float are rejected (JS prints `1` where Kotlin prints `1.0`) and
 * integer-like object keys are rejected (V8 orders those numerically before other keys). Everything
 * the dashboard flows send fits this subset; use [jsonBody] to build ordered payloads.
 */
object JsJson {

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is String -> writeString(sb, value)
            is Int, is Long, is Short, is Byte, is BigInteger -> sb.append(value.toString())
            is Float, is Double -> throw IllegalArgumentException(
                "Floating-point values are not canonicalisable (JS and Kotlin format them differently): $value",
            )
            is JsonBody -> writeObject(sb, value.entries)
            is Map<*, *> -> writeObject(sb, value.entries.map { (k, v) -> (k as? String ?: throw IllegalArgumentException("Non-string key: $k")) to v })
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, value.asList())
            else -> throw IllegalArgumentException("Unsupported value type: ${value::class.java.name}")
        }
    }

    private fun writeObject(sb: StringBuilder, entries: Collection<Pair<String, Any?>>) {
        sb.append('{')
        var first = true
        for ((key, v) in entries) {
            if (isArrayIndexKey(key)) {
                throw IllegalArgumentException("Integer-like key \"$key\" would be reordered by V8")
            }
            if (!first) sb.append(',')
            first = false
            writeString(sb, key)
            sb.append(':')
            write(sb, v)
        }
        sb.append('}')
    }

    /** V8 treats canonical array-index strings ("0", "10", but not "01" or "-1") as integer keys. */
    private fun isArrayIndexKey(key: String): Boolean {
        if (key.isEmpty() || key.length > 10) return false
        if (!key.all { it in '0'..'9' }) return false
        if (key.length > 1 && key[0] == '0') return false
        return key.toLong() < 4294967295L
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

/** Insertion-ordered request body; `"key" to value` inside [jsonBody] adds an entry. */
class JsonBody {
    private val map = LinkedHashMap<String, Any?>()

    val entries: Collection<Pair<String, Any?>>
        get() = map.entries.map { Pair(it.key, it.value) }

    infix fun String.to(value: Any?) {
        map[this] = value
    }

    fun put(key: String, value: Any?) {
        map[key] = value
    }

    operator fun get(key: String): Any? = map[key]

    fun toMap(): Map<String, Any?> = LinkedHashMap(map)

    fun canonical(): String = JsJson.stringify(this)
}

fun jsonBody(build: JsonBody.() -> Unit): JsonBody = JsonBody().apply(build)
