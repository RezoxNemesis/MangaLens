package com.mangalens.core.translation.memory

import java.util.Collections

/** A cache row selects a journal field to inspect; it has no source/native/open authority. */
internal data class MemoryLexicalHint(val chapterId: String, val bubbleId: String, val kind: MemorySearchKind,
    val editRevision: Int, val seriesId: String?, val associationRevision: Long, val normalizedText: String) {
    val id: String get() = memoryHash(listOf(chapterId, bubbleId, kind.name, editRevision.toString(),
        seriesId.orEmpty(), associationRevision.toString(), normalizedText))
    fun validate() {
        require(chapterId.matches(Regex("[a-f0-9]{32}")) && memoryValidHash(bubbleId))
        require(editRevision >= 0 && associationRevision >= 0 && (seriesId == null || memoryValidId(seriesId)))
        memoryValidateText(normalizedText)
        require(normalizedText == memoryTextKey(normalizedText))
    }
}

internal data class MemoryLexicalHintBatch(val query: String, val hints: List<MemoryLexicalHint>, val incomplete: Boolean)

/** Explicit bounded lexical search. All matching text must be re-derived from current journals. */
internal class MemoryLexicalHintIndex(val inventory: String, hints: List<MemoryLexicalHint>, val incomplete: Boolean) {
    val hints: List<MemoryLexicalHint> = Collections.unmodifiableList(hints.toList())
    init {
        require(memoryValidHash(inventory) && hints.size <= MAX_HINTS)
        hints.forEach { it.validate() }
        require(hints.map { it.id }.distinct().size == hints.size)
    }

    fun find(query: String, limit: Int = 32, kinds: Set<MemorySearchKind>? = null): MemoryLexicalHintBatch {
        val text = query.trim()
        require(text.isNotBlank() && text.length <= 256 && '\u0000' !in text && limit in 1..32)
        val matching = hints.asSequence().filter { (kinds == null || it.kind in kinds) && SeriesMemoryQuery.matches(it.normalizedText, text) }.take(limit + 1).toList()
        return MemoryLexicalHintBatch(text, Collections.unmodifiableList(matching.take(limit)), incomplete || matching.size > limit)
    }

    companion object { const val MAX_HINTS = 8192 }
}
