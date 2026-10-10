package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import org.junit.Assert.*
import org.junit.Test

/** Controlled text pairs exercise the real target gate; these tests do not assert model quality. */
class PersonalMemoryEditorInputPolicyTest {
    private val nativeHindi = "हाँ।"
    private val receipt = MemoryPublicationReceipt(
        MemorySourceProof("c".repeat(32), 0, "/private/fixture.png", "a".repeat(64), 100, 200, MemoryRegionBounds(1, 2, 90, 190)),
        "d".repeat(32), "e".repeat(32), "hi-latn", "full-config", "Yes.", "haan.", "/private/output.png", "b".repeat(64),
        nativeAuthorityVersion = 1, ownerRequestId = "reader:fixture", presentationEpoch = 1, associationRevision = 0)

    @Test fun initialShowsActualSavedNativeHindiAndUntouchedFormIsNotAnEdit() {
        val initial = PersonalMemoryEditorInputPolicy.initial(receipt, nativeHindi, null)
        assertEquals(PersonalMemoryEditorValues("Yes.", "haan.", nativeHindi), initial)
        assertFalse(PersonalMemoryEditorInputPolicy.changed(initial, initial, receipt.targetLanguage))
        assertEquals(MemoryCorrectionEdit(), PersonalMemoryEditorInputPolicy.edit(receipt, nativeHindi, initial))
    }

    @Test fun hindiOnlyOrthographyEditRetainsExplicitUnchangedRomanAndActualHindiPair() {
        val initial = PersonalMemoryEditorInputPolicy.initial(receipt, nativeHindi, null)
        val edited = initial.copy(hindiDraft = "हां।")
        assertTrue(PersonalMemoryEditorInputPolicy.changed(initial, edited, receipt.targetLanguage))
        val edit = PersonalMemoryEditorInputPolicy.edit(receipt, nativeHindi, edited)
        assertEquals(MemoryCorrectionEdit(translated = "haan.", hindiDraft = "हां।"), edit)
        assertEquals("haan.", HindiRomanization.render(edit.hindiDraft!!, receipt.originalOcr))
        assertTrue(TranslationQualityPolicy.isUsable(receipt.originalOcr, edit.translated!!, receipt.targetLanguage, edit.hindiDraft))
        PersonalMemoryOverlayPolicy.validateEdit(receipt, edit, nativeHindi)
    }

    @Test fun romanAndHindiEditsMustStillBeOneVerifiedPair() {
        val hello = receipt.copy(originalOcr = "Hello.", originalTranslation = "namaste.")
        val actual = PersonalMemoryEditorInputPolicy.edit(hello, "नमस्ते।",
            PersonalMemoryEditorValues("Hello.", "namaste, dost.", "नमस्ते, दोस्त।"))
        assertEquals("namaste, dost.", actual.translated)
        assertEquals("नमस्ते, दोस्त।", actual.hindiDraft)
        PersonalMemoryOverlayPolicy.validateEdit(hello, actual, "नमस्ते।")
        val wrong = actual.copy(hindiDraft = "शुक्रिया।")
        assertTrue(runCatching { PersonalMemoryOverlayPolicy.validateEdit(hello, wrong, "नमस्ते।") }.isFailure)
    }

    @Test fun missingNativeHindiStaysMissingRatherThanManufacturingEvidence() {
        val initial = PersonalMemoryEditorInputPolicy.initial(receipt, null, null)
        assertEquals("", initial.hindiDraft)
        assertEquals(MemoryCorrectionEdit(), PersonalMemoryEditorInputPolicy.edit(receipt, null, initial))
    }

    @Test fun wrongHindiOnlyEditCannotBorrowTheSavedNativeProof() {
        val initial = PersonalMemoryEditorInputPolicy.initial(receipt, nativeHindi, null)
        val edit = PersonalMemoryEditorInputPolicy.edit(receipt, nativeHindi, initial.copy(hindiDraft = "शुक्रिया।"))
        assertEquals("haan.", edit.translated)
        assertEquals("शुक्रिया।", edit.hindiDraft)
        assertTrue(runCatching { PersonalMemoryOverlayPolicy.validateEdit(receipt, edit, nativeHindi) }.isFailure)
    }

    @Test fun englishOrRomanTextIsNotAcceptedAsHindiEvidence() {
        val initial = PersonalMemoryEditorInputPolicy.initial(receipt, nativeHindi, null)
        for (draft in listOf("Yes.", "haan.")) {
            val edit = PersonalMemoryEditorInputPolicy.edit(receipt, nativeHindi, initial.copy(hindiDraft = draft))
            assertEquals(draft, edit.hindiDraft)
            assertEquals("haan.", edit.translated)
            assertTrue(runCatching { PersonalMemoryOverlayPolicy.validateEdit(receipt, edit, nativeHindi) }.isFailure)
        }
    }

