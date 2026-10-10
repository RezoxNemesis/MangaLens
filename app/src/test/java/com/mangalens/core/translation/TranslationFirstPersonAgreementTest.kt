package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

/** Authored candidate controls, not screenshot ground truth or model-quality certification. */
class TranslationFirstPersonAgreementTest {
    private val source = "I can take you there."
    private val good = "मैं तुम्हें वहाँ ले जा सकता हूँ।"
    private val weak = "मैं तुम्हें वहाँ ले जा सकते हैं।"

    @Test fun clearFirstPersonModalCannotUseThirdPersonHindiAuxiliary() {
        for (bad in listOf(weak, "मैं तुम्हें वहाँ ले जा सकता है।", "मैं तुम्हें वहाँ ले जा सकती हैं।", "मैं तुम्हें वहाँ ले जा सकते हैं ।")) {
            assertFalse(bad, TranslationQualityPolicy.isUsable(source, bad, "hi"))
            assertThrows(TranslationQualityException::class.java) { TranslationQualityPolicy.choose(source, bad, "", "hi") }
        }
    }
    @Test fun knownAgreementAllowsEitherUnknownSpeakerGenderWithoutChoosingOne() {
        for (candidate in listOf(good, "मैं तुम्हें वहाँ ले जा सकती हूँ।", "मैं तुम्हें वहाँ ले जा सकता हूं।"))
            assertTrue(candidate, TranslationQualityPolicy.isUsable(source, candidate, "hi"))
    }
    @Test fun aBadHindiRefinementKeepsTheUsableDraftAndARepairCanReplaceABadDraft() {
        assertEquals(good, TranslationQualityPolicy.choose(source, good, weak, "hi"))
        assertEquals(good, TranslationQualityPolicy.choose(source, weak, good, "hi"))
    }
    @Test fun romanCandidateMustPassItsOwnFirstPersonAgreement() {
        for (bad in listOf("main tumhein wahan le ja sakte hain.", "main yahan aa sakta hai.", "mai yahan aa sakti hain."))
            assertFalse(bad, TranslationQualityPolicy.isUsable(source, bad, "hi-latn"))
        assertTrue(TranslationQualityPolicy.isUsable(source, "main tumhein wahan le ja sakta hoon.", "hi-latn"))
        assertTrue(TranslationQualityPolicy.isUsable(source, "main tumhein wahan le ja sakti hoon.", "hi-latn"))
    }
    @Test fun invalidHindiCannotBecomeEvidenceForItsExactRomanRendering() {
        assertFalse(TranslationQualityPolicy.isUsable(source, HindiRomanization.render(weak, source), "hi-latn", weak))
        assertThrows(TranslationQualityException::class.java) { HinglishTranslationOutput.fromHindiDraft(source, weak) }
    }
    @Test fun independentRomanRefinementCannotBorrowADifferentHindiCandidateProof() {
        val draft = HinglishTranslationOutput.fromHindiDraft(source, good)
        val rejected = "main tumhein wahan le ja sakte hain."
        assertEquals(draft, TranslationQualityPolicy.chooseDraft(source, draft, rejected, "hi-latn"))
        assertFalse(TranslationQualityPolicy.isUsable(source, rejected, "hi-latn", good))
        assertFalse(TranslationQualityPolicy.isUsable(source, "I can take you there.", "hi-latn", good))
    }
    @Test fun repairedHindiMustPassBeforeItCanOwnItsRomanRendering() {
        val repaired = TranslationQualityPolicy.chooseDraft(source, TranslationDraft(weak), good, "hi")
        val roman = HinglishTranslationOutput.fromHindiDraft(source, repaired.text)
        assertEquals(good, roman.hindiDraft)
        assertTrue(TranslationQualityPolicy.isUsable(source, roman.text, "hi-latn", roman.hindiDraft))
        assertFalse(TranslationQualityPolicy.isUsable(source, "main tumhein wahan le ja sakte hain.", "hi-latn", roman.hindiDraft))
    }
    @Test fun explicitNegationStaysRequiredInBothScriptPaths() {
        val negativeSource = "I cannot take you there."
        val negativeHindi = "मैं तुम्हें वहाँ नहीं ले जा सकता हूँ।"
        assertTrue(TranslationQualityPolicy.isUsable(negativeSource, negativeHindi, "hi"))
        assertFalse(TranslationQualityPolicy.isUsable(negativeSource, good, "hi"))
        val draft = HinglishTranslationOutput.fromHindiDraft(negativeSource, negativeHindi)
        assertTrue(TranslationQualityPolicy.isUsable(negativeSource, draft.text, "hi-latn", draft.hindiDraft))
        assertFalse(TranslationQualityPolicy.isUsable(negativeSource, "main tumhein wahan le ja sakta hoon.", "hi-latn"))
    }
    @Test fun subordinateQuotedPluralAndUnknownSourceClausesStayOutsideThisNarrowInference() {
        assertTrue(TranslationFluencyPolicy.isPlausible("I can see that they can leave.", "मैं देख सकता हूँ कि वे जा सकते हैं।", "hi"))
        assertTrue(TranslationFluencyPolicy.isPlausible("I can say \"they can leave\".", "मैं कह सकता हूँ कि वे जा सकते हैं।", "hi"))
        assertTrue(TranslationFluencyPolicy.isPlausible("He can take you there.", "वह तुम्हें वहाँ ले जा सकते हैं।", "hi"))
        assertTrue(TranslationFluencyPolicy.isPlausible("I could take you there.", weak, "hi"))
        assertTrue(TranslationFluencyPolicy.isPlausible("I can see that they can leave.", "main dekh sakta hoon ki ve ja sakte hain.", "hi-latn"))
    }
    @Test fun sourceAndTargetNamesOrNumbersRemainIndependentRequirements() {
        val original = "Hey, Velora! I can bring 3 coins."
        assertFalse(TranslationQualityPolicy.isUsable(original, "मैं 3 सिक्के ला सकता हूँ।", "hi"))
        assertFalse(TranslationQualityPolicy.isUsable(original, "अरे वेलोरा! मैं 4 सिक्के ला सकता हूँ।", "hi"))
        assertTrue(TranslationQualityPolicy.isUsable(original, "अरे वेलोरा! मैं 3 सिक्के ला सकता हूँ।", "hi"))
    }
}
