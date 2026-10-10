package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

/** Observable selection constraints only; synthetic pairs do not prove semantic equivalence. */
class TranslationCandidateComparisonTest {
    @Test fun usableHindiDraftWinsWhenRefinementAddsADifferentExplicitNumber() {
        val source = "I found 3 coins."
        val draft = "मुझे 3 सिक्के मिले।"
        val refined = "मुझे 3 सिक्के और 4 चाबियाँ मिलीं।"
        assertTrue(TranslationQualityPolicy.isUsable(source, refined, "hi"))
        assertEquals(TranslationCandidateComparison.NEW_EXPLICIT_NUMBER,
            TranslationCandidateComparisonPolicy.assess(source, draft, refined, "hi"))
        assertEquals(draft, TranslationQualityPolicy.choose(source, draft, refined, "hi"))
    }
    @Test fun devanagariAndAsciiCountsCompareWithoutChangingTheirText() {
        val source = "I found 3 coins."
        val draft = "मुझे 3 सिक्के मिले।"
        val refined = "मैंने ३ सिक्के पाए।"
        assertEquals(refined, TranslationQualityPolicy.choose(source, draft, refined, "hi"))
        assertEquals(draft, TranslationQualityPolicy.choose(source, draft, "मुझे ३ सिक्के और ४ चाबियाँ मिलीं।", "hi"))
    }
    @Test fun terminalSentencePunctuationDoesNotHideSourceOrRefinementIntegers() {
        assertEquals(TranslationCandidateComparison.NEW_EXPLICIT_NUMBER,
            TranslationCandidateComparisonPolicy.assess("I found 3.", "3 मिले।", "3 के बाद 4.", "hi"))
        assertEquals(TranslationCandidateComparison.NEW_EXPLICIT_NUMBER,
            TranslationCandidateComparisonPolicy.assess("I found 3.", "3 मिले।", "4.", "hi"))
        assertEquals(TranslationCandidateComparison.COMPATIBLE,
            TranslationCandidateComparisonPolicy.assess("Take 3.5 litres.", "3.5 लीटर।", "3.5 लीटर की 4 बोतलें।", "hi"))
    }
    @Test fun sourceSpelledNumbersAndUsableDraftQuantitiesAreNotMistakenForNewDigits() {
        assertEquals(TranslationCandidateComparison.COMPATIBLE,
            TranslationCandidateComparisonPolicy.assess("One of the 3 doors.", "3 दरवाज़ों में से एक।", "3 दरवाज़ों में से 1।", "hi"))
        assertEquals(TranslationCandidateComparison.COMPATIBLE,
            TranslationCandidateComparisonPolicy.assess("I found 3 pairs.", "मुझे 3 जोड़े यानी 6 वस्तुएँ मिलीं।", "मुझे 3 जोड़ों में 6 वस्तुएँ मिलीं।", "hi"))
    }
    @Test fun bothUsableCandidatesWithRetainedConstraintsStillPermitRefinement() {
        val source = "I found 3 coins."
        val refined = "मैंने 3 सिक्के पाए।"
        assertEquals(refined, TranslationQualityPolicy.choose(source, "मुझे 3 सिक्के मिले।", refined, "hi"))
    }
    @Test fun independentRomanNewDigitsCannotUseTheDraftsHindiEvidence() {
        val source = "Can you give me 3 coins?"
        val draft = HinglishTranslationOutput.fromHindiDraft(source, "क्या तुम मुझे 3 सिक्के दे सकते हो?")
        val bad = "kya tum mujhe 3 sikke aur 4 chabiyan de sakte ho?"
        assertTrue(TranslationQualityPolicy.isUsable(source, bad, "hi-latn"))
        assertEquals(draft, TranslationQualityPolicy.chooseDraft(source, draft, bad, "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable(source, bad, "hi-latn", draft.hindiDraft))
    }
    @Test fun independentRomanCandidateWithRetainedConstraintsHasNoBorrowedHindiProof() {
        val source = "Can you give me 3 coins?"
        val draft = HinglishTranslationOutput.fromHindiDraft(source, "क्या तुम मुझे 3 सिक्के दे सकते हो?")
        val refined = "tum mujhe 3 sikke de sakte ho?"
        assertTrue(TranslationQualityPolicy.isUsable(source, refined, "hi-latn"))
        val selected = TranslationQualityPolicy.chooseDraft(source, draft, refined, "hi-latn")
        assertEquals(refined, selected.text)
        assertNull(selected.hindiDraft)
    }
    @Test fun capturedSourceMentionedHindiGlossaryRetainsItsObservedPreferredSpelling() {
        val source = "Use your mana."
        val draft = "अपना माना उपयोग करो।"
        val refined = "अपनी ऊर्जा इस्तेमाल करो।"
        assertEquals(draft, TranslationQualityPolicy.choose(source, draft, refined, "hi", capturedGlossary = mapOf("mana" to "माना")))
        assertEquals(refined, TranslationQualityPolicy.choose(source, draft, refined, "hi"))
    }
    @Test fun currentRomanGlossaryHasItsOwnSpellingAndTargetCandidate() {
        val source = "Use your mana."
        val draft = HinglishTranslationOutput.fromHindiDraft(source, "अपना माना उपयोग करो।")
        val refined = "apni urja ka istemal karo."
        assertTrue(TranslationQualityPolicy.isUsable(source, refined, "hi-latn"))
        assertEquals(draft, TranslationQualityPolicy.chooseDraft(source, draft, refined, "hi-latn", capturedGlossary = mapOf("mana" to "maanaa")))
        assertEquals(refined, TranslationQualityPolicy.chooseDraft(source, draft, refined, "hi-latn",
            capturedGlossary = mapOf("mana" to "mana")).text)
    }
    @Test fun absentWholeSourceTermOrUnobservedPreferredSpellingDoesNotInventAConstraint() {
        for (glossary in listOf(mapOf("man" to "माना"), mapOf("mana" to "जादुई शक्ति")))
            assertEquals(TranslationCandidateComparison.COMPATIBLE,
                TranslationCandidateComparisonPolicy.assess("Use your mana.", "अपना माना उपयोग करो।", "अपनी ऊर्जा इस्तेमाल करो।", "hi", glossary))
    }
    @Test fun registerEditCannotEraseExplicitCapturedPreferredAddress() {
        val source = "You can come here."
        val hindi = "आप यहाँ आ सकते हैं।"
        assertEquals(hindi, TranslationQualityPolicy.choose(source, hindi, "", "hi", style = TranslationStyleProfile.NATURAL,
            capturedGlossary = mapOf("you" to "आप")))
        val roman = HinglishTranslationOutput.fromHindiDraft(source, hindi, TranslationStyleProfile.FORMAL)
        val selected = TranslationQualityPolicy.chooseDraft(source, roman, "", "hi-latn", TranslationStyleProfile.NATURAL,
            mapOf("you" to "aap"))
        assertEquals(roman, selected)
        assertTrue(TranslationQualityPolicy.isUsable(source, selected.text, "hi-latn", selected.hindiDraft))
    }
    @Test fun aSuccessfulRegisterEditKeepsOnlyItsOwnActualHindiRenderingEvidence() {
        val source = "Can you come here?"
        val roman = HinglishTranslationOutput.fromHindiDraft(source, "क्या आप यहाँ आ सकते हैं?", TranslationStyleProfile.FORMAL)
        val selected = TranslationQualityPolicy.chooseDraft(source, roman, "", "hi-latn", TranslationStyleProfile.NATURAL)
        assertNotNull(selected.hindiDraft)
        assertTrue(TranslationQualityPolicy.isUsable(source, selected.text, "hi-latn", selected.hindiDraft))
        assertEquals(HindiRomanization.render(selected.hindiDraft!!, source), selected.text)
    }
    @Test fun unknownDecimalSourceAndOtherLanguagesAreNotClaimedAsIntegerEquivalence() {
        assertEquals(TranslationCandidateComparison.COMPATIBLE,
            TranslationCandidateComparisonPolicy.assess("Take 1.5 litres.", "1.5 लीटर लो।", "1.5 लीटर की 2 बोतलें लो।", "hi"))
        assertEquals(TranslationCandidateComparison.COMPATIBLE,
            TranslationCandidateComparisonPolicy.assess("I found 3 coins.", "Ich fand 3 Münzen.", "Ich fand 3 Münzen und 4 Schlüssel.", "de"))
    }
}
