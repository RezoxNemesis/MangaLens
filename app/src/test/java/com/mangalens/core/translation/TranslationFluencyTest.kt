package com.mangalens.core.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Controlled candidates exercise acceptance/retry; these tests do not certify model fluency. */
class TranslationFluencyTest {
    private val source = "Hey, Velora! I couldn't find the 3 coins."
    private val weak = "अरे, वेलोरा! मुझे 3 सिक्के नहीं मिल सका।"
    private val good = "अरे, वेलोरा! मुझे 3 सिक्के नहीं मिल सके।"

    @Test fun observedDativePluralObjectCannotAgreeWithSingularInability() {
        assertFalse(TranslationQualityPolicy.isUsable(source, weak, "hi"))
        assertThrows(TranslationQualityException::class.java) {
            TranslationQualityPolicy.choose(source, weak, "", "hi")
        }
    }

    @Test fun differentNounsNumbersAndFemininePluralAgreementHaveTheSameGuard() {
        assertFalse(TranslationQualityPolicy.isUsable("We couldn't find the 4 tickets.", "हमें 4 टिकटें नहीं मिल सकी।", "hi"))
        assertFalse(TranslationQualityPolicy.isUsable("I couldn't find the 2 books.", "मुझे 2 किताबें नहीं मिला।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("We couldn't find the 4 tickets.", "हमें 4 टिकटें नहीं मिल सकीं।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("I couldn't find the 2 books.", "मुझे 2 किताबें नहीं मिलीं।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable(source, good, "hi"))
    }

    @Test fun singularMassAndAgentiveSpeakerAgreementRemainValid() {
        assertTrue(TranslationQualityPolicy.isUsable("I couldn't find the 1 coin.", "मुझे 1 सिक्का नहीं मिल सका।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("I couldn't find the 3 litres of milk.", "मुझे 3 लीटर दूध नहीं मिल सका।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("I couldn't find the 3 coins.", "मैं 3 सिक्के नहीं खोज सका।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("I couldn't find the 3 coins.", "मैं 3 सिक्के नहीं खोज सकी।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("The 3 coins were not found by me.", "मेरे द्वारा 3 सिक्के नहीं पाए जा सके।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("I couldn't find it.", "मुझे वह नहीं मिल सका।", "hi"))
    }

    @Test fun aSavedHindiProofCannotHideTheObservedGrammarErrorInRomanOutput() {
        val rendered = HindiRomanization.render(weak, source)
        assertFalse(TranslationQualityPolicy.isUsable(source, rendered, "hi-latn", weak))
        assertThrows(TranslationQualityException::class.java) {
            HinglishTranslationOutput.fromHindiDraft(source, weak)
        }
    }

    @Test fun independentRomanRefinementCannotReintroduceTheKnownPluralDisagreement() {
        val draft = HinglishTranslationOutput.fromHindiDraft(source, good)
        val bad = "are, Velora! mujhe 3 sikke nahi mil sakaa."
        val selected = TranslationQualityPolicy.chooseDraft(source, draft, bad, "hi-latn")
        assertEquals(draft, selected)
        assertFalse(TranslationQualityPolicy.isUsable(source, bad, "hi-latn"))
    }

    @Test fun oneDistinctRealTranslatorInputRetryPreservesNameCountNegationAndHindiProof() = runBlocking {
        val inputs = mutableListOf<String>()
        val draft = EnglishHindiTranslationInputs.translateDraft(source, "hi-latn") { input ->
            inputs += input
            if (inputs.size == 1) weak else good
        }
        assertEquals(listOf(source, "Hey, Velora! I was not able to find the 3 coins."), inputs)
        assertEquals(good, draft.hindiDraft)
        assertTrue(TranslationQualityPolicy.isUsable(source, draft.text, "hi-latn", draft.hindiDraft))
    }

    @Test fun alreadyUsableAgreementMakesOnlyOneTranslationCall() = runBlocking {
        var calls = 0
        val draft = EnglishHindiTranslationInputs.translateDraft(source, "hi") { calls++; good }
        assertEquals(1, calls)
        assertEquals(good, draft.text)
    }

    @Test fun unsuccessfulGrammarAttemptsStayBoundedAndNeverBecomeUsable() = runBlocking {
        var calls = 0
        val draft = EnglishHindiTranslationInputs.translateDraft(source, "hi") { calls++; weak }
        assertEquals(2, calls)
        assertFalse(TranslationQualityPolicy.isUsable(source, draft.text, "hi"))
        assertThrows(TranslationQualityException::class.java) {
            TranslationQualityPolicy.chooseDraft(source, draft, "", "hi")
        }
        Unit
    }

    @Test fun aPinnedIndependentCandidateMustStillPreserveMeaningAndAmounts() {
        assertEquals(good, TranslationQualityPolicy.choose(source, weak, good, "hi", style = TranslationStyleProfile.FORMAL))
        for (bad in listOf("अरे, वेलोरा! मुझे 3 सिक्के मिल सके।", "अरे, वेलोरा! मुझे 4 सिक्के नहीं मिल सके।",
            "अरे, किसी और! मुझे 3 सिक्के नहीं मिल सके।")) {
            assertThrows(TranslationQualityException::class.java) {
                TranslationQualityPolicy.choose(source, weak, bad, "hi", style = TranslationStyleProfile.FORMAL)
            }
        }
    }

    @Test fun capturedStylesRetainExactValidatedHindiProofAcrossMemoryRoundTrip() {
        for (style in listOf(TranslationStyleProfile.NATURAL, TranslationStyleProfile.CASUAL,
            TranslationStyleProfile.FORMAL, TranslationStyleProfile.custom("Keep formal respectful speech."))) {
            val draft = HinglishTranslationOutput.fromHindiDraft(source, good, style)
            val selected = TranslationQualityPolicy.chooseDraft(source, draft, "", "hi-latn", style)
            assertEquals(good, selected.hindiDraft)
            val encoded = TranslationMemoryCodec.encode(source, selected, "hi-latn")
            assertEquals(selected, TranslationMemoryCodec.decode(source, encoded, "hi-latn"))
        }
    }

    @Test fun inabilityAlternativePreservesSubjectAndDoesNotRewritePositiveOrPerfectIdioms() {
        assertEquals(listOf("We couldn't open the 2 boxes.", "We were not able to open the 2 boxes."),
            EnglishHindiTranslationInputs.candidates("We couldn't open the 2 boxes."))
        for (text in listOf("I couldn't be happier.", "I couldn't agree more.", "I couldn't help laughing.",
            "I couldn't have found the 3 coins.", "Could you open the 2 boxes?")) {
            assertEquals(listOf(text), EnglishHindiTranslationInputs.candidates(text))
        }
    }

    @Test fun explicitSubjectsAndCurlyApostropheDoNotChangeNamesOrQuantities() {
        assertEquals(listOf("Hi, O'Neil! She could not see the 2 signs.", "Hi, O'Neil! She was not able to see the 2 signs."),
            EnglishHindiTranslationInputs.candidates("Hi, O'Neil! She could not see the 2 signs."))
        assertEquals(listOf("They couldn’t reach the 4 gates.", "They were not able to reach the 4 gates."),
            EnglishHindiTranslationInputs.candidates("They couldn’t reach the 4 gates."))
    }

    @Test fun nasalFemininePluralUsesItsCheckedHindiProofRatherThanAmbiguousRomanEndings() {
        val source = "I couldn't find the 2 books."
        for (hindi in listOf("मुझे 2 किताबें नहीं मिलीं।", "मुझे 2 किताबें नहीं मिल सकीं।")) {
            val draft = HinglishTranslationOutput.fromHindiDraft(source, hindi)
            assertTrue(TranslationQualityPolicy.isUsable(source, draft.text, "hi-latn", draft.hindiDraft))
            assertEquals(draft, TranslationQualityPolicy.chooseDraft(source, draft, "", "hi-latn"))
        }
    }

    @Test fun oldWeakCachedEvidenceIsRejectedWithoutChangingTheMemoryFormat() {
        val fields = listOf(source, "hi-latn", weak, HindiRomanization.render(weak, source))
        val oldEnvelope = "\u001eML-HI-LATN:1:" + fields.joinToString("") { "${it.length}:$it" }
        assertNull(TranslationMemoryCodec.decode(source, oldEnvelope, "hi-latn"))
    }

    @Test fun caseMarkedOrUnmarkedMeasurementPhrasesAndOtherLanguagesStayOutsideTheNarrowGate() {
        assertTrue(TranslationQualityPolicy.isUsable("I couldn't find it in the 3 rooms.", "मुझे 3 कमरों में नहीं मिल सका।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable("I couldn't get the 3 litres.", "mujhe 3 litre nahi mil sakaa.", "hi-latn"))
        assertTrue(TranslationQualityPolicy.isUsable("I couldn't find the 3 coins.", "Ich konnte die 3 Münzen nicht finden.", "de"))
        assertFalse(TranslationQualityPolicy.isUsable("I couldn't find the 3 coins.", "मुझे ३ सिक्के नहीं मिल सका।", "hi"))
    }
}
