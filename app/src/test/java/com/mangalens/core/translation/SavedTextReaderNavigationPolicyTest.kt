package com.mangalens.core.translation

import com.mangalens.core.reader.*
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SavedTextReaderNavigationPolicyTest {
    @Test fun acceptedResultSetsActualPageOrdinalOffsetZeroAndKeepsCurrentLibraryMetadata() = fixture { f, _, selection ->
        val current = f.chapter.copy(position = 9, scrollOffset = 812, bookmarked = true, notes = "Keep this note", lastReadAt = 5)
        val selected = SavedTextReaderNavigationPolicy.chapterFor(selection, listOf(current), 100)!!
        assertEquals(0, selected.position); assertEquals(0, selected.scrollOffset)
        assertEquals("Keep this note", selected.notes); assertTrue(selected.bookmarked)
        assertEquals(100L, selected.lastReadAt); assertEquals(f.chapter.pages, selected.pages)
        assertEquals("hi", selection.receipt.configuration.targetLanguage)
    }

    @Test fun absentRelocatedOrReplacedChapterMetadataCannotBePromotedOnMain() = fixture { f, _, selection ->
        assertNull(SavedTextReaderNavigationPolicy.chapterFor(selection, emptyList(), 1))
        assertNull(SavedTextReaderNavigationPolicy.chapterFor(selection, listOf(f.chapter.copy(id = "b".repeat(32))), 1))
        assertNull(SavedTextReaderNavigationPolicy.chapterFor(selection, listOf(f.chapter.copy(sourceUrl = "content://other")), 1))
        val relocated = f.chapter.copy(pages = f.chapter.pages.map { it.copy(localPath = it.localPath + ".replacement") })
        assertNull(SavedTextReaderNavigationPolicy.chapterFor(selection, listOf(relocated), 1))
    }

    @Test fun capturedBaseReaderChoicePreservesSavedOcrFlagsAndNeverRecapturesItsModelOrPacket() = fixture { _, _, selection ->
        val configuration = SavedTextReaderNavigationPolicy.readerChoice(selection)
        assertEquals(selection.receipt.configuration.copy(refinementRequest = null, memoryPacket = null), configuration)
        assertTrue(ReaderTranslationChoice.from(configuration).matches(selection.receipt.configuration))
        assertNull(configuration.refinementRequest); assertNull(configuration.memoryPacket)
    }

    @Test fun openAndLeaveProjectSavedTargetWithoutChangingAmbientDefaults() = fixture { _, _, selection ->
        val preferred = SavedTextReaderPreferences("en", "faithful", "Keep the preferred defaults")
        val shown = SavedTextReaderNavigationPolicy.preferences(selection, preferred)
        assertEquals("hi", shown.target); assertEquals("natural", shown.style)
        assertEquals(SavedTextReaderPreferences("en", "faithful", "Keep the preferred defaults"), preferred)
        assertEquals(preferred, SavedTextReaderNavigationPolicy.preferences(null, preferred))
    }

    @Test fun explicitNewTargetEqualToAmbientStillDiffersFromVisibleSavedTargetAndRetiresOverride() = fixture { _, _, selection ->
        val preferred = SavedTextReaderPreferences("en", "faithful", "")
        val requested = "en"
        assertNotEquals(requested, SavedTextReaderNavigationPolicy.preferences(selection, preferred).target)
        assertEquals(requested, SavedTextReaderNavigationPolicy.preferences(null, preferred.copy(target = requested)).target)
        assertEquals("faithful", preferred.style)
    }

    @Test fun queuedReaderRefreshCannotDeliverG1AfterIdenticalOutputG2Replacement() = fixture { f, native, selection ->
        val fromIo = native.get(selection.receipt.taskId)!!
        f.completed(nativeStore = native, owner = "reader:G2", forceReprocess = true)
        var accepted = false
        assertFalse(selection.tryAcceptNative(fromIo) { accepted = true })
        assertFalse(accepted)
    }

    @Test fun acceptedReaderRefreshChecksFreshSelectedSourceAndOutputBeforeMainNativeContext() = runBlocking {
        for (replace in listOf("source", "output")) fixture { f, native, selection ->
            val fromIo = native.get(selection.receipt.taskId)!!
            if (replace == "source") f.source.appendText(" changed")
            else java.io.File(fromIo.pages.single().cleanedPath!!).appendText(" changed")
            var accepted = false
            assertFalse(replace, selection.tryAcceptNative(fromIo) { accepted = true }); assertFalse(accepted)
        }
    }

    @Test fun actualPersonalCorrectionDoesNotRetireUnchangedNativeReaderReceipt() = fixture { f, native, selection ->
        val task = native.get(selection.receipt.taskId)!!
        val authority = ReaderMemoryPublicationAuthority(); authority.activate(ReaderTranslationPresentation.receipt(task))
        val adapter = NativeMemoryPublicationAdapter(f.root, native, authority)
        val editor = MemoryBubbleEditor(adapter.memory.inspectChapter(f.chapter.id).bubbles.single().receipt)
        val before = f.journal(task.id).readBytes()
        adapter.correct(editor, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
        assertEquals(1, adapter.inspect(editor)!!.editRevision)
        var accepted = false
        assertTrue(selection.tryAcceptNative(native.get(selection.receipt.taskId)!!) { accepted = true })
        assertTrue(accepted)
        assertArrayEquals(before, f.journal(task.id).readBytes())
        // The initial result's memory/manifest lease is intentionally separate from later native presentation.
        assertEquals(selection.receipt.configuration, native.get(selection.receipt.taskId)!!.config)
    }

    private fun fixture(body: suspend CoroutineScope.(NativeMemoryPublicationAdapterTest.Fixture, ChapterTranslationStore, SavedTextReaderSelection) -> Unit) = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); val task = f.completed(nativeStore = native)
            val authority = ReaderMemoryPublicationAuthority(); val captured = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, native, authority)
            check(adapter.openEditor(captured, 0, 0) != null)
            val service = NativeSavedTextSearchService(native, adapter.memory) { id ->
                f.chapter.takeIf { it.id == id }?.let { SavedTextChapterScope.inspect(it, f.sources) }
            }
            val result = service.search(f.chapter.id, "Hello")!!
            val pending = service.prepareOpen(result, result.rows.single().id)!!
            var selected: SavedTextReaderSelection? = null
            check(pending.tryDeliver { selected = it; true })
            body(f, native, selected!!)
        }
    }
}
