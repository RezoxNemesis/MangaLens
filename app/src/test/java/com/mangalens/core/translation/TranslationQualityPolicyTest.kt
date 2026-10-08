package com.mangalens.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TranslationQualityPolicyTest {
    @Test
    fun rejectsRefinementThatLeaksSourceScriptIntoHindi() {
        val chosen = TranslationQualityPolicy.choose(
            source = "こんにちは",
            draft = "नमस्ते",
            refined = "こんにちは 123",
            targetLanguage = "hi"
        )
        assertEquals("नमस्ते", chosen)
    }

    @Test
    fun keepsGoodRefinementAndRestoresSourceTerminalPunctuation() {
        val chosen = TranslationQualityPolicy.choose(
            source = "行くの?",
            draft = "क्या हम चलें",
            refined = "क्या हम अब चलें",
            targetLanguage = "hi"
        )
        assertEquals("क्या हम अब चलें?", chosen)
    }

    @Test
    fun rejectsLengthExplosionAndKeepsDraft() {
        val chosen = TranslationQualityPolicy.choose(
            source = "待って!",
            draft = "रुको!",
            refined = "यह एक बहुत लंबा अनावश्यक स्पष्टीकरण है ".repeat(12),
            targetLanguage = "hi"
        )
        assertEquals("रुको!", chosen)
    }
    @Test
    fun neutralEnglishDialogueDoesNotInventHindiRespect() {
        val chosen = TranslationQualityPolicy.choose(
            source = "Don't worry about it.",
            draft = "इसके बारे में चिंता मत कीजिए।",
            refined = "इसके बारे में चिंता मत कीजिए।",
            targetLanguage = "hi"
        )
        assertEquals("इसके बारे में चिंता मत करो।", chosen)
    }

    @Test
    fun hostileEnglishDialogueDropsFormalHindiPronouns() {
        val chosen = TranslationQualityPolicy.choose(
            source = "You... son... of a...",
            draft = "आप... एक कमीने के बेटे...",
            refined = "आप... एक कमीने के बेटे...",
            targetLanguage = "hi"
        )
        assertFalse(chosen.contains("आप"))
    }

    @Test
    fun explicitRespectCueKeepsFormalRegister() {
        val chosen = TranslationQualityPolicy.choose(
            source = "Sir, you should rest.",
            draft = "सर, आपको आराम करना चाहिए।",
            refined = "सर, आपको आराम करना चाहिए।",
            targetLanguage = "hi"
        )
        assertEquals("सर, आपको आराम करना चाहिए।", chosen)
    }


    @Test
    fun hindiRefinementWithHeavyEnglishLeakageFallsBackToHindiDraft() {
        val chosen = TranslationQualityPolicy.choose(
            source = "The Scientific Mindset",
            draft = "वैज्ञानिक मानसिकता",
            refined = "वैज्ञानिक Mindset Scientific सोच",
            targetLanguage = "hi"
        )
        assertEquals("वैज्ञानिक मानसिकता", chosen)
    }

    @Test(expected = TranslationQualityException::class)
    fun rejectsUntranslatedDraftWhenRefinementAlsoFails() {
        TranslationQualityPolicy.choose("BEATEN UP.", "BEATEN UP.", "", "hi")
    }

    @Test(expected = TranslationQualityException::class)
    fun rejectsMixedHindiDraftEvenWhenMostLettersAreHindi() {
        val source = "It just reminded me of the days I used to get beaten up."
        val bad = "यह मुझे उन दिनों की याद दिलाता था जब मैं बहुत तकलीफ में रहता था BEATEN UP."
        TranslationQualityPolicy.choose(source, bad, bad, "hi-IN")
    }

    @Test
    fun validRefinementCanRepairAnInvalidDraft() {
        assertEquals("मेरी पिटाई होती थी।", TranslationQualityPolicy.choose(
            "I used to get beaten up.", "BEATEN UP", "मेरी पिटाई होती थी।", "hi_IN"
        ))
    }

    @Test
    fun persistedTranslationsMustPassTheSameGate() {
        assertFalse(TranslationQualityPolicy.isUsable("BEATEN UP", "BEATEN UP", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("BEATEN UP", "पिटाई", "hi"))
        assertFalse(TranslationQualityPolicy.isUsable("OK", "OK", "hi"))
    }

    @Test
    fun punctuationInsideSourceDoesNotChangeStatementEnding() {
        assertEquals("उसने पूछा क्यों, फिर चला गया।", TranslationQualityPolicy.choose(
            "He asked why? Then left.", "उसने पूछा क्यों, फिर चला गया।", "", "hi"
        ))
        assertEquals("क्यों?", TranslationQualityPolicy.choose("Why?", "क्यों।", "", "hi"))
    }

    @Test(expected = TranslationQualityException::class)
    fun uncheckedLongDraftCannotAuthorizeItsOwnLength() {
        TranslationQualityPolicy.choose("Wait!", "यह बहुत अनावश्यक विवरण है ".repeat(20), "", "hi")
    }

    @Test
    fun permitsOnePreservedCharacterName() {
        assertTrue(TranslationQualityPolicy.isUsable("Jin is here.", "Jin अब हमारे साथ यहाँ मौजूद है।", "hi"))
    }

}

