package com.mangalens.core.translation

import com.mangalens.core.translation.memory.MemorySearchKind
import com.mangalens.core.translation.memory.SeriesMemoryQuery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Selectors reference actual bounded native metadata. They confer no read/editor authority. */
internal data class NativeLexicalHint(val task: ChapterTranslationTask, val pageIndex: Int,
    val letteringIndex: Int, val kind: MemorySearchKind, val text: String)
internal data class NativeLexicalHintBatch(val hints: List<NativeLexicalHint>, val incomplete: Boolean)

/** Uses the existing native journal owner/decoder; never manufactures a personal memory record. */
internal class NativeMetadataLexicalIndex(private val native: ChapterTranslationStore) {
    suspend fun find(query: String, limit: Int = 32): NativeLexicalHintBatch {
        require(query.isNotBlank() && query.length <= 256 && '\u0000' !in query && limit in 1..32)
        val hits = arrayListOf<NativeLexicalHint>()
        // Store persistence caps the entire loaded inventory at 64 tasks / 32,000,000 journal bytes.
        // Normalization is field-at-a-time (native's 8,000-character field bound), not an extra body cache.
        for (task in native.nativeIndexTasks()) for (page in task.pages) {
            if (page.status !in setOf(ChapterTranslationPageStatus.COMPLETED, ChapterTranslationPageStatus.PARTIAL)) continue
            for ((ordinal, letter) in page.lettering.withIndex()) {
                currentCoroutineContext().ensureActive()
                for ((kind, text) in listOf(MemorySearchKind.OCR to letter.source, MemorySearchKind.TRANSLATION to letter.translated)) {
                    if (text.isBlank() || !SeriesMemoryQuery.matches(text, query)) continue
                    if (hits.size == limit) return NativeLexicalHintBatch(hits.toList(), true)
                    hits += NativeLexicalHint(task, page.index, ordinal, kind, text)
                }
            }
        }
        return NativeLexicalHintBatch(hits.toList(), false)
    }
}
