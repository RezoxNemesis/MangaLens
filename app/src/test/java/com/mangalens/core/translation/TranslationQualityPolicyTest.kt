package com.mangalens.core.translation

import org.junit.Assert.assertEquals
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


}
