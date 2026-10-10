package com.mangalens.core.translation

import com.mangalens.core.translation.memory.MemoryRegionBounds
import com.mangalens.core.translation.memory.MemorySfxPresentation

/** A render hint, never a source credential. The gateway verifies actual native and personal receipts. */
data class PersonalReaderRegion internal constructor(val pageIndex: Int, val nativeIndex: Int, val personal: PersonalMangaLettering)

internal object ReaderSfxRestorationPlan {
    const val MAX_REGIONS = 4
    const val MAX_DECODE_PIXELS = 500_000L
    fun needsOriginal(personal: PersonalMangaLettering): Boolean = personal.regionPresentation?.sfx in
        setOf(MemorySfxPresentation.KEEP_ORIGINAL, MemorySfxPresentation.ALONGSIDE, MemorySfxPresentation.ANNOTATE)
    fun originalPatch(page: ChapterTranslationPage, index: Int, expected: SavedMangaLettering): MemoryRegionBounds? {
        if (page.lettering.getOrNull(index) != expected || expected.originalSourceBounds == null) return null
        val originalWidth = page.originalWidth ?: return null
        val originalHeight = page.originalHeight ?: return null
        fun overlaps(other: SavedMangaLettering) = expected.left < other.right && expected.right > other.left &&
            expected.top < other.bottom && expected.bottom > other.top
        if (page.lettering.filterIndexed { other, _ -> other != index }.any(::overlaps)) return null
        return runCatching { OriginalMangaGeometry.fromSampled(expected.left, expected.top, expected.right, expected.bottom,
            page.imageWidth, page.imageHeight, originalWidth, originalHeight).let { MemoryRegionBounds(it.left, it.top, it.right, it.bottom) } }.getOrNull()
    }
}
