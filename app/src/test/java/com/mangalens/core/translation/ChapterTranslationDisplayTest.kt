package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class ChapterTranslationDisplayTest {
    private fun task(chapter: String, language: String = "hi", style: String = "natural", custom: String = "", updated: Long = 1L) =
        ChapterTranslationTask("id-$chapter-$language-$style", "generation", chapter, "Chapter",
            ChapterTranslationConfig(language, style, custom), emptyList(), ChapterTranslationStatus.PAUSED, 0L, updated)

    @Test fun newerUnrelatedChapterCannotStealReaderProgress() {
        val wanted = task("active")
        assertEquals(wanted, ChapterTranslationDisplay.select(listOf(wanted, task("other", updated = 20L)), "active", "hi", "natural", ""))
    }
    @Test fun languageSwitchCannotRestoreOlderLanguageSurface() {
        assertNull(ChapterTranslationDisplay.select(listOf(task("active", "hi")), "active", "ja", "natural", ""))
    }
    @Test fun styleSwitchCannotRestoreIncompatibleLettering() {
        assertNull(ChapterTranslationDisplay.select(listOf(task("active", style = "faithful")), "active", "hi", "natural", ""))
    }
    @Test fun customStyleRequiresMatchingInstruction() {
        val wanted = task("active", style = "custom", custom = "Preserve honorifics")
        assertNull(ChapterTranslationDisplay.select(listOf(wanted), "active", "hi", "custom", "Use formal speech"))
        assertEquals(wanted, ChapterTranslationDisplay.select(listOf(wanted), "active", "hi", "custom", " Preserve honorifics "))
    }
    @Test fun ordinaryStyleIgnoresDormantCustomInstruction() {
        val wanted = task("active")
        assertEquals(wanted, ChapterTranslationDisplay.select(listOf(wanted), "active", "HI", "natural", "Old unused instruction"))
    }
    @Test fun missingChapterDoesNotExposeAnotherChaptersOutput() {
        assertNull(ChapterTranslationDisplay.select(listOf(task("active")), "", "hi", "natural", ""))
    }
    @Test fun newerTaskWithDifferentOcrOptionsCannotStealControls() {
        val wanted = task("active").copy(id = "japanese", config = ChapterTranslationConfig("hi", ocrScript = "JAPANESE"))
        val newer = task("active", updated = 50L).copy(id = "latin", config = ChapterTranslationConfig("hi", ocrScript = "LATIN"))
        assertEquals(wanted, ChapterTranslationDisplay.select(listOf(wanted, newer), "active", "hi", "natural", "", wanted.config))
    }
    @Test fun disabledRefinementCannotRestoreAnotherConfigurationsResult() {
        val other = task("active").copy(config = ChapterTranslationConfig("hi", localRefinement = true))
        assertNull(ChapterTranslationDisplay.select(listOf(other), "active", "hi", "natural", "", ChapterTranslationConfig("hi")))
    }
}
