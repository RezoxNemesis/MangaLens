package com.mangalens.core.translation.memory

import com.mangalens.core.translation.ChapterTranslationPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SeriesMemoryCanonicalPageTest {
    @Test fun actualZeroBasedFirstPageCanBeIndexedCorrectedSearchedAndUsedAsTermOrigin() = runBlocking {
        val root = Files.createTempDirectory("memory-canonical-first-page").toFile()
        try {
            val source = File(root, "chapters/source.jpg").apply { parentFile!!.mkdirs(); writeText("owned original source") }
            val page = ChapterTranslationPage(0, source.absolutePath, SeriesMemoryStore.sha256(source))
            val receipt = MemoryPublicationReceipt(MemorySourceProof("chapter", page.index, page.sourcePath!!, page.sourceSha256!!,
                720, 1200, MemoryRegionBounds(20, 20, 400, 160)), "task", "generation", "hi", "captured-config", "Jin, wait.", seriesId = "series")
            val store = SeriesMemoryStore(root, MemoryPublicationFence { expected, commit -> check(expected == receipt); commit() })
            store.createSeries("Owned series", "series"); store.associateChapter("chapter", "series", 0)
            store.indexBubble(receipt, receipt)
            store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "जिन, रुको।"))
            assertEquals(0, store.search("Jin").hits.single().source.pageIndex)
            store.upsertTerm("series", SeriesGlossaryTerm("jin", "Jin", "जिन", "hi", origin = MemoryLocation("chapter", page.index)))
            store.associateChapter("later", "series", 1)
            assertEquals(mapOf("Jin" to "जिन"), store.relevant(MemoryRetrievalRequest("later", 0, "Jin, wait.", "hi", "captured-config")).glossary)
        } finally { root.deleteRecursively() }
    }

    @Test fun negativeCanonicalPageIsRejectedAndExistingPositiveIndexesAreNotRenumbered() {
        val proof = MemorySourceProof("chapter", 1, "/private/source.jpg", "a".repeat(64), 720, 1200, MemoryRegionBounds(20, 20, 400, 160))
        proof.validate(); assertEquals(1, proof.pageIndex)
        try { proof.copy(pageIndex = -1).validate(); fail("Negative source page") } catch (_: IllegalArgumentException) { }
        try { MemoryLocation("chapter", -1).validate(); fail("Negative term origin page") } catch (_: IllegalArgumentException) { }
    }
}
