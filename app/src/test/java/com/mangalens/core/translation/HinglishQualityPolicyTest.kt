package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class HinglishQualityPolicyTest {
    @Test fun readableRomanHindiIsUsableOnlyForTheRomanTarget() {
        assertTrue(TranslationQualityPolicy.isUsable("I am fine.", "main theek hoon.", "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable("I am fine.", "main theek hoon.", "hi"))
        assertFalse(TranslationQualityPolicy.isUsable("I am fine.", "मैं ठीक हूँ।", "hi-latn"))
        assertTrue(TranslationQualityPolicy.isUsable("I am fine.", "मैं ठीक हूँ।", "hi-IN"))
    }

    @Test fun refinementsInEnglishOrDevanagariFallBackToTheGoodRomanDraft() {
        val draft = "uski pitaai hui."
        assertEquals(draft, TranslationQualityPolicy.choose("He was beaten up.", draft, "He was beaten up.", "hi-latn"))
        assertEquals(draft, TranslationQualityPolicy.choose("He was beaten up.", draft, "उसकी पिटाई हुई।", "hi-latn"))
    }

    @Test fun namesAndEnglishGenreTermsSurviveAlongsideHindiGrammar() {
        assertTrue(TranslationQualityPolicy.isUsable("Sung Jinwoo's level increased!", "Sung Jinwoo ka level badh gaya!", "hi-latn"))
        assertTrue(TranslationQualityPolicy.isUsable("Jin has mana.", "Jin ke paas mana hai.", "hi-latn"))
    }

    @Test fun sourceHindiCanBeRomanizedAndAlreadyRomanHindiDoesNotNeedEnglishTranslation() {
        assertTrue(TranslationQualityPolicy.isUsable("मुझे नहीं पता।", "mujhe nahi pata.", "hi-latn"))
        assertEquals("Tum ghar kab aaoge?", TranslationQualityPolicy.choose("Tum ghar kab aaoge?", "Tum ghar kab aaoge?", "", "hi-latn"))
    }

    @Test fun copiedEnglishClausesAndExplanatoryOrRepeatedOutputsAreRejected() {
        assertFalse(TranslationQualityPolicy.isUsable("I WAS BEATEN UP.", "I WAS BEATEN UP.", "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable("I WAS BEATEN UP.", "mujhe I WAS BEATEN UP hai.", "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable("I WAS BEATEN UP हा", "I WAS BEATEN UP.", "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable("Run!", "bhaago ".repeat(20), "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable("Wait!", "ruko aur ab main poori kahani samjhata hoon. ".repeat(30), "hi-latn"))
    }

    @Test fun romanPunctuationRetainsTheSourceQuestionOrShout() {
        assertEquals("kya hua?", TranslationQualityPolicy.choose("What happened?", "kya hua.", "", "hi-latn"))
    }

    @Test fun hindiIntermediateAllowsRetainedNamesAndGenreTermsWhileRejectingCopiedClauses() {
        assertEquals("Sung Jinwoo का level बढ़ गया!", TranslationQualityPolicy.hindiDraftForRomanOutput(
            "Sung Jinwoo's level increased!", "Sung Jinwoo का level बढ़ गया!"))
        assertThrows(TranslationQualityException::class.java) { TranslationQualityPolicy.hindiDraftForRomanOutput(
            "I WAS BEATEN UP.", "मैं I WAS BEATEN UP था।") }
        assertThrows(TranslationQualityException::class.java) { TranslationQualityPolicy.hindiDraftForRomanOutput(
            "Please Tell me.", "Please Tell मुझे।") }
    }

    @Test fun shortHindiOutputsAreUsableWithoutAcceptingUnchangedEnglishWords() {
        assertTrue(TranslationQualityPolicy.isUsable("I", "main", "hi-latn"))
        assertTrue(TranslationQualityPolicy.isUsable("Home", "ghar", "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable("Main", "Main", "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable("Fine", "Fine", "hi-latn"))
    }

    @Test fun romanRefinementCannotInventPolitenessButExplicitFormalCuesRemain() {
        assertEquals("tum chinta mat karo.", TranslationQualityPolicy.choose("Don't worry.", "tum chinta mat karo.", "aap chinta mat kijiye.", "hi-latn"))
        assertEquals("aap yahan rahiye, sir.", TranslationQualityPolicy.choose("Stay here, sir.", "aap yahan rahiye, sir.", "", "hi-latn"))
    }

    @Test fun hindiSourceDoesNotMakeAnEnglishRefinementUsable() {
        assertEquals("main theek hoon.", TranslationQualityPolicy.choose("मैं ठीक हूँ।", "main theek hoon.", "I am fine.", "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable("मुझे नहीं पता।", "I don't know.", "hi-latn"))
    }

    @Test fun directHindiNounsRemainUsableWithoutInventingHindiGrammar() {
        assertTrue(TranslationQualityPolicy.isUsable("क़लम", "qalam", "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable("क़लम", "pen", "hi-latn"))
    }

    @Test fun titleCaseEnglishActionsAreNotProtectedAsCharacterNames() {
        assertThrows(TranslationQualityException::class.java) { TranslationQualityPolicy.hindiDraftForRomanOutput(
            "He Was Seriously Injured.", "वह Seriously Injured था।") }
        assertFalse(TranslationQualityPolicy.isUsable("He Was Seriously Injured.", "woh Seriously Injured tha.", "hi-latn"))
    }
}
