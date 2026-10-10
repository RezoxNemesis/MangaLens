package com.mangalens.core.translation

import com.mangalens.core.reader.*
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Actual saved native fixture controls authored first; every test is currently UNRUN. */
class NativeCrossChapterSavedTextSearchServiceTest {
    @Test fun twoActualNativeChaptersAreAcceptedOnlyThroughTheirOwnFullReceipts() = fixture { f, service ->
        val found = service.search("Hello")!!
        assertEquals(setOf(f.first.id, f.second.id), found.rows.map { it.chapterId }.toSet())
        assertTrue(found.tryDeliver { true })
        for (row in found.rows) {
            var selected: SavedTextReaderSelection? = null
            assertTrue(service.prepareOpen(found, row.id)!!.tryDeliver { selected = it; true })
            assertEquals(row.chapterId, selected!!.chapterId)
            assertEquals(row.row.receipt, selected!!.receipt)
            assertEquals(row.row.hit.source.pageIndex, selected!!.pageIndex)
        }
    }

    @Test fun personalAndNativeTextKindsRemainSeparateAcrossChapters() = fixture { f, service ->
        val receipt = f.adapter.memory.inspectChapter(f.second.id).bubbles.single().receipt
        f.reader.activate(ReaderTranslationPresentation.receipt(f.native.latest(f.second.id)!!))
        val editor = f.adapter.openEditor(f.reader.current()!!, 7, 0)!!
        assertEquals(receipt.bubbleId, editor.receipt.bubbleId)
        f.adapter.correct(editor, 0, MemoryCorrectionEdit(correctedOcr = "Hello, friend.", translated = "नमस्ते, मित्र।"))
        val found = service.search("Hello", forceRefresh = true)!!
        assertEquals(3, found.rows.size)
        assertEquals(setOf(MemorySearchKind.OCR, MemorySearchKind.CORRECTED_OCR), found.rows.map { it.row.hit.kind }.toSet())
        assertEquals(1, found.rows.single { it.row.hit.kind == MemorySearchKind.CORRECTED_OCR }.row.hit.revision)
    }

    @Test fun anIndexRowCannotPublishTextAfterTheRealNativeGenerationChanges() = fixture { f, service ->
        val first = service.search("Hello")!!
        assertTrue(first.rows.any { it.chapterId == f.first.id })
        f.fixture.completed(nativeStore = f.native, owner = "reader:G2", forceReprocess = true)
        var delivered = false
        assertFalse(first.tryDeliver { delivered = true; true }); assertFalse(delivered)
        val fresh = service.search("Hello")!!
        assertTrue(fresh.rows.none { it.chapterId == f.first.id })
        assertTrue(fresh.incomplete)
        assertNull(service.prepareOpen(first, first.rows.first { it.chapterId == f.first.id }.id))
    }

    @Test fun aCurrentMatchingBubbleCannotHideAddedOrRemovedWholeReaderPages() = fixture { f, service ->
        val old = service.search("Hello")!!
        val extra = File(f.fixture.sources, "extra.jpg").apply { writeText("extra source bytes") }
        f.current[f.first.id] = f.first.copy(pages = f.first.pages + ChapterPage(9, "local:extra", extra.path))
        val changed = service.search("Hello")!!
        assertTrue(changed.rows.none { it.chapterId == f.first.id }); assertTrue(changed.incomplete)
        assertNull(service.prepareOpen(old, old.rows.first { it.chapterId == f.first.id }.id))
        f.current[f.first.id] = f.first.copy(pages = emptyList())
        assertTrue(service.search("Hello")!!.rows.none { it.chapterId == f.first.id })
    }

