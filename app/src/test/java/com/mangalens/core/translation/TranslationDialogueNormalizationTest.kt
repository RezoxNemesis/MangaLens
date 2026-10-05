package com.mangalens.core.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationDialogueNormalizationTest {
    @Test
    fun rewritesHighConfidenceEnglishIdiomsBeforeHindiMachineTranslation() {
        assertEquals(
            "It made me even angrier.",
            normalizeEnglishDialogueForHindi("It pissed me off even more.")
        )
        assertEquals(
            "I used to be beaten badly.",
            normalizeEnglishDialogueForHindi("I used to get beaten up.")
        )
    }

    @Test
    fun leavesOrdinaryDialogueUntouched() {
        val source = "I think it was my first time experiencing pain like that."
        assertEquals(source, normalizeEnglishDialogueForHindi(source))
    }
}
