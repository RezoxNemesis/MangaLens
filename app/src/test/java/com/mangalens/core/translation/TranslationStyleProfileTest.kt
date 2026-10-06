package com.mangalens.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationStyleProfileTest {
    @Test
    fun builtInProfilesHaveDistinctRulesAndMemoryKeys() {
        val profiles = listOf(
            TranslationStyleProfile.NATURAL,
            TranslationStyleProfile.FAITHFUL,
            TranslationStyleProfile.CASUAL,
            TranslationStyleProfile.FORMAL,
            TranslationStyleProfile.WEBTOON
        )
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size)
        assertEquals(profiles.size, profiles.map { it.instruction }.distinct().size)
        assertEquals(profiles.size, profiles.map { it.memoryKey }.distinct().size)
    }

    @Test
    fun customStyleIsBoundedAndDifferentInstructionsDoNotShareMemory() {
        val bounded = TranslationStyleProfile.custom("x".repeat(TranslationStyleProfile.MAX_CUSTOM_INSTRUCTION_CHARS + 500))
        assertEquals(TranslationStyleProfile.MAX_CUSTOM_INSTRUCTION_CHARS, bounded.instruction.length)
        assertTrue(bounded.memoryKey.startsWith("custom:"))

        val concise = TranslationStyleProfile.custom("Keep dialogue concise")
        val expressive = TranslationStyleProfile.custom("Keep dialogue expressive")
        assertNotEquals(concise.memoryKey, expressive.memoryKey)
        assertEquals(concise.memoryKey, TranslationStyleProfile.custom("  Keep dialogue concise  ").memoryKey)
    }

    @Test
    fun refinementPromptUsesSelectedStyleAndBoundsContextAndGlossary() {
        val glossary = linkedMapOf<String, String>().apply {
            repeat(25) { index -> put("name$index", "value$index") }
        }
        val prompt = buildTranslationRefinementPrompt(
            source = "Source line",
            translated = "Draft line",
            targetLanguage = "hi",
            style = TranslationStyleProfile.FORMAL,
            chapterContext = "context ".repeat(1200),
            glossary = glossary
        )

        assertTrue(prompt.contains("STYLE: Formal"))
        assertTrue(prompt.contains(TranslationStyleProfile.FORMAL.instruction))
        assertTrue(prompt.contains("name19 => value19"))
        assertFalse(prompt.contains("name20 => value20"))
        assertTrue(prompt.contains("Source line"))
        assertTrue(prompt.contains("Draft line"))
        assertTrue(prompt.length < 12_000)
    }
}
