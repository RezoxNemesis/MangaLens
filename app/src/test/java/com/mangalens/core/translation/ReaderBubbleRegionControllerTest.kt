package com.mangalens.core.translation

import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Authored UNRUN controller/actual-store proofs. Callbacks do not imply OCR or model quality. */
class ReaderBubbleRegionControllerTest {
    @Test fun originalRetryReceivesExactCapturedNativeAndShowsUnsavedReadingWithoutWriting() = runBlocking {
        var received: ReaderBubbleInspection? = null
        Fixture(actions = { selection, action, source, owner ->
            owner.validate(true); received = selection
            assertEquals(ReaderBubbleRegionAction.RETRY_ORIGINAL_OCR, action); assertEquals("Hello.", source)
            result()
        }).use { f ->
            f.open(); f.controller.retryOriginalOcr(); f.awaitAlternatives()
            assertEquals(f.task.generation, received!!.proof.task.generation)
            assertSame(received!!.proof.task.config, received!!.readerReceipt.configuration)
            assertEquals("Hello, friend.", f.controller.state.value.alternatives.single().sourceText)
            f.assertNoWrites()
        }
    }

    @Test fun regenerateUsesActualPersonalOcrWithoutPretendingItIsNativeOcr() = runBlocking {
        var source: String? = null
        Fixture(actions = { selected, action, value, owner ->
            owner.validate(true); source = value
            assertEquals("Hello.", selected.view.originalOcr)
            assertEquals(ReaderBubbleRegionAction.REGENERATE_TRANSLATION, action); result()
        }).use { f ->
            val adapter = NativeMemoryPublicationAdapter(f.native.root, f.store, f.authority)
            val editor = requireNotNull(adapter.openEditor(f.selected, 0, 0))
            adapter.correct(editor, 0, MemoryCorrectionEdit(correctedOcr = "Hello, friend.", translated = "नमस्ते, मित्र।"))
            val journal = f.memoryJournal.readBytes()
            f.open(); f.controller.regenerateTranslation(); f.awaitAlternatives()
            assertEquals("Hello, friend.", source)
            assertArrayEquals(journal, f.memoryJournal.readBytes()); assertArrayEquals(f.nativeBefore, f.native.journal(f.task.id).readBytes())
        }
    }

    @Test fun legacyReadCannotSubmitOriginalOcrOrTranslationThroughTheNewEditorPath() = runBlocking {
        val calls = AtomicInteger()
        Fixture(legacy = true, actions = { _, _, _, _ -> calls.incrementAndGet(); result() }).use { f ->
            f.open(); f.controller.retryOriginalOcr()
            withTimeout(2000) { f.controller.state.first { !it.busy && it.error } }
            assertEquals(0, calls.get()); assertTrue(f.controller.state.value.alternatives.isEmpty()); f.assertNoWrites()
        }
    }

    @Test fun dismissalRejectsAHeldRegionCallback() = held { it.controller.dismiss() }
    @Test fun nativeG2RejectsAHeldRegionCallback() = held { f ->
        f.native.completed(nativeStore = f.store, owner = "reader:G2", forceReprocess = true)
    }
    @Test fun sameSeriesRelinkRejectsAHeldRegionCallback() = held(linked = true) { f ->
        f.memory.unlinkChapter(f.native.chapter.id); f.memory.associateChapter(f.native.chapter.id, "series-one", 0)
    }
    @Test fun profileReplacementRejectsAHeldRegionCallback() = held(linked = true) { f ->
        f.memory.upsertTerm("series-one", SeriesGlossaryTerm("new-term", "Hello", "नमस्ते", "hi"))
    }
    @Test fun originalSourceReplacementRejectsAHeldRegionCallback() = held { f ->
        f.native.source.writeText("replaced source bytes")
    }

    @Test fun aCopiedAlternativeCannotIndexOrOpenTheCorrectionEditor() = runBlocking {
        Fixture(actions = { _, _, _, owner -> owner.validate(true); result() }).use { f ->
            f.open(); f.controller.retryOriginalOcr(); f.awaitAlternatives()
            f.controller.chooseAlternative(f.controller.state.value.alternatives.single().copy())
            withTimeout(2000) { f.controller.state.first { !it.busy && it.error } }
            assertEquals(0, f.opened.get()); f.assertNoWrites()
        }
    }

