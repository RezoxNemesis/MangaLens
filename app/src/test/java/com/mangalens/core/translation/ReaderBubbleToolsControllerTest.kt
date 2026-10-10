package com.mangalens.core.translation

import com.mangalens.core.translation.memory.SeriesMemoryStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Prepared real legacy read/ownership controls: callbacks never instantiate a model or decode a bitmap. */
class ReaderBubbleToolsControllerTest {
    @Test fun legacyDialogReadsSavedTextAndRefusesExplicitEditCredentials() = runBlocking {
        Fixture().use { f ->
            f.open(); f.awaitView()
            assertEquals("Hello.", f.controller.state.value.view!!.originalOcr)
            assertFalse(f.controller.state.value.view!!.hasOriginalCrop)
            assertNull(f.controller.state.value.preview)
            assertFalse(File(f.native.root, "reader_memory").exists())
            f.controller.edit()
            withTimeout(1_000) { f.controller.state.first { !it.open } }
            assertEquals(0, f.shownEditors.get()); assertFalse(File(f.native.root, "reader_memory").exists())
        }
    }

    @Test fun aRetiredCapturedCallbackCannotRecaptureTheSameReceiptAtANewEpoch() = runBlocking {
        Fixture().use { f ->
            f.authority.activate(f.selected.receipt)
            f.open()
            assertFalse(f.controller.state.value.open)
            assertFalse(File(f.native.root, "reader_memory").exists())
        }
    }

    @Test fun dismissalCancelsAHeldLocalAnswerAndCannotPublishItsLateText() = heldAnswer { f -> f.controller.dismiss() }

    @Test fun nativeG2DuringAHeldAnswerRejectsTheOldSelectionWithoutReadingAnotherChapter() = heldAnswer { f ->
        f.native.completed(legacyGeometry = true, nativeStore = f.store, owner = "reader:G2", forceReprocess = true)
    }

    @Test fun sameSeriesRelinkDuringAHeldAnswerRetiresThePersonalSnapshot() = heldAnswer(linked = true) { f ->
        val memory = SeriesMemoryStore(f.native.root)
        memory.unlinkChapter(f.native.chapter.id); memory.associateChapter(f.native.chapter.id, "series-one", 0)
    }

    @Test fun repeatedAskWhileBusyDoesNotStartAnotherLocalAction() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<String?>(); val calls = AtomicInteger()
        Fixture(explanation = { _, owner -> owner.validate(false); calls.incrementAndGet(); entered.complete(Unit); release.await() }).use { f ->
            f.open(); f.awaitView(); f.controller.ask("First question")
            try {
                withTimeout(1_000) { entered.await() }
                assertTrue(f.controller.state.value.busy)
                f.controller.ask("Second question")
                assertEquals(1, calls.get())
                release.complete("A local callback answer")
                withTimeout(1_000) { f.controller.state.first { it.answer != null && !it.busy } }
                assertEquals("A local callback answer", f.controller.state.value.answer!!.text)
            } finally { f.controller.dismiss(); release.complete(null) }
        }
    }

    @Test fun newSelectionRetiresTheHeldOldOperationBeforeItsCancellationCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        Fixture(providerGate = {
            if (calls.getAndIncrement() == 0) { entered.complete(Unit); release.await() }
        }).use { f ->
            f.open()
            try {
                withTimeout(1_000) { entered.await() }
                val newer = f.native.completed(legacyGeometry = true, nativeStore = f.store, owner = "reader:G2", forceReprocess = true)
                val current = f.authority.activate(ReaderTranslationPresentation.receipt(newer))
                f.controller.open(current, 0, 0, newer.pages.single().lettering.single())
                release.complete(Unit)
                f.awaitView()
                assertTrue(f.controller.state.value.open); assertFalse(f.controller.state.value.busy)
                assertFalse(File(f.native.root, "reader_memory").exists())
            } finally { release.complete(Unit); f.controller.dismiss() }
        }
    }

    private fun heldAnswer(linked: Boolean = false, change: suspend (Fixture) -> Unit) = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<String?>()
        Fixture(linked, { _, owner -> owner.validate(false); entered.complete(Unit); release.await() }).use { f ->
            f.open(); f.awaitView(); f.controller.ask("Explain the saved dialogue")
            try {
                withTimeout(1_000) { entered.await() }
                change(f)
                release.complete("This held callback text must never appear")
                withTimeout(1_000) { f.controller.state.first { !it.open } }
                assertNull(f.controller.state.value.answer); assertEquals(0, f.shownEditors.get())
            } finally { release.complete(null); f.controller.dismiss() }
        }
    }

    private class Fixture(linked: Boolean = false, explanation: SavedBubbleExplanation? = null,
        providerGate: suspend () -> Unit = {}) : AutoCloseable {
        val native = NativeMemoryPublicationAdapterTest.Fixture()
        val store = native.store(); val task = native.completed(legacyGeometry = true, nativeStore = store)
        val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val shownEditors = AtomicInteger()
        val controller = ReaderBubbleToolsController(native.root, scope, authority, { providerGate(); store },
            com.mangalens.core.translation.memory.AtomicMemoryJournalWriter, { explanation }, { _, _, _ -> shownEditors.incrementAndGet(); Unit })
        init { if (linked) runBlocking { SeriesMemoryStore(native.root).apply { createSeries("Explicit series", "series-one"); associateChapter(native.chapter.id, "series-one", 0) } } }
        fun open() = controller.open(selected, 0, 0, task.pages.single().lettering.single())
        suspend fun awaitView() = withTimeout(1_000) { controller.state.first { it.open && it.view != null && !it.busy } }
        override fun close() { controller.dismiss(); job.cancel(); native.close() }
    }
}
