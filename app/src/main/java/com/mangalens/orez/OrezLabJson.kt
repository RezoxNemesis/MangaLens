package com.mangalens.orez

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Strict grammar/depth/key uniqueness before Android's permissive JSON coercion parser. */
internal object OrezLabJson {
    fun objectFrom(raw: ByteArray, check: () -> Unit = {}): JSONObject {
        if (raw.size > OrezModelEvaluationValidator.MAX_BODY_BYTES) throw IOException("Lab JSON byte bound")
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
        catch (error: java.nio.charset.CharacterCodingException) { throw IOException("Lab JSON UTF-8", error) }
        return try { Parser(text, check).read(); JSONObject(text) }
        catch (error: org.json.JSONException) { throw IOException("Malformed lab JSON", error) }
    }
    /** Matches Python ensure_ascii=False/sort_keys/compact separators; no platform slash escaping. */
    fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> quote(value)
        is Int, is Long -> value.toString()
        else -> throw IOException("Unsupported canonical lab JSON value")
    }
    private fun quote(value: String): String = buildString {
        append('"'); var position = 0
        while (position < value.length) {
            val character = value[position++]
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> when {
                    character < ' ' -> { append("\\u"); append(character.code.toString(16).padStart(4, '0')) }
                    Character.isHighSurrogate(character) -> {
                        if (position >= value.length || !Character.isLowSurrogate(value[position])) throw IOException("Unpaired lab JSON surrogate")
                        append(character); append(value[position++])
                    }
                    Character.isLowSurrogate(character) -> throw IOException("Unpaired lab JSON surrogate")
                    else -> append(character)
                }
            }
        }
        append('"')
    }
    fun comparePaths(left: String, right: String): Int {
        var a = 0; var b = 0
        while (a < left.length && b < right.length) {
            val x = Character.codePointAt(left, a); val y = Character.codePointAt(right, b)
            if (x != y) return x.compareTo(y)
            a += Character.charCount(x); b += Character.charCount(y)
        }
        return (left.length - a).compareTo(right.length - b)
    }
    private class Parser(private val text: String, private val check: () -> Unit) {
        private var cursor = 0
        private fun whitespace() { while (cursor < text.length && text[cursor] in " \t\r\n") cursor++ }
        private fun take(value: Char) { whitespace(); if (cursor >= text.length || text[cursor++] != value) throw IOException("Malformed lab JSON") }
        private fun string(): String {
            whitespace(); val begin = cursor; take('"')
            while (cursor < text.length) {
                if (cursor % 4096 == 0) check()
                val value = text[cursor++]
                if (value == '"') return JSONTokener(text.substring(begin, cursor)).nextValue() as? String ?: throw IOException("Lab JSON key")
                if (value < ' ') throw IOException("Lab JSON control character")
                if (value == '\\') {
                    if (cursor >= text.length) throw IOException("Lab JSON escape")
                    when (text[cursor++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> { repeat(4) { if (cursor >= text.length || text[cursor++].digitToIntOrNull(16) == null) throw IOException("Lab JSON Unicode escape") } }
                        else -> throw IOException("Lab JSON escape")
                    }
                }
            }
            throw IOException("Unterminated lab JSON string")
        }
        private fun value(depth: Int) {
            check(); whitespace(); if (depth > 16 || cursor >= text.length) throw IOException("Lab JSON depth/incomplete value")
            when (text[cursor]) {
                '{' -> {
                    cursor++; val keys = HashSet<String>(); whitespace()
                    if (cursor < text.length && text[cursor] == '}') { cursor++; return }
                    while (true) {
                        val key = string(); if (!keys.add(key) || keys.size > 8192) throw IOException("Duplicate/oversized lab JSON keys")
                        take(':'); value(depth + 1); whitespace()
                        if (cursor < text.length && text[cursor] == '}') { cursor++; return }
                        take(',')
                    }
                }
                '[' -> {
                    cursor++; whitespace(); var count = 0
                    if (cursor < text.length && text[cursor] == ']') { cursor++; return }
                    while (true) {
                        if (++count > 8192) throw IOException("Lab JSON array bound")
                        value(depth + 1); whitespace()
                        if (cursor < text.length && text[cursor] == ']') { cursor++; return }
                        take(',')
                    }
                }
                '"' -> { string(); return }
                't', 'f', 'n' -> {
                    val token = when (text[cursor]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    if (!text.startsWith(token, cursor)) throw IOException("Malformed lab JSON literal")
                    cursor += token.length
                }
                else -> {
                    val start = cursor
                    while (cursor < text.length && text[cursor] in "0123456789-+.eE") cursor++
                    val number = text.substring(start, cursor)
                    if (number.length !in 1..64 || !number.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) throw IOException("Malformed lab JSON number")
                }
            }
        }
        fun read() { value(1); whitespace(); check(); if (cursor != text.length || text.trimStart().firstOrNull() != '{') throw IOException("Lab JSON requires one exact object") }
    }
}
