package com.mangalens.core.reader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ChapterCatalogBudgetTest {
    @Test fun smallFullSeedUsesTheRealEncoderWithoutWritingARecord() = fixture { root, library ->
        library.checkMetadataBudget(seed("Chapter"))
        assertTrue(File(root, "chapter_library").listFiles().orEmpty().isEmpty())
    }
    @Test fun unicodeByteExpansionCannotFitByCharacterCountAlone() = fixture { _, library ->
        rejected { library.checkMetadataBudget(seed("界".repeat(700_000))) }
    }
    @Test fun actualJsonEscapingExpansionIsIncludedInTheHardByteCap() = fixture { _, library ->
        rejected { library.checkMetadataBudget(seed("\"".repeat(1_100_000))) }
    }
    @Test fun aPendingSeedAtTheExactByteCapReservesFullPromotionSuccessBeforeAnyWrite() = fixture { root, library ->
        val original = seed("").copy(updatedAt = 1, addedAt = 1)
        val overhead = library.checkMetadataBudget(original)
        val boundary = original.copy(title = "x".repeat(2_000_000 - overhead))
        assertEquals(2_000_000, library.checkMetadataBudget(boundary))
        val reservation = ChapterAcquisitionBudgetPage(1, original.pages.single().sourceUrl,
            "1_" + "a".repeat(64) + ".img", com.mangalens.core.acquisition.ChapterImagePromotion.COMMERCIAL_LINK)
        rejected { library.checkMetadataBudget(boundary, listOf(reservation)) }
        assertEquals(ChapterPageAcquisitionPolicy.PENDING, boundary.pages.single().error)
        assertNull(boundary.pages.single().localPath); assertNull(boundary.pages.single().promotionHint)
        assertTrue(File(root, "chapter_library").listFiles().orEmpty().isEmpty())
    }
    @Test fun fixedFailureExpansionIsIncludedInTheLargestEncodedSuccessorRow() = fixture { _, library ->
        val original = seed("Chapter")
        // A short safe budget filename makes the fixed failure row the larger alternative.
        val reservation = ChapterAcquisitionBudgetPage(1, original.pages.single().sourceUrl, "x.img")
        val failure = original.copy(pages = listOf(ChapterPageAcquisitionPolicy.failure(1, reservation.sourceUrl)))
        assertEquals(library.checkMetadataBudget(failure), library.checkMetadataBudget(original, listOf(reservation)))
    }
    @Test fun aForeignOrdinalOrSourceCannotReserveAnotherCatalogRow() = fixture { _, library ->
        try {
            library.checkMetadataBudget(seed("Chapter"), listOf(ChapterAcquisitionBudgetPage(2, "https://example.org/foreign.png", "x.img")))
            fail("Foreign reservation was accepted")
        } catch (_: IllegalArgumentException) { }
    }
    private fun seed(title: String) = SavedChapter("a".repeat(32), title, "https://example.org/chapter",
        listOf(ChapterPage(1, "https://example.org/page.png", error = ChapterPageAcquisitionPolicy.PENDING)))
    private fun fixture(action: (File, ChapterLibrary) -> Unit) {
        val root = Files.createTempDirectory("chapter-catalog-budget-").toFile()
        val io = object : ChapterLibraryJournalIo {
            override fun read(file: File): java.io.InputStream = error("Preflight cannot read a journal")
            override fun write(file: File, bytes: ByteArray) { error("Preflight cannot write a journal") }
            override fun delete(file: File) { error("Preflight cannot delete a journal") }
        }
        try { action(root, ChapterLibrary(root, io)) } finally { root.deleteRecursively() }
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Oversized encoded chapter seed was accepted") }
        catch (failure: IllegalArgumentException) { assertTrue(failure.message.orEmpty().contains("metadata")) }
    }
}
