package com.mangalens.core.translation

import com.mangalens.core.translation.memory.MemoryCorrectionEdit
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN form controls; generated values remain distinct from actual saved history. */
class ReaderBubbleAlternativeFormPolicyTest {
    private val actual = PersonalMemoryEditorValues("Native OCR", "haan.", "हाँ।")
    @Test fun originalOcrOnlyAlternativeKeepsActualTranslationAndHindiEvidence() {
        assertEquals(PersonalMemoryEditorValues("Retried OCR", "haan.", "हाँ।"),
            ReaderBubbleAlternativeFormPolicy.preset(actual, MemoryCorrectionEdit(correctedOcr = "Retried OCR")))
        assertEquals(PersonalMemoryEditorValues("Native OCR", "haan.", "हाँ।"), actual)
    }
    @Test fun translationAlternativeWithoutHindiCannotBorrowTheActualSavedHindiDraft() {
        assertEquals("", ReaderBubbleAlternativeFormPolicy.preset(actual, MemoryCorrectionEdit(translated = "nahi.")).hindiDraft)
    }
    @Test fun actualGeneratedHindiTravelsWithItsCorrespondingRomanAlternative() {
        assertEquals(PersonalMemoryEditorValues("Native OCR", "nahi.", "नहीं।"),
            ReaderBubbleAlternativeFormPolicy.preset(actual, MemoryCorrectionEdit(translated = "nahi.", hindiDraft = "नहीं।")))
    }
    @Test fun originalOcrAlternativeCreatesNoTranslatedOrHindiClaim() {
        val edit = ReaderBubbleAlternativeInputPolicy.edit(ReaderBubbleRegionAlternative("read", ReaderBubbleAlternativeKind.ORIGINAL_OCR, "Retried OCR"))
        assertEquals(MemoryCorrectionEdit(correctedOcr = "Retried OCR"), edit)
    }
    @Test fun aDraftPreservesItsActualHindiInsteadOfInferringOneFromRomanLetters() {
        val edit = ReaderBubbleAlternativeInputPolicy.edit(ReaderBubbleRegionAlternative("draft", ReaderBubbleAlternativeKind.ON_DEVICE_DRAFT,
            "No.", TranslationDraft("nahi.", "नहीं।")))
        assertEquals("नहीं।", edit.hindiDraft); assertEquals("nahi.", edit.translated)
    }
    @Test fun oversizedGeneratedTextCannotEnterThe4096CharacterForm() {
        assertTrue(runCatching { ReaderBubbleAlternativeInputPolicy.edit(ReaderBubbleRegionAlternative("read", ReaderBubbleAlternativeKind.ORIGINAL_OCR, "x".repeat(4097))) }.isFailure)
        assertTrue(runCatching { ReaderBubbleAlternativeInputPolicy.edit(ReaderBubbleRegionAlternative("draft", ReaderBubbleAlternativeKind.ON_DEVICE_DRAFT, "Yes.", TranslationDraft("x".repeat(4097)))) }.isFailure)
    }
    @Test fun emptyNativeReadingIsRejectedInsteadOfBecomingAnEmptyPreset() {
        assertTrue(runCatching { ReaderBubbleAlternativeInputPolicy.edit(ReaderBubbleRegionAlternative("read", ReaderBubbleAlternativeKind.ORIGINAL_OCR, " ")) }.isFailure)
    }
}