    @Test fun identicalSourceInodeReplacementAfterIoRetiresTheHeldAggregate() = fixture { f, service ->
        val found = service.search("Hello")!!
        val replacement = File(f.fixture.sources, "replacement.pending").apply { writeBytes(f.fixture.source.readBytes()) }
        Files.move(replacement.toPath(), f.fixture.source.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        var delivered = false
        assertFalse(found.tryDeliver { delivered = true; true }); assertFalse(delivered)
    }

    @Test fun explicitRelinkCannotBorrowAnOldHintSeries() = fixture { f, service ->
        val series = f.adapter.memory.createSeries("First explicit series")
        f.adapter.memory.associateChapter(f.second.id, series.id, 1)
        f.reader.activate(ReaderTranslationPresentation.receipt(f.native.latest(f.second.id)!!))
        assertNotNull(f.adapter.openEditor(f.reader.current()!!, 7, 0))
        val old = service.search("Hello", forceRefresh = true)!!
        val row = old.rows.first { it.chapterId == f.second.id }
        val other = f.adapter.memory.createSeries("Other explicit series")
        f.adapter.memory.associateChapter(f.second.id, other.id, 1)
        assertNull(service.prepareOpen(old, row.id))
        assertTrue(service.search("Hello")!!.rows.none { it.chapterId == f.second.id })
        assertFalse(old.tryDeliver { true })
    }

    @Test fun removedExplicitSeriesProfileRetiresHeldResultsAndFutureHintAcceptance() = fixture { f, service ->
        val series = f.adapter.memory.createSeries("Explicit profile")
        f.adapter.memory.associateChapter(f.second.id, series.id, 1)
        f.reader.activate(ReaderTranslationPresentation.receipt(f.native.latest(f.second.id)!!))
        check(f.adapter.openEditor(f.reader.current()!!, 7, 0) != null)
        val held = service.search("Hello", forceRefresh = true)!!
        assertTrue(held.rows.any { it.chapterId == f.second.id })
        f.adapter.memory.removeSeries(series.id)
        assertFalse(held.tryDeliver { true })
        assertTrue(service.search("Hello")!!.rows.none { it.chapterId == f.second.id })
    }

    @Test fun replacementOfAnUnmatchedWholeReaderSourceRetiresHeldTextAndPreparedOpen() = fixture { f, service ->
        val other = File(f.fixture.sources, "unmatched.jpg").apply { writeText("other original source bytes") }
        val expanded = f.second.copy(pages = f.second.pages + ChapterPage(9, "local:unmatched", other.path))
        f.current[f.second.id] = expanded
        val start = f.native.start(expanded, f.fixture.config, ownerRequestId = "reader:expanded", forceReprocess = true)
        f.native.markRunning(start.id, start.generation)
        for (index in listOf(7, 9)) {
            val page = f.native.beginPage(start.id, start.generation, index)!!
            val output = f.native.createOutputFile(start.id, start.generation, index).apply { writeText("valid expanded surface $index") }
            check(f.native.commitPage(start.id, start.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                cleanedPath = output.path, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 250, imageHeight = 333,
                originalWidth = 1001, originalHeight = 1009, lettering = listOf(SavedMangaLettering(if (index == 7) "Hello." else "Other.", "नमस्ते।", 0, 0, 100, 100,
                    "sans-serif", 0, -16777216, 20f, "ALIGN_CENTER", 1, 2, 23, 47,
                    originalSourceBounds = SavedOriginalSourceBounds(1, 4, 6, 93, 143))))))
        }
        val task = f.native.finish(start.id, start.generation)!!
        f.reader.activate(ReaderTranslationPresentation.receipt(task))
        check(f.adapter.openEditor(f.reader.current()!!, 7, 0) != null)
        val found = service.search("Hello", forceRefresh = true)!!
        val row = found.rows.single { it.chapterId == expanded.id }
        assertEquals(7, row.row.hit.source.pageIndex)
        val open = service.prepareOpen(found, row.id)!!
        val replacement = File(f.fixture.sources, "unmatched.pending").apply { writeBytes(other.readBytes()) }
        Files.move(replacement.toPath(), other.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        assertEquals(task.pages.single { it.index == 7 }, f.native.get(task.id)!!.pages.single { it.index == 7 })
        var accepted = false
        assertFalse(found.tryDeliver { accepted = true; true }); assertFalse(accepted)
        assertFalse(open.tryDeliver { accepted = true; true }); assertFalse(accepted)
    }

    @Test fun sparseActualIndexSevenStillOpensListOrdinalZero() = fixture { f, service ->
        val found = service.search("Hello")!!
        val row = found.rows.single { it.chapterId == f.second.id }
        var selection: SavedTextReaderSelection? = null
        assertTrue(service.prepareOpen(found, row.id)!!.tryDeliver { selection = it; true })
        assertEquals(7, selection!!.pageIndex); assertEquals(0, selection!!.pageOrdinal)
        assertEquals(MemoryRegionBounds(4, 6, 93, 143), row.row.hit.source.bounds)
    }

    @Test fun unknownIdsQueriesAndRowIdsNeverBecomeNativeCommands() = fixture { _, service ->
        for (query in listOf("", " ", "x".repeat(257), "Hello\u0000")) assertNull(service.search(query))
        val found = service.search("Hello")!!
        assertNull(service.prepareOpen(found, "forged reader result"))
        assertTrue(service.search("not in these chapters")!!.rows.isEmpty())
    }

    @Test fun sourceBudgetIsEnforcedByActualReadsAndTheOldInspectorDefaultRemainsUsable() = runBlocking {
        val root = Files.createTempDirectory("bounded-source-inspection").toFile()
        try {
            val source = File(root, "source.jpg").apply { writeBytes(ByteArray(1025) { 1 }) }
            val chapter = SavedChapter("a".repeat(32), "Saved", "local:saved", listOf(ChapterPage(7, "local:7", source.path)))
            assertNull(SavedTextChapterScope.inspect(chapter, root, sourceByteBudget = 1024))
            val original = SavedTextChapterScope.inspect(chapter, root)!!
            assertEquals(1025L, original.inspectedBytes)
            assertEquals(setOf(7), original.sources.keys)
        } finally { root.deleteRecursively() }
    }

    @Test fun failedSourceInspectionConsumesTheSharedBatchReadBudget() = runBlocking {
        val root = Files.createTempDirectory("shared-source-budget").toFile()
        try {
            val a = File(root, "a.jpg").apply { writeBytes(ByteArray(700) { 1 }) }
            val b = File(root, "b.jpg").apply { writeBytes(ByteArray(700) { 2 }) }
            val budget = com.mangalens.orez.agent.OrezChapterSourceReadBudget(1000)
            val first = SavedChapter("a".repeat(32), "A", "local:a", listOf(ChapterPage(0, "local:0", a.path)))
            val second = SavedChapter("b".repeat(32), "B", "local:b", listOf(ChapterPage(0, "local:0", b.path)))
            assertNotNull(SavedTextChapterScope.inspect(first, root, sourceReadBudget = budget))
            assertEquals(700L, budget.usedBytes)
            assertNull(SavedTextChapterScope.inspect(second, root, sourceReadBudget = budget))
            assertEquals(1000L, budget.usedBytes); assertEquals(0L, budget.remainingBytes)
            assertNull(SavedTextChapterScope.inspect(first, root, sourceReadBudget = budget))
            assertEquals(1000L, budget.usedBytes)
        } finally { root.deleteRecursively() }
    }

    private class CrossFixture : AutoCloseable {
        val fixture = NativeMemoryPublicationAdapterTest.Fixture()
        val native = fixture.store()
        val first = fixture.chapter
        val secondSource = File(fixture.sources, "second.jpg").apply { writeText("second original chapter bytes") }
        val second = SavedChapter("b".repeat(32), "Second chapter", "local:second", listOf(ChapterPage(7, "local:second-seven", secondSource.path)))
        val current = linkedMapOf(first.id to first, second.id to second)
        val reader = ReaderMemoryPublicationAuthority()
        val adapter = NativeMemoryPublicationAdapter(fixture.root, native, reader)
        suspend fun setup() {
            val a = fixture.completed(nativeStore = native)
            reader.activate(ReaderTranslationPresentation.receipt(a)); check(adapter.openEditor(reader.current()!!, 0, 0) != null)
            val start = native.start(second, fixture.config, ownerRequestId = "reader:second")
            native.markRunning(start.id, start.generation)
            val page = native.beginPage(start.id, start.generation, 7)!!
            val output = native.createOutputFile(start.id, start.generation, 7).apply { writeText("valid second surface") }
            check(native.commitPage(start.id, start.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                cleanedPath = output.path, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 250, imageHeight = 333,
                originalWidth = 1001, originalHeight = 1009, lettering = listOf(SavedMangaLettering("Hello.", "नमस्ते।", 0, 0, 100, 100,
                    "sans-serif", 0, -16777216, 20f, "ALIGN_CENTER", 1, 2, 23, 47,
                    originalSourceBounds = SavedOriginalSourceBounds(1, 4, 6, 93, 143))))))
            val b = native.finish(start.id, start.generation)!!
            reader.activate(ReaderTranslationPresentation.receipt(b)); check(adapter.openEditor(reader.current()!!, 7, 0) != null)
        }
        fun service() = NativeCrossChapterSavedTextSearchService(native, adapter.memory, MemoryLexicalHintStore(fixture.root)) { id, budget ->
            current[id]?.let { SavedTextChapterScope.inspect(it, fixture.sources, sourceReadBudget = budget) }
        }
        override fun close() = fixture.close()
    }
    private fun fixture(body: suspend (CrossFixture, NativeCrossChapterSavedTextSearchService) -> Unit) = runBlocking {
        CrossFixture().use { f -> f.setup(); body(f, f.service()) }
    }
}
