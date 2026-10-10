package com.mangalens.core.translation.memory

import org.json.JSONArray
import org.json.JSONObject

internal object MemoryLexicalHintCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun encode(index: MemoryLexicalHintIndex): ByteArray = JSONObject().put("version", 1).put("inventory", index.inventory)
        .put("incomplete", index.incomplete).put("rows", JSONArray().apply {
            index.hints.forEach { hint -> put(row(hint)) }
        }).toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }

    fun decode(bytes: ByteArray): MemoryLexicalHintIndex {
        require(bytes.size in 1..MAX_BYTES)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(number(json, "version") == 1L)
        val rows = json.getJSONArray("rows"); require(rows.length() <= MemoryLexicalHintIndex.MAX_HINTS)
        val hints = (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
            val revision = number(row, "editRevision"); require(revision in 0..Int.MAX_VALUE.toLong())
            MemoryLexicalHint(row.getString("chapter"), row.getString("bubble"), MemorySearchKind.valueOf(row.getString("kind")),
                revision.toInt(), if (row.isNull("series")) null else row.getString("series"),
                number(row, "associationRevision"), row.getString("text"))
        } }
        return MemoryLexicalHintIndex(json.getString("inventory"), hints, json.getBoolean("incomplete"))
    }

    fun encodedRowBytes(hint: MemoryLexicalHint): Int = row(hint).toString().toByteArray(Charsets.UTF_8).size
    private fun row(hint: MemoryLexicalHint): JSONObject = JSONObject().put("chapter", hint.chapterId).put("bubble", hint.bubbleId)
        .put("kind", hint.kind.name).put("editRevision", hint.editRevision).put("series", hint.seriesId)
        .put("associationRevision", hint.associationRevision).put("text", hint.normalizedText)

    private fun number(json: JSONObject, key: String): Long {
        val value = json.get(key); require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it >= 0) }
    }
}
