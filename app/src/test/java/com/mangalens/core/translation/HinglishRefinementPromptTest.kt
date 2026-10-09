package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class HinglishRefinementPromptTest {
    @Test fun romanTargetExplicitlyRequiresReadableLatinHindiAndRetainedTerms() {
        val prompt = prompt("hi-latn")
        assertTrue(prompt.contains("TARGET LANGUAGE: hi-latn"))
        assertTrue(prompt.contains("Hinglish (Roman Hindi)"))
        assertTrue(prompt.contains("Latin letters"))
        assertTrue(prompt.contains("No Devanagari"))
        assertTrue(prompt.contains("source name spellings"))
        assertTrue(prompt.contains("level, skill, mana"))
        assertTrue(prompt.contains("tum-register"))
        assertTrue(prompt.contains("Never invent politeness"))
        assertFalse(prompt.contains("natural तुम-register"))
    }

    @Test fun ordinaryHindiKeepsItsScriptGuidanceAndSelectedStyle() {
        val prompt = prompt("hi")
        assertTrue(prompt.contains("natural तुम-register"))
        assertFalse(prompt.contains("No Devanagari"))
        assertTrue(prompt.contains(TranslationStyleProfile.WEBTOON.instruction))
    }

    @Test fun romanPromptRetainsSourceDraftContextAndGlossaryInsteadOfReTranslatingTheTargetTag() {
        val prompt = prompt(" HI_LATN ")
        assertTrue(prompt.contains("Jin, do not leave!"))
        assertTrue(prompt.contains("Jin, mat jao!"))
        assertTrue(prompt.contains("Jin is speaking to a friend."))
        assertTrue(prompt.contains("Hunter => Hunter"))
        assertTrue(prompt.contains("Hinglish (Roman Hindi)"))
    }

    private fun prompt(target: String) = buildTranslationRefinementPrompt(
        "Jin, do not leave!", "Jin, mat jao!", target, TranslationStyleProfile.WEBTOON,
        "Jin is speaking to a friend.", mapOf("Hunter" to "Hunter")
    )
}
