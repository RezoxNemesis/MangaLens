package com.mangalens.ui.reader

import com.mangalens.core.translation.memory.MemorySfxPresentation

internal enum class ReaderSfxOriginalReadiness { PREPARING, RESTORED, UNAVAILABLE }

/** Display-only rows. No row is an original-source crop or mutation credential. */
internal data class ReaderSfxNoteRow(val nativeIndex: Int, val presentation: MemorySfxPresentation,
    val translated: String, val annotation: String?, val original: ReaderSfxOriginalReadiness)
internal data class ReaderSfxNoteGroup(val pageIndex: Int, val rows: List<ReaderSfxNoteRow>, val overflow: Int = 0)

internal object ReaderSfxNotePolicy {
    const val MAX_SESSIONS = 16
    const val MAX_VISIBLE_PAGES = 2
    const val MAX_ROWS_PER_PAGE = 4
    const val MAX_TRANSLATION = 512
    const val MAX_ANNOTATION = 256
    private const val MAX_REPORTED_OVERFLOW = 256

    /** Offscreen/prefetched images cannot populate the fixed reading UI. Latest current session wins. */
    fun visible(visiblePages: Set<Int>, groups: List<ReaderSfxNoteGroup>): List<ReaderSfxNoteGroup> {
        val selected = visiblePages.filter { it >= 0 }.take(MAX_VISIBLE_PAGES)
        return selected.mapNotNull { page -> groups.lastOrNull { it.pageIndex == page && it.rows.isNotEmpty() }?.let { group ->
            group.copy(rows = group.rows.take(MAX_ROWS_PER_PAGE).map { row -> row.copy(
                translated = row.translated.take(MAX_TRANSLATION), annotation = row.annotation?.take(MAX_ANNOTATION)) },
                overflow = minOf(MAX_REPORTED_OVERFLOW, group.overflow.coerceIn(0, MAX_REPORTED_OVERFLOW) +
                    (group.rows.size - MAX_ROWS_PER_PAGE).coerceIn(0, MAX_REPORTED_OVERFLOW)))
        } }
    }
}
