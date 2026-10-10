package com.mangalens.core.translation

import com.mangalens.core.reader.*
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** UNRUN. Native metadata is never made into a personal/editor receipt. */
class NativeIndexedSavedTextSearchServiceTest {
    @Test fun neverInspectedColdNativeTextIsAHintUntilExplicitWholeWarming() = fixture { f ->
        val hints = NativeMetadataLexicalIndex(f.native).find("Hello")
        assertEquals(1, hints.hints.size)
        assertTrue(f.native.get(f.task.id)!!.validationPending)
        assertTrue(f.service.search("Hello")!!.rows.isEmpty())
        assertFalse(f.warmer.pass())
        val found = f.service.search("Hello")!!
        assertEquals("Hello.", found.rows.single().text); assertTrue(found.tryDeliver { true })
        assertTrue(f.native.get(f.task.id)!!.validationPending)
        assertFalse(File(f.base.root, "reader_memory").exists())
        assertNull(f.native.prepareMemoryPublication(found.rows.single().receipt, 0))
    }
    @Test fun nativeOcrAndTranslationKindsStaySeparateWithoutPersonalWrites() = fixture { f ->
        f.warmer.pass()
        assertEquals(MemorySearchKind.OCR, f.service.search("Hello")!!.rows.single().kind)
        assertEquals(MemorySearchKind.TRANSLATION, f.service.search("नमस्ते")!!.rows.single().kind)
        assertFalse(File(f.base.root, "reader_memory").exists())
    }
    @Test fun warmingDoesNotWriteNativeJournalOrClearOrdinaryColdFlags() = fixture { f ->
        val before = f.base.journal(f.task.id).readBytes()
        f.warmer.pass()
        assertArrayEquals(before, f.base.journal(f.task.id).readBytes())
        assertTrue(f.native.get(f.task.id)!!.validationPending)
        val warm = f.warmer.receipt(f.task)!!
        assertNotNull(f.native.prepareWarmedNativeReadPage(warm, 0))
        assertNull(f.native.prepareMemoryPublication(ReaderTranslationPresentation.receipt(f.task), 0))
    }
    @Test fun heldNativeResultsAndOpenRetireAfterSourceInodeReplacement() = fixture { f ->
        f.warmer.pass(); val found = f.service.search("Hello")!!
        val pending = f.service.prepareOpen(found, found.rows.single().id)!!
        replaceIdentically(f.base.source)
        assertFalse(found.tryDeliver { true }); assertFalse(pending.tryDeliver { true })
        assertNull(f.service.prepareOpen(found, found.rows.single().id))
    }
    @Test fun heldNativeResultsRetireAfterSurfaceInodeReplacement() = fixture { f ->
        f.warmer.pass(); val found = f.service.search("Hello")!!
        replaceIdentically(File(f.task.pages.single().cleanedPath!!))
        assertFalse(found.tryDeliver { true }); assertTrue(f.service.search("Hello")!!.rows.isEmpty())
    }
    @Test fun g1ProofCannotDisplayOrOpenAfterActualNativeG2Replacement() = fixture { f ->
        f.warmer.pass(); val found = f.service.search("Hello")!!
        f.base.completed(nativeStore = f.native, owner = "reader:G2", forceReprocess = true)
        assertFalse(found.tryDeliver { true }); assertNull(f.service.prepareOpen(found, found.rows.single().id))
        assertTrue(f.service.search("Hello")!!.rows.isEmpty())
    }
    @Test fun readerScopeCannotProjectOutAnyOtherCapturedPage() = fixture { f ->
        f.warmer.pass(); val found = f.service.search("Hello")!!
        val extra = File(f.base.sources, "extra.jpg").apply { writeText("original extra bytes") }
        f.chapter = f.chapter.copy(pages = f.chapter.pages + ChapterPage(7, "local:extra", extra.path))
        assertNull(f.service.prepareOpen(found, found.rows.single().id))
        assertTrue(f.service.search("Hello")!!.rows.isEmpty())
    }
    @Test fun typedNativeOpenCarriesSparseActualIndexAndIsSingleUse() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { base ->
            val native = base.store()
            val source = File(base.sources, "sparse.jpg").apply { writeText("original sparse source") }
            val chapter = SavedChapter("b".repeat(32), "Sparse", "local:sparse", listOf(ChapterPage(7, "local:7", source.path)))
            val raw = completeChapter(base, native, chapter)
            val warmer = warmer(native)
            assertFalse(warmer.pass())
            val service = NativeIndexedSavedTextSearchService(native, warmer) { SavedTextChapterScope.inspect(chapter, base.sources) }
            val found = service.search("Hello")!!; val row = found.rows.single()
            val pending = service.prepareOpen(found, row.id)!!
            var accepted: SavedTextReaderSelection? = null
            assertTrue(pending.tryDeliver { accepted = it; true }); assertFalse(pending.tryDeliver { true })
            assertEquals(7, accepted!!.pageIndex); assertEquals(0, accepted!!.pageOrdinal)
            assertSame(raw, accepted!!.warmedTaskForRead())
        }
    }
    @Test fun sixtyFivePageNativeChapterReachesItsLastSparsePageThroughWarming() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { base ->
            val native = base.store()
            val pages = (0 until 65).map { ordinal -> val file = File(base.sources, "long-$ordinal.jpg").apply { writeText("original long source $ordinal") }
                ChapterPage(7 + ordinal * 3, "local:$ordinal", file.path) }
            val chapter = SavedChapter("b".repeat(32), "Long", "local:long", pages)
            completeChapter(base, native, chapter, matchingIndex = pages.last().index)
            val warmer = warmer(native); assertFalse(warmer.pass())
            val service = NativeIndexedSavedTextSearchService(native, warmer) { SavedTextChapterScope.inspect(chapter, base.sources) }
            val found = service.search("Hello")!!; val row = found.rows.single()
            assertEquals(64, row.pageOrdinal); assertEquals(199, row.proof.page.index)
            var selection: SavedTextReaderSelection? = null
            assertTrue(service.prepareOpen(found, row.id)!!.tryDeliver { selection = it; true })
            assertEquals(199, selection!!.pageIndex); assertEquals(64, selection!!.pageOrdinal)
        }
    }
    @Test fun actualSmallPassBudgetsAccumulateAndOnlyPublishAfterWholeTaskCompletion() = fixture { f ->
        var received = 0L
        while (true) {
            val budget = NativeIndexPassBudget(5); val more = f.warmer.pass(budget); received += budget.used
            assertTrue(budget.used <= 5)
            if (!more) break
            assertTrue(f.service.search("Hello")!!.rows.isEmpty())
        }
        assertEquals(f.base.source.length() + File(f.task.pages.single().cleanedPath!!).length(), received)
        assertEquals(received, f.warmer.state.value.receivedBytes)
        assertEquals(1, f.warmer.state.value.verifiedTasks)
    }
    @Test fun failedWholeSourceIsBlockedAndNeverShowsEvenWhenMatchingOutputRemains() = fixture { f ->
        f.base.source.appendText("changed source")
        val budget = NativeIndexPassBudget(); assertFalse(f.warmer.pass(budget))
        assertEquals(f.base.source.length(), budget.used)
        assertEquals(1, f.warmer.state.value.blockedTasks)
        assertTrue(f.service.search("Hello")!!.rows.isEmpty())
    }
    @Test fun processRestartDoesNotDecodeOrBorrowAFormerWarmReadProof() = fixture { f ->
        f.warmer.pass(); assertEquals(1, f.service.search("Hello")!!.rows.size)
        val cold = f.base.store(); val restarted = warmer(cold)
        val service = NativeIndexedSavedTextSearchService(cold, restarted) { SavedTextChapterScope.inspect(f.chapter, f.base.sources) }
        assertTrue(service.search("Hello")!!.rows.isEmpty()); assertTrue(cold.get(f.task.id)!!.validationPending)
    }
    @Test fun forgedIdsAndKindsCannotManufactureANativeOpen() = fixture { f ->
        f.warmer.pass(); val found = f.service.search("Hello")!!
        assertNull(f.service.prepareOpen(found, "native:forged"))
        assertNull(f.service.search("")); assertNull(f.service.search("x".repeat(257))); assertNull(f.service.search("Hello\u0000"))
        assertTrue(f.service.search("unmatched")!!.rows.isEmpty())
    }
    @Test fun newExplicitSeriesLinkRetiresHeldNativeRowsThatCapturedNoAssociation() = fixture { f ->
        f.warmer.pass(); val held = f.service.search("Hello")!!
        val series = f.memory.createSeries("Current explicit link")
        f.memory.associateChapter(f.chapter.id, series.id, 1)
        assertFalse(held.tryDeliver { true }); assertNull(f.service.prepareOpen(held, held.rows.single().id))
        assertEquals(1, f.service.search("Hello")!!.rows.size)
    }
    @Test fun removedProfileRetiresHeldNativeReadAndItsTypedReaderProof() = fixture { f ->
        val series = f.memory.createSeries("Explicit saved profile")
        f.memory.associateChapter(f.chapter.id, series.id, 1)
        f.warmer.pass(); val found = f.service.search("Hello")!!
        var selected: SavedTextReaderSelection? = null
        assertTrue(f.service.prepareOpen(found, found.rows.single().id)!!.tryDeliver { selected = it; true })
        f.memory.removeSeries(series.id)
        assertFalse(found.tryDeliver { true }); assertNull(selected!!.warmedTaskForRead())
        assertTrue(f.service.search("Hello")!!.rows.isEmpty())
    }
    @Test fun identicalProfileInodeReplacementRetiresHeldNativeOpen() = fixture { f ->
        val series = f.memory.createSeries("Explicit profile incarnation")
        f.memory.associateChapter(f.chapter.id, series.id, 1)
        f.warmer.pass(); val held = f.service.search("Hello")!!
        replaceIdentically(File(f.base.root, "reader_memory/series/${series.id}.json"))
        assertFalse(held.tryDeliver { true }); assertNull(f.service.prepareOpen(held, held.rows.single().id))
    }
    @Test fun canceledWarmPassReportsActuallyReceivedBytesAndResumesWithoutDuplicateCharge() = fixture { f ->
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        var first = true
        val heldWarmer = NativeIndexWarmer(f.native, decodeDimensions = { bytes ->
            if (first) { first = false; entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            if (bytes.toString(Charsets.UTF_8).startsWith("valid surface:")) 250 to 333 else 1001 to 1009
        }, openHandle = ::TestNativeIndexReadHandle)
        val job = CoroutineScope(Dispatchers.IO).launch { heldWarmer.pass() }
        check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); job.cancel(); release.countDown(); job.join()
        assertEquals(f.base.source.length(), heldWarmer.state.value.receivedBytes)
        assertNull(heldWarmer.receipt(f.task))
        assertFalse(heldWarmer.pass())
        assertEquals(f.base.source.length() + File(f.task.pages.single().cleanedPath!!).length(), heldWarmer.state.value.receivedBytes)
    }
    private class Fixture : AutoCloseable {
        val base = NativeMemoryPublicationAdapterTest.Fixture()
        val written = base.store()
        init { base.completed(nativeStore = written) }
        val native = base.store()
        val task = native.nativeIndexTasks().single()
        var chapter = base.chapter
        val warmer = warmer(native)
        val memory = SeriesMemoryStore(base.root)
        val service = NativeIndexedSavedTextSearchService(native, warmer, memory) { SavedTextChapterScope.inspect(chapter, base.sources) }
        override fun close() = base.close()
    }
    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking { Fixture().use { block(it) } }
    private fun replaceIdentically(file: File) {
        val before = Files.getLastModifiedTime(file.toPath())
        val replacement = File(file.parentFile, "replacement.pending").apply { writeBytes(file.readBytes()) }
        Files.setLastModifiedTime(replacement.toPath(), before)
        Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    companion object {
        private fun warmer(native: ChapterTranslationStore) = NativeIndexWarmer(native,
            decodeDimensions = { bytes -> if (bytes.toString(Charsets.UTF_8).startsWith("valid surface:")) 250 to 333 else 1001 to 1009 },
            openHandle = ::TestNativeIndexReadHandle)
        private fun completeChapter(base: NativeMemoryPublicationAdapterTest.Fixture, native: ChapterTranslationStore,
            chapter: SavedChapter, matchingIndex: Int? = null): ChapterTranslationTask {
            val start = native.start(chapter, base.config, ownerRequestId = "reader:index-test")
            native.markRunning(start.id, start.generation)
            for (source in chapter.pages) {
                val page = native.beginPage(start.id, start.generation, source.index)!!
                val output = native.createOutputFile(start.id, start.generation, source.index).apply { writeText("valid surface: ${source.index}") }
                assertTrue(native.commitPage(start.id, start.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                    cleanedPath = output.path, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 250, imageHeight = 333,
                    originalWidth = 1001, originalHeight = 1009, lettering = listOf(SavedMangaLettering(
                        if (matchingIndex == null || source.index == matchingIndex) "Hello." else "Other.", "नमस्ते।", 0, 0, 100, 100,
                        "sans-serif", 0, -16777216, 20f, "ALIGN_CENTER", 1, 2, 23, 47,
                        originalSourceBounds = OriginalMangaGeometry.fromSampled(1, 2, 23, 47, 250, 333, 1001, 1009))))))
            }
            return native.finish(start.id, start.generation)!!
        }
    }
}
