package com.mangalens.core.translation.memory

/** Explicit personal classification, never an OCR/model semantic result. */
enum class MemoryUserRegionKind { DIALOGUE, NARRATION, SFX, FURIGANA }
enum class MemorySfxPresentation { KEEP_ORIGINAL, ALONGSIDE, REPLACE, ANNOTATE }
data class MemoryRegionPresentation(val userKind: MemoryUserRegionKind,
    val sfx: MemorySfxPresentation? = null, val annotation: String? = null) {
    fun validate() {
        require((userKind == MemoryUserRegionKind.SFX) == (sfx != null)) { "Choose a presentation for an explicitly classified SFX region." }
        require(annotation == null || sfx == MemorySfxPresentation.ANNOTATE && annotation.isNotBlank() && annotation.length <= 256 &&
            annotation.none { it == '\u0000' }) { "Use a short annotation only with Annotate." }
    }
}
