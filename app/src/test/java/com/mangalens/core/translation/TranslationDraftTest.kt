package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationDraftTest {
    private val nouns = listOf("Fire!" to "आग!", "Power!" to "शक्ति!", "Sword!" to "तलवार!")

    @Test fun validatedShortNounsKeepEvidenceThroughSelectionInsteadOfInventingHindiGrammar() {
        for ((source, hindi) in nouns) {
            val draft = HinglishTranslationOutput.fromHindiDraft(source, hindi)
            assertEquals(hindi, draft.hindiDraft)
            assertFalse(TranslationQualityPolicy.isUsable(source, draft.text, "hi-latn"))
            assertTrue(TranslationQualityPolicy.isUsable(source, draft.text, "hi-latn", draft.hindiDraft))
            assertEquals(draft, TranslationQualityPolicy.chooseDraft(source, draft, "", "hi-latn"))
            assertEquals(draft.text, TranslationQualityPolicy.choose(source, draft.text, "", "hi-latn", draft.hindiDraft))
        }
    }

    @Test fun englishRefinementCannotBorrowTheHindiDraftsEvidence() {
        for ((source, hindi) in nouns) {
            val draft = HinglishTranslationOutput.fromHindiDraft(source, hindi)
            for (refinement in listOf(source, "The fire was incredibly strong.", "I was beaten up.", "आग!")) {
                assertEquals(draft, TranslationQualityPolicy.chooseDraft(source, draft, refinement, "hi-latn"))
                assertFalse(TranslationQualityPolicy.isUsable(source, refinement, "hi-latn", hindi))
            }
        }
    }

    @Test fun aDifferentIndependentlyPlausibleRefinementDropsTheOldEvidence() {
        val source = "Where is the sword?"
        val draft = HinglishTranslationOutput.fromHindiDraft(source, "तलवार कहाँ है?")
        val refined = TranslationQualityPolicy.chooseDraft(source, draft, "talvaar yahan hai!", "hi-latn")
        assertEquals("talvaar yahan hai!", refined.text)
        assertNull(refined.hindiDraft)
        assertTrue(TranslationQualityPolicy.isUsable(source, refined.text, "hi-latn"))
    }

    @Test fun proofMustDescribeThisExactOutputAndMustActuallyContainValidatedHindi() {
        assertFalse(TranslationQualityPolicy.isUsable("Fire!", "aag!", "hi-latn", "तलवार!"))
        assertFalse(TranslationQualityPolicy.isUsable("Fire!", "AAG!", "hi-latn", "आग!"))
        assertFalse(TranslationQualityPolicy.isUsable("Fire!", "aag!", "hi-latn", "Fire!"))
        assertFalse(TranslationQualityPolicy.isUsable("Fire!", "aag!", "hi", "आग!"))
        assertFalse(TranslationQualityPolicy.isUsable("Fire!", "aag!", "hi-latn", "आ".repeat(8001)))
    }

    @Test fun unchangedEnglishCannotBeMistakenForANameInTheShortNounRegression() {
        for ((source, _) in nouns) {
            assertFalse(TranslationQualityPolicy.isUsable(source, source, "hi-latn"))
            assertThrows(TranslationQualityException::class.java) { HinglishTranslationOutput.fromHindiDraft(source, source) }
        }
    }

    @Test fun ordinaryHindiAndPlainRomanDialogueKeepTheirExistingBehavior() {
        assertEquals(TranslationDraft("आग!"), TranslationQualityPolicy.chooseDraft("Fire!", TranslationDraft("आग!"), "", "hi"))
        assertEquals(TranslationDraft("Tum ghar kab aaoge?"), TranslationQualityPolicy.chooseDraft(
            "Tum ghar kab aaoge?", TranslationDraft("Tum ghar kab aaoge?"), "", "hi-latn"))
        assertEquals("Jin", HinglishTranslationOutput.fromHindiDraft("Jin", "Jin").text)
        assertNull(HinglishTranslationOutput.fromHindiDraft("Jin", "Jin").hindiDraft)
    }
}
