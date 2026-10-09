package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.attribute.FileTime

class PersonalMemoryOverlayPolicyTest {
    private val bounds = SavedOriginalSourceBounds(left = 4, top = 6, right = 93, bottom = 143)
    private val letter = SavedMangaLettering("Hello.", "नमस्ते।", 0, 0, 100, 100, "sans-serif", 0, -1, 20f, "ALIGN_CENTER", 1, 2, 23, 47, originalSourceBounds = bounds)
    private val page = ChapterTranslationPage(0, "/private/chapters/0.png", "a".repeat(64), ChapterTranslationPageStatus.COMPLETED,
        "/private/chapter_translations/task/0.png", "b".repeat(64), 250, 333, listOf(letter), originalWidth = 1001, originalHeight = 1009)
    private val task = ChapterTranslationTask("task", "G1", "chapter", "Fixture", ChapterTranslationConfig("hi"), listOf(page), ChapterTranslationStatus.COMPLETED, 1, 1, ownerRequestId = "reader")
    private val stamp = NativeMemoryFileStamp("/fixture", "inode", 1, FileTime.fromMillis(1))
    private val proof = NativeMemoryPageProof(task, page, stamp, stamp)
    private val receipt = MemoryPublicationReceipt(MemorySourceProof("chapter", 0, page.sourcePath!!, page.sourceSha256!!, 1001, 1009,
        MemoryRegionBounds(4, 6, 93, 143)), "task", "G1", "hi", "config", "Hello.", "नमस्ते।", page.cleanedPath, page.cleanedSha256,
        nativeAuthorityVersion = 1, ownerRequestId = "reader", presentationEpoch = 1, associationRevision = 0)
    private fun chapter(captured: MemoryPublicationReceipt = receipt, edit: MemoryCorrectionEdit = MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) =
        MemoryChapterSnapshot("chapter", null, listOf(MemoryIndexedBubble(captured,
            MemoryCorrection(captured, listOf(MemoryCorrectionRevision(1, edit, 1))))), false)

    @Test fun validatedPersonalLetteringKeepsEveryNativeCoordinateAndOriginalValue() {
        val personal = PersonalMemoryOverlayPolicy.project(proof, "config", chapter()).getValue(0)
        assertEquals(letter, personal.original)
        assertEquals("नमस्ते, मित्र।", personal.personal.translated)
        assertEquals(letter.copy(translated = "नमस्ते, मित्र।"), personal.personal)
        assertEquals("नमस्ते।", proof.page.lettering.single().translated)
    }
    @Test fun differentNativeOwnerGenerationSourceOutputOrConfigurationCannotProject() {
        for (captured in listOf(receipt.copy(ownerRequestId = "reader:G2"), receipt.copy(generation = "G2"),
            receipt.copy(outputSha256 = "c".repeat(64)), receipt.copy(configurationIdentity = "other"),
            receipt.copy(source = receipt.source.copy(sourceSha256 = "c".repeat(64))),
            receipt.copy(source = receipt.source.copy(bounds = MemoryRegionBounds(5, 6, 93, 143))),
            receipt.copy(associationRevision = 1), receipt.copy(nativeAuthorityVersion = 0)))
            assertTrue(PersonalMemoryOverlayPolicy.project(proof, "config", chapter(captured)).isEmpty())
    }
    @Test fun personalOverlayDoesNotRequireAnOldProcessPresentationEpoch() {
        assertFalse(PersonalMemoryOverlayPolicy.project(proof, "config", chapter(receipt.copy(presentationEpoch = 999))).isEmpty())
    }
    @Test fun wrongTargetUserTextIsNeitherProjectedNorAcceptedBySave() {
        val wrong = MemoryCorrectionEdit(translated = "Hello, friend.")
        assertTrue(PersonalMemoryOverlayPolicy.project(proof, "config", chapter(edit = wrong)).isEmpty())
        assertTrue(runCatching { PersonalMemoryOverlayPolicy.validateEdit(receipt, wrong, null) }.isFailure)
    }
    @Test fun removedOrRestoredOriginalCorrectionNeverRevivesAnEarlierPersonalOverlay() {
        assertTrue(PersonalMemoryOverlayPolicy.project(proof, "config", chapter().copy(removed = true)).isEmpty())
        assertTrue(PersonalMemoryOverlayPolicy.project(proof, "config", chapter(edit = MemoryCorrectionEdit())).isEmpty())
        assertTrue(PersonalMemoryOverlayPolicy.project(proof, "config", chapter().copy(bubbles = listOf(MemoryIndexedBubble(receipt, editRevision = 7)))).isEmpty())
    }
    @Test fun legacySampledCoordinatesCannotBePromotedToOriginalProof() {
        val legacy = page.copy(originalWidth = null, originalHeight = null, lettering = listOf(letter.copy(originalSourceBounds = null)))
        assertTrue(PersonalMemoryOverlayPolicy.project(proof.copy(page = legacy), "config", chapter()).isEmpty())
    }
    @Test fun explicitChapterOrderAllowsZeroAndUnknownWhileRejectingInvalidOrUnboundedInput() {
        assertNull(parseMemoryChapterOrdinal("")); assertNull(parseMemoryChapterOrdinal("  "))
        assertEquals(0, parseMemoryChapterOrdinal("0")); assertEquals(1_000_000, parseMemoryChapterOrdinal("1000000"))
        for (invalid in listOf("-1", "1000001", "1.5", "1e2", "2147483648", "earlier")) assertTrue(runCatching { parseMemoryChapterOrdinal(invalid) }.isFailure)
    }
}