    @Test fun explicitAlternativeChoiceIndexesTheActualReceiptButDoesNotFabricateCorrectionHistory() = runBlocking {
        Fixture(actions = { _, _, _, owner -> owner.validate(true); result() }).use { f ->
            f.open(); f.controller.retryOriginalOcr(); f.awaitAlternatives()
            val actual = f.controller.state.value.alternatives.single()
            f.controller.chooseAlternative(actual)
            withTimeout(2000) { f.delivered.await() }
            val editor = requireNotNull(f.editor)
            assertEquals("Hello.", editor.captured.receipt.originalOcr)
            assertEquals(MemoryCorrectionEdit(correctedOcr = "Hello, friend."), f.preset)
            assertNull(editor.bubble.correction); assertEquals(0, editor.bubble.editRevision)
            assertNull(f.memory.inspectChapter(f.native.chapter.id).bubbles.single().correction)
            assertArrayEquals(f.nativeBefore, f.native.journal(f.task.id).readBytes())
            assertArrayEquals(f.sourceBefore, f.native.source.readBytes())
        }
    }

    @Test fun repeatedRetryWhileBusyCannotBeginASecondRegionOperation() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        Fixture(actions = { _, _, _, owner -> calls.incrementAndGet(); owner.validate(false); entered.complete(Unit); release.await(); result() }).use { f ->
            f.open(); f.controller.retryOriginalOcr()
            try {
                withTimeout(2000) { entered.await() }; f.controller.regenerateTranslation(); assertEquals(1, calls.get())
                release.complete(Unit); f.awaitAlternatives(); f.assertNoWrites()
            } finally { release.complete(Unit) }
        }
    }

    private fun held(linked: Boolean = false, change: suspend (Fixture) -> Unit) = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        Fixture(linked = linked, actions = { _, _, _, owner ->
            owner.validate(false); entered.complete(Unit); release.await(); result()
        }).use { f ->
            f.open(); f.controller.retryOriginalOcr()
            try {
                withTimeout(2000) { entered.await() }; change(f); release.complete(Unit)
                withTimeout(2000) { f.controller.state.first { !it.open } }
                assertTrue(f.controller.state.value.alternatives.isEmpty()); assertEquals(0, f.opened.get())
                assertTrue(f.memory.inspectChapter(f.native.chapter.id).bubbles.isEmpty())
            } finally { release.complete(Unit) }
        }
    }

    private fun result() = ReaderBubbleRegionResult(listOf(ReaderBubbleRegionAlternative("actual-callback-reading",
        ReaderBubbleAlternativeKind.ORIGINAL_OCR, "Hello, friend.")))

    private class Fixture(legacy: Boolean = false, linked: Boolean = false, actions: SavedBubbleRegionActions) : AutoCloseable {
        val native = NativeMemoryPublicationAdapterTest.Fixture(); val store = native.store()
        val task = native.completed(legacyGeometry = legacy, nativeStore = store)
        val nativeBefore = native.journal(task.id).readBytes(); val sourceBefore = native.source.readBytes()
        val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
        val memory = SeriesMemoryStore(native.root)
        val memoryJournal = File(native.root, "reader_memory/chapters/${native.chapter.id}.json")
        val opened = AtomicInteger(); val delivered = CompletableDeferred<Unit>()
        var editor: ReaderMemoryEditor? = null; var preset: MemoryCorrectionEdit? = null
        private val job = SupervisorJob(); private val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val controller = ReaderBubbleToolsController(native.root, scope, authority, { store }, AtomicMemoryJournalWriter,
            { null }, { _, _, _ -> error("Generated alternatives must use the unsaved-preset path.") },
            { actions }, { _, _, value, edit, label ->
                assertTrue(label.contains("review", true)); editor = value; preset = edit; opened.incrementAndGet(); delivered.complete(Unit)
            })
        init { if (linked) runBlocking { memory.createSeries("Series", "series-one"); memory.associateChapter(native.chapter.id, "series-one", 0) } }
        suspend fun open() { controller.open(selected, 0, 0, task.pages.single().lettering.single())
            withTimeout(2000) { controller.state.first { it.view != null && !it.busy } } }
        suspend fun awaitAlternatives() = withTimeout(2000) { controller.state.first { it.alternatives.isNotEmpty() && !it.busy } }
        fun assertNoWrites() { assertFalse(memoryJournal.exists()); assertArrayEquals(nativeBefore, native.journal(task.id).readBytes()); assertArrayEquals(sourceBefore, native.source.readBytes()) }
        override fun close() { controller.dismiss(); job.cancel(); native.close() }
    }
}
