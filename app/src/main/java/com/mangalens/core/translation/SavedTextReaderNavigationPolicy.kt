package com.mangalens.core.translation

import com.mangalens.core.reader.SavedChapter

internal data class SavedTextReaderPreferences(val target: String, val style: String, val custom: String)

/** Final Main metadata admission; real source/output/memory evidence is held by the prepared result. */
internal object SavedTextReaderNavigationPolicy {
    fun chapterFor(selection: SavedTextReaderSelection, library: List<SavedChapter>, visitedAt: Long): SavedChapter? {
        val current = library.singleOrNull { it.id == selection.chapterId } ?: return null
        val expected = selection.chapter
        if (current.sourceUrl != expected.sourceUrl || current.pages.map { Triple(it.index, it.sourceUrl, it.localPath) } !=
            expected.pages.map { Triple(it.index, it.sourceUrl, it.localPath) } ||
            current.pages.getOrNull(selection.pageOrdinal)?.index != selection.pageIndex ||
            current.pages.getOrNull(selection.pageOrdinal)?.localPath != selection.page.sourcePath) return null
        return current.copy(position = selection.pageOrdinal, scrollOffset = 0).visitedAt(visitedAt)
    }

    fun readerChoice(selection: SavedTextReaderSelection): ChapterTranslationConfig =
        selection.receipt.configuration.copy(refinementRequest = null, memoryPacket = null)

    fun preferences(selection: SavedTextReaderSelection?, preferred: SavedTextReaderPreferences): SavedTextReaderPreferences =
        selection?.receipt?.configuration?.let { SavedTextReaderPreferences(it.targetLanguage, it.styleId, it.customStyle) } ?: preferred
}