    @Test fun changedRomanDoesNotSilentlyInheritUnrelatedNativeHindiEvidence() {
        val initial = PersonalMemoryEditorInputPolicy.initial(receipt, nativeHindi, null)
        val edit = PersonalMemoryEditorInputPolicy.edit(receipt, nativeHindi, initial.copy(translated = "shukriya."))
        assertEquals(nativeHindi, edit.hindiDraft)
        assertTrue(runCatching { PersonalMemoryOverlayPolicy.validateEdit(receipt, edit, nativeHindi) }.isFailure)
        val withoutEvidence = PersonalMemoryEditorInputPolicy.initial(receipt, nativeHindi, MemoryCorrectionEdit(translated = "shukriya."))
        assertEquals("", withoutEvidence.hindiDraft)
    }

    @Test fun clearingHindiEvidenceDoesNotReuseAProofNeededByTheOriginalRomanTranslation() {
        val fire = receipt.copy(originalOcr = "Fire!", originalTranslation = "aag!")
        assertTrue(TranslationQualityPolicy.isUsable("Fire!", "aag!", "hi-latn", "आग!"))
        val initial = PersonalMemoryEditorInputPolicy.initial(fire, "आग!", null)
        val edit = PersonalMemoryEditorInputPolicy.edit(fire, "आग!", initial.copy(hindiDraft = ""))
        assertEquals("aag!", edit.translated)
        assertNull(edit.hindiDraft)
        assertTrue(runCatching { PersonalMemoryOverlayPolicy.validateEdit(fire, edit, "आग!") }.isFailure)
    }

    @Test fun reopeningExistingCorrectionKeepsItsOwnPairAndDoesNotRegisterANewChange() {
        val existing = MemoryCorrectionEdit(translated = "haan.", hindiDraft = "हां।")
        val initial = PersonalMemoryEditorInputPolicy.initial(receipt, nativeHindi, existing)
        assertEquals("हां।", initial.hindiDraft)
        assertFalse(PersonalMemoryEditorInputPolicy.changed(initial, initial, receipt.targetLanguage))
        assertEquals(existing, PersonalMemoryEditorInputPolicy.edit(receipt, nativeHindi, initial))
    }

    @Test fun ocrOnlyCorrectionUsesActualNativeHindiWithoutAddingATranslatedCorrection() {
        val initial = PersonalMemoryEditorInputPolicy.initial(receipt, nativeHindi, null)
        val edit = PersonalMemoryEditorInputPolicy.edit(receipt, nativeHindi, initial.copy(ocr = "YES."))
        assertEquals(MemoryCorrectionEdit(correctedOcr = "YES."), edit)
        PersonalMemoryOverlayPolicy.validateEdit(receipt, edit, nativeHindi)
    }

    @Test fun returningToActualNativeHindiCreatesAnOriginalValueRevision() {
        val initial = PersonalMemoryEditorInputPolicy.initial(receipt, nativeHindi, MemoryCorrectionEdit(translated = "haan.", hindiDraft = "हां।"))
        val restored = initial.copy(hindiDraft = nativeHindi)
        assertTrue(PersonalMemoryEditorInputPolicy.changed(initial, restored, receipt.targetLanguage))
        assertEquals(MemoryCorrectionEdit(), PersonalMemoryEditorInputPolicy.edit(receipt, nativeHindi, restored))
        PersonalMemoryOverlayPolicy.validateEdit(receipt, MemoryCorrectionEdit(), nativeHindi)
    }

    @Test fun nonRomanTargetDoesNotPublishOrCompareAHindiDraftField() {
        val hindi = receipt.copy(targetLanguage = "hi", originalOcr = "Hello.", originalTranslation = "नमस्ते।")
        val initial = PersonalMemoryEditorInputPolicy.initial(hindi, "not evidence", null)
        assertEquals("", initial.hindiDraft)
        assertFalse(PersonalMemoryEditorInputPolicy.changed(initial, initial.copy(hindiDraft = "made up"), hindi.targetLanguage))
        val edit = PersonalMemoryEditorInputPolicy.edit(hindi, null, initial.copy(translated = "नमस्ते, दोस्त।", hindiDraft = "made up"))
        assertEquals(MemoryCorrectionEdit(translated = "नमस्ते, दोस्त।"), edit)
        PersonalMemoryOverlayPolicy.validateEdit(hindi, edit, null)
    }
}
