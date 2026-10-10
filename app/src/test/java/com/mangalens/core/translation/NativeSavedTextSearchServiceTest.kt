package com.mangalens.core.translation

import com.mangalens.core.reader.*
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeSavedTextSearchServiceTest {
    @Test fun actualOriginalAndPersonalKindsStaySeparateAndReadOnly() = fixture { f, native, adapter, chapter, service ->
        // Reuse the exact receipt indexed by this fixture's actual adapter/Reader authority.
        val editor = MemoryBubbleEditor(adapter.memory.inspectChapter(chapter.id).bubbles.single().receipt)
        adapter.correct(editor, 0, MemoryCorrectionEdit(correctedOcr = "Hello, friend.", translated = "नमस्ते, मित्र।"))
        val before = f.root.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes() }
        val found = service.search(chapter.id, " Hello ")!!
        assertEquals("Hello", found.query)
        assertEquals(setOf(MemorySearchKind.OCR, MemorySearchKind.CORRECTED_OCR), found.rows.map { it.hit.kind }.toSet())
        assertEquals(setOf("Hello.", "Hello, friend."), found.rows.map { it.hit.text }.toSet())
        assertTrue(found.rows.all { it.hit.source.chapterId == chapter.id && it.hit.revision == 1 })
        assertEquals(setOf(0), found.rows.map { it.hit.source.pageIndex }.toSet())
        val row = found.rows.single { it.hit.kind == MemorySearchKind.CORRECTED_OCR }
        val prepared = service.prepareOpen(found, row.id)!!
        var delivered: SavedTextReaderSelection? = null
        assertTrue(prepared.tryDeliver { delivered = it; true })
        assertEquals(0, delivered!!.pageOrdinal); assertEquals(0, delivered!!.pageIndex)
        assertEquals(MemorySearchKind.CORRECTED_OCR, delivered!!.kind)
        assertEquals(native.latest(chapter.id)!!.config, delivered!!.receipt.configuration)
        assertFalse("One explicit result-opening action cannot be replayed", prepared.tryDeliver { true })
        before.forEach { (path, bytes) -> assertArrayEquals(path, bytes, File(path).readBytes()) }
    }

    @Test fun sparseActualPageIndexNavigatesToItsListOrdinalWithoutInventingBounds() = fixture(sparse = true) { _, _, _, chapter, service ->
        val result = service.search(chapter.id, "Hello")!!
        val row = result.rows.single { it.hit.source.pageIndex == 7 }
        val prepared = service.prepareOpen(result, row.id)!!
        var selected: SavedTextReaderSelection? = null
        assertTrue(prepared.tryDeliver { selected = it; true })
        assertEquals(listOf(7, 0), chapter.pages.map { it.index })
        assertEquals(7, selected!!.pageIndex); assertEquals(0, selected!!.pageOrdinal)
        assertEquals(MemoryRegionBounds(4, 6, 93, 143), row.hit.source.bounds)
    }

    @Test fun malformedUnknownChapterQueryAndResultIdsCannotExpandTheSelectedScope() = fixture { _, _, _, chapter, service ->
        for (query in listOf("", " ", "a".repeat(257), "Hello\u0000")) assertNull(service.search(chapter.id, query))
        assertNull(service.search("../private", "Hello")); assertNull(service.search("b".repeat(32), "Hello"))
        val found = service.search(chapter.id, "Hello")!!
        assertNull(service.prepareOpen(found, "other chapter/result"))
        assertEquals(1, found.rows.size)
    }

    @Test fun heldMainDeliveryCannotOpenAnIdenticalTextG2Result() = fixture { f, native, _, chapter, service ->
        val found = service.search(chapter.id, "Hello")!!
        val open = service.prepareOpen(found, found.rows.single().id)!!
        f.completed(nativeStore = native, owner = "reader:G2", forceReprocess = true)
        var opened = false
        assertFalse(open.tryDeliver { opened = true; true })
        assertFalse(opened)
        assertNull(service.prepareOpen(found, found.rows.single().id))
    }

    @Test fun sourceOutputDeleteAndExactRelinkAfterIoBeforeMainRejectNavigation() = runBlocking {
        for (kind in listOf("source", "output", "delete", "relink")) fixture(linked = true) { f, native, adapter, chapter, service ->
            val found = service.search(chapter.id, "Hello")!!
            val row = found.rows.single(); val open = service.prepareOpen(found, row.id)!!
            when (kind) {
                "source" -> f.source.appendText(" changed")
                "output" -> File(native.latest(chapter.id)!!.pages.single().cleanedPath!!).appendText(" changed")
                "delete" -> native.finishChapterRemoval(native.beginChapterRemoval(chapter.id))
                else -> adapter.memory.associateChapter(chapter.id, adapter.memory.createSeries("Another series").id, 0)
            }
            var opened = false
            assertFalse(kind, open.tryDeliver { opened = true; true }); assertFalse(opened)
        }
    }

    @Test fun aColdPendingTaskIsOmittedWithoutRefreshingTheJournal() = fixture { f, _, adapter, chapter, _ ->
        val cold = f.store()
        val before = f.journal(cold.latest(chapter.id)!!.id).readBytes()
        val service = NativeSavedTextSearchService(cold, adapter.memory) { id ->
            chapter.takeIf { it.id == id }?.let { SavedTextChapterScope.inspect(it, f.sources) }
        }
        val found = service.search(chapter.id, "Hello")!!
        assertTrue(found.rows.isEmpty()); assertTrue(found.incomplete)
        assertTrue(cold.latest(chapter.id)!!.validationPending)
        assertArrayEquals(before, f.journal(cold.latest(chapter.id)!!.id).readBytes())
    }

    @Test fun originalAndSavedTranslationQueriesDoNotCreateNativeResults() = fixture { _, native, _, chapter, service ->
        val found = service.search(chapter.id, "नमस्ते")!!
        assertEquals(MemorySearchKind.TRANSLATION, found.rows.single().hit.kind)
        assertEquals("नमस्ते।", found.rows.single().hit.text)
        assertEquals(native.latest(chapter.id)!!.generation, found.rows.single().receipt.generation)
        assertTrue(service.search(chapter.id, "not in this chapter")!!.rows.isEmpty())
    }

    @Test fun retainedNativeBubbleForRemovedPageIsOmittedFromCurrentSelectedChapterResults() = fixture(sparse = true) { f, native, adapter, chapter, _ ->
        val current = chapter.copy(pages = chapter.pages.filter { it.index == 0 })
        val service = NativeSavedTextSearchService(native, adapter.memory) { id ->
            current.takeIf { it.id == id }?.let { SavedTextChapterScope.inspect(it, f.sources) }
        }
        val before = f.root.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes() }
        val found = service.search(chapter.id, "Hello")!!
        assertTrue("A retained page cannot borrow an unbindable whole native receipt", found.rows.isEmpty())
        assertTrue(found.incomplete)
        assertTrue(found.rows.all { current.pages.any { page -> page.index == it.hit.source.pageIndex && page.localPath == it.hit.source.sourcePath } })
        before.forEach { (path, bytes) -> assertArrayEquals(path, bytes, File(path).readBytes()) }
    }

    @Test fun retainedNativeBubbleForReplacedCurrentPagePathCannotAppearAsCurrentText() = fixture { f, native, adapter, chapter, _ ->
        val replacement = File(f.sources, "replacement.jpg").apply { writeText("replacement chapter source bytes") }
        val current = chapter.copy(pages = listOf(chapter.pages.single().copy(localPath = replacement.path)))
        val service = NativeSavedTextSearchService(native, adapter.memory) { id ->
            current.takeIf { it.id == id }?.let { SavedTextChapterScope.inspect(it, f.sources) }
        }
        val found = service.search(chapter.id, "Hello")!!
        assertTrue(found.rows.isEmpty()); assertTrue(found.incomplete)
        assertTrue(native.latest(chapter.id)!!.pages.single().lettering.isNotEmpty())
    }

    @Test fun acceptedSavedTextMustBindItsWholeCapturedNativeScopeToTheExistingReader() = runBlocking {
        for (change in listOf("removed", "added")) fixture(sparse = true) { f, native, adapter, chapter, _ ->
            var current = chapter
            val service = NativeSavedTextSearchService(native, adapter.memory) { id ->
                current.takeIf { it.id == id }?.let { SavedTextChapterScope.inspect(it, f.sources) }
            }
            val choice = ReaderTranslationChoice.from(native.latest(chapter.id)!!.config.copy(refinementRequest = null, memoryPacket = null))
            val valid = service.search(chapter.id, "Hello")!!
            for (row in valid.rows) {
                var opened: SavedTextReaderSelection? = null
                assertTrue(service.prepareOpen(valid, row.id)!!.tryDeliver { opened = it; true })
                assertTrue("An accepted saved result must bind to the unchanged real Reader predicate",
                    ReaderTranslationPresentation.matchesReader(opened!!.receipt, chapter.id, choice,
                        chapter.pages.associate { it.index to it.localPath }, f.sources))
            }
            current = if (change == "removed") chapter.copy(pages = chapter.pages.filter { it.index == 0 }) else {
                val added = File(f.sources, "new-page.jpg").apply { writeText("new original chapter source bytes") }
                chapter.copy(pages = chapter.pages + ChapterPage(1, "local:new", added.path))
            }
            val found = service.search(chapter.id, "Hello")!!
            assertTrue("$change scope must never publish rows the exact existing Reader cannot bind",
                found.rows.all { ReaderTranslationPresentation.matchesReader(it.receipt, chapter.id, choice,
                    current.pages.associate { page -> page.index to page.localPath }, f.sources) })
            assertTrue("$change task scope must be omitted rather than projecting its receipt", found.rows.isEmpty())
            assertTrue(found.incomplete)
            assertNull("A queued old result must not open after source scope changes", service.prepareOpen(valid, valid.rows.first().id))
        }
    }

    private fun fixture(sparse: Boolean = false, linked: Boolean = false,
        body: suspend CoroutineScope.(NativeMemoryPublicationAdapterTest.Fixture, ChapterTranslationStore, NativeMemoryPublicationAdapter, SavedChapter, NativeSavedTextSearchService) -> Unit) = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store()
            val chapter = if (sparse) f.chapter.copy(pages = listOf(ChapterPage(7, "local:seven", f.source.path),
                ChapterPage(0, "local:zero", File(f.sources, "other.jpg").apply { writeText("other original bytes") }.path))) else f.chapter
            val task = if (!sparse) f.completed(nativeStore = native) else {
                val start = native.start(chapter, f.config, ownerRequestId = "reader:sparse")
                native.markRunning(start.id, start.generation)
                for (index in chapter.pages.map { it.index }) {
                    val page = native.beginPage(start.id, start.generation, index)!!
                    val output = native.createOutputFile(start.id, start.generation, index).apply { writeText("valid surface: $index") }
                    check(native.commitPage(start.id, start.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                        cleanedPath = output.path, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 250, imageHeight = 333,
                        originalWidth = 1001, originalHeight = 1009, lettering = listOf(SavedMangaLettering("Hello.", "नमस्ते।", 0, 0, 100, 100,
                            "sans-serif", 0, -16777216, 20f, "ALIGN_CENTER", 1, 2, 23, 47,
                            originalSourceBounds = SavedOriginalSourceBounds(1, 4, 6, 93, 143))))))
                }
                native.finish(start.id, start.generation)!!
            }
            val reader = ReaderMemoryPublicationAuthority(); val selected = reader.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, native, reader)
            if (linked) adapter.memory.associateChapter(chapter.id, adapter.memory.createSeries("Explicit series").id, 0)
            for (page in chapter.pages) check(adapter.openEditor(selected, page.index, 0) != null)
            val service = NativeSavedTextSearchService(native, adapter.memory) { id ->
                chapter.takeIf { it.id == id }?.let { SavedTextChapterScope.inspect(it, f.sources) }
            }
            body(f, native, adapter, chapter, service)
        }
    }
}
