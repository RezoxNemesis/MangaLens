package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import com.mangalens.ui.reader.TranslationOverlay
import com.mangalens.ui.reader.applyPersonalReaderOverlays
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.attribute.FileTime

/** Authored UNRUN. Real publication/projection types; no bitmap or model result is fabricated. */
class PersonalSfxProjectionTest {
    private val native = SavedMangaLettering("Bang!", "धम!", 5, 10, 50, 60, "sans-serif", 0, -1, 20f, "ALIGN_CENTER", 5, 10, 50, 60,
        originalSourceBounds = SavedOriginalSourceBounds(left = 10, top = 20, right = 100, bottom = 120))
    private val page = ChapterTranslationPage(37, "/source", "a".repeat(64), ChapterTranslationPageStatus.COMPLETED,
        "/output", "b".repeat(64), 100, 200, listOf(native), originalWidth = 200, originalHeight = 400)
    private val task = ChapterTranslationTask("task", "G1", "chapter", "Fixture", ChapterTranslationConfig("hi"), listOf(page), ChapterTranslationStatus.COMPLETED, 1, 1)
    private val stamp = NativeMemoryFileStamp("/fixture", "inode", 1, FileTime.fromMillis(1))
    private val proof = NativeMemoryPageProof(task, page, stamp, stamp)
    private val receipt = MemoryPublicationReceipt(MemorySourceProof("chapter", 37, "/source", "a".repeat(64), 200, 400, MemoryRegionBounds(10, 20, 100, 120)),
        "task", "G1", "hi", "config", "Bang!", "धम!", "/output", "b".repeat(64), nativeAuthorityVersion = 1, presentationEpoch = 1, associationRevision = 0)
    private val selection = MemoryRegionPresentation(MemoryUserRegionKind.SFX, MemorySfxPresentation.KEEP_ORIGINAL)
    private fun chapter(edit: MemoryCorrectionEdit, captured: MemoryPublicationReceipt = receipt) = MemoryChapterSnapshot("chapter", null,
        listOf(MemoryIndexedBubble(captured, MemoryCorrection(captured, listOf(MemoryCorrectionRevision(1, edit, 1))))), false)
    @Test fun manualPolicyProjectsWithItsActualNativeIndexAndLeavesNativeTextUntouched() {
        val personal = PersonalMemoryOverlayPolicy.project(proof, "config", chapter(MemoryCorrectionEdit(regionPresentation = selection))).getValue(0)
        assertEquals(selection, personal.regionPresentation); assertEquals(37, personal.pageIndex); assertEquals(native, personal.original); assertEquals(native, personal.personal)
        assertEquals(listOf(native), proof.page.lettering)
    }
    @Test fun nativeGenerationOrSourceChangeRetiresManualPresentationToo() {
        for (other in listOf(receipt.copy(generation = "G2"), receipt.copy(outputSha256 = "c".repeat(64)),
            receipt.copy(source = receipt.source.copy(sourceSha256 = "c".repeat(64))), receipt.copy(associationRevision = 1)))
            assertTrue(PersonalMemoryOverlayPolicy.project(proof, "config", chapter(MemoryCorrectionEdit(regionPresentation = selection), other)).isEmpty())
    }
    @Test fun anOrdinaryOldTextCorrectionRetainsItsOriginalPersonalDataEquality() {
        val edit = MemoryCorrectionEdit(translated = "धड़ाम!")
        val personal = PersonalMemoryOverlayPolicy.project(proof, "config", chapter(edit)).getValue(0)
        assertEquals(PersonalMangaLettering(native, native.copy(translated = "धड़ाम!"), 1), personal)
    }
    @Test fun revertingToAnEmptyPersonalEditCannotKeepAnEarlierSfxPolicy() {
        assertTrue(PersonalMemoryOverlayPolicy.project(proof, "config", chapter(MemoryCorrectionEdit())).isEmpty())
    }
    @Test fun readerProjectionRetainsIndicesAndCannotAttachAPolicyToDifferentNativeLettering() {
        val personal = PersonalMemoryOverlayPolicy.project(proof, "config", chapter(MemoryCorrectionEdit(regionPresentation = selection)))
        val overlay = TranslationOverlay(OcrRegion("Bang!", 5, 10, 50, 60), native.translated, imageWidthPx = 100, imageHeightPx = 200, lettering = native)
        val accepted = applyPersonalReaderOverlays(listOf(overlay), personal).single()
        assertEquals(0, accepted.personalRegion!!.nativeIndex); assertSame(personal[0], accepted.personalRegion!!.personal)
        val changed = overlay.copy(lettering = native.copy(source = "Crash!"))
        assertEquals(changed, applyPersonalReaderOverlays(listOf(changed), personal).single())
    }
    @Test fun manualClassificationDoesNotSupplyFabricatedHindiEvidence() {
        val bad = MemoryCorrectionEdit(translated = "Bang!", regionPresentation = selection)
        assertTrue(runCatching { PersonalMemoryOverlayPolicy.validateEdit(receipt, bad, null) }.isFailure)
        assertTrue(PersonalMemoryOverlayPolicy.project(proof, "config", chapter(bad)).isEmpty())
    }
}
