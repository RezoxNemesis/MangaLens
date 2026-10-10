package com.mangalens.core.translation

import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.core.translation.inpainting.*
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Authored UNRUN exact real-store authority controls. Injected callbacks allocate no pixels/models. */
class ReaderLaMaControllerOwnershipTest {
    @Test fun openingSavedToolsDoesNotVerifyDownloadOrLoadTheOptionalPack() = runBlocking {
        Fixture { _, _ -> error("Preview not requested") }.use { f ->
            f.open(); assertEquals(0, f.calls.get()); assertEquals(0, f.modelReads.get()); f.assertNoWrites()
        }
    }
    @Test fun legacySavedTextCannotInvokeExperimentalImageRepair() = runBlocking {
        Fixture(legacy = true) { _, _ -> error("No original coordinates") }.use { f ->
            f.open(); f.controller.previewArtworkRepair(); f.awaitIdleError(); assertEquals(0, f.calls.get()); f.assertNoWrites()
        }
    }
    @Test fun nativeGenerationReplacementRejectsTheHeldRepairDispatch() = held { f ->
        f.native.completed(nativeStore = f.store, owner = "reader:G2", forceReprocess = true)
    }
    @Test fun sameSeriesRelinkRejectsTheHeldRepairDispatch() = held(linked = true) { f ->
        f.memory.unlinkChapter(f.native.chapter.id); f.memory.associateChapter(f.native.chapter.id, "series-one", 0)
    }
    @Test fun sourceReplacementRejectsTheHeldRepairDispatch() = held { f -> f.native.source.writeText("replaced original") }
    @Test fun manuallyDeniedPageCannotOpenRepairToolsOrReadTheOptionalPack() = runBlocking {
        Fixture { _, _ -> error("A hidden page cannot request repair") }.use { f ->
            f.pageAllowed.set(false)
            f.controller.open(f.selected, 0, 0, f.task.pages.single().lettering.single())
            f.controller.previewArtworkRepair()
            assertFalse(f.controller.state.value.open); assertEquals(0, f.calls.get()); assertEquals(0, f.modelReads.get()); f.assertNoWrites()
        }
    }
    @Test fun manualPageDenialDuringHeldRepairRejectsFurtherProviderWork() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
        val forbiddenWork = AtomicInteger()
        Fixture { _, owner ->
            owner.validate(false); entered.complete(Unit)
            try {
                withContext(NonCancellable) { release.await() }; owner.validate(false)
                forbiddenWork.incrementAndGet(); error("A hidden owner cannot produce synthetic pixels")
            } finally { returned.complete(Unit) }
        }.use { f ->
            f.open(); f.controller.previewArtworkRepair()
            try {
                withTimeout(2000) { entered.await() }; f.pageAllowed.set(false); release.complete(Unit)
                withTimeout(2000) { returned.await() }
                assertEquals(0, forbiddenWork.get()); assertNull(f.controller.state.value.artworkPreview); f.assertNoWrites()
            } finally { release.complete(Unit) }
        }
    }
    @Test fun optionalToolsPauseRetiresOnlyTheRepairOperationWithoutInventingAPreview() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val completed = CompletableDeferred<Unit>()
        Fixture { _, owner ->
            owner.validate(false); entered.complete(Unit)
            try { withContext(NonCancellable) { release.await() }; owner.validate(false); error("No synthetic bitmap result") }
            finally { completed.complete(Unit) }
        }.use { f ->
            f.open(); f.controller.previewArtworkRepair()
            try {
                withTimeout(2000) { entered.await() }; f.controller.clearArtworkPreview()
                assertTrue(f.controller.state.value.open); assertFalse(f.controller.state.value.busy); assertNull(f.controller.state.value.artworkPreview)
                release.complete(Unit); withTimeout(2000) { completed.await() }; assertNull(f.controller.state.value.artworkPreview); f.assertNoWrites()
            } finally { release.complete(Unit) }
        }
    }
    @Test fun clearingAnAbsentRepairDoesNotChangeAnUnrelatedHeldAnswerBusyState() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<String?>()
        Fixture(explanation = { _, _ -> entered.complete(Unit); release.await() }) { _, _ -> error("No preview requested") }.use { f ->
            f.open(); f.controller.ask("Explain")
            try {
                withTimeout(2000) { entered.await() }; f.controller.clearArtworkPreview()
                assertTrue(f.controller.state.value.busy); release.complete("Local unsaved answer")
                withTimeout(2000) { f.controller.state.first { !it.busy && it.answer != null } }; f.assertNoWrites()
            } finally { release.complete(null) }
        }
    }
    @Test fun pauseResumeDuringHeldStorePreparationCannotStartTheOldRepairProducer() = runBlocking {
        val hold = AtomicBoolean(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
        Fixture(providerGate = { if (hold.get()) {
            entered.complete(Unit); try { withContext(NonCancellable) { release.await() } } finally { returned.complete(Unit) }
        } }) { _, owner -> owner.validate(false); error("No synthetic preview pixels") }.use { f ->
            f.open(); val earlier = f.scopeJob.children.toSet(); hold.set(true); f.controller.previewArtworkRepair()
            try {
                withTimeout(2000) { entered.await() }; val oldProducer = f.scopeJob.children.single { it !in earlier }
                f.controller.clearArtworkPreview()
                assertEquals(0, f.calls.get()); hold.set(false)
                f.controller.previewArtworkRepair(); f.awaitIdleError(); assertEquals(1, f.calls.get())
                release.complete(Unit); withTimeout(2000) { returned.await(); oldProducer.join() }
                assertEquals(1, f.calls.get()); assertNull(f.controller.state.value.artworkPreview); f.assertNoWrites()
            } finally { release.complete(Unit) }
        }
    }

    private fun held(linked: Boolean = false, change: suspend (Fixture) -> Unit) = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        Fixture(linked = linked) { _, owner ->
            owner.validate(false); entered.complete(Unit); release.await(); owner.validate(true); error("No synthetic bitmap result")
        }.use { f ->
            f.open(); f.controller.previewArtworkRepair()
            try {
                withTimeout(2000) { entered.await() }; change(f); release.complete(Unit)
                withTimeout(2000) { f.controller.state.first { !it.open } }; assertNull(f.controller.state.value.artworkPreview)
                assertTrue(f.memory.inspectChapter(f.native.chapter.id).bubbles.isEmpty())
            } finally { release.complete(Unit) }
        }
    }
    private class Fixture(legacy: Boolean = false, linked: Boolean = false, explanation: SavedBubbleExplanation? = null,
        providerGate: suspend () -> Unit = {},
        action: suspend (ReaderBubbleInspection, NativeComputePrecondition) -> ReaderLaMaRepairPreview) : AutoCloseable {
        val native = NativeMemoryPublicationAdapterTest.Fixture(); val store = native.store()
        val task = native.completed(legacyGeometry = legacy, nativeStore = store)
        val nativeBefore = native.journal(task.id).readBytes(); val sourceBefore = native.source.readBytes()
        val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
        val memory = SeriesMemoryStore(native.root); val calls = AtomicInteger(); val modelReads = AtomicInteger(); val pageAllowed = AtomicBoolean(true)
        val scopeJob = SupervisorJob(); private val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val repair = object : SavedBubbleArtworkRepair {
            override val model: LaMaModelManager get() { modelReads.incrementAndGet(); error("A controller ownership test must not instantiate an Android model manager.") }
            override suspend fun preview(inspection: ReaderBubbleInspection, owner: NativeComputePrecondition): ReaderLaMaRepairPreview {
                calls.incrementAndGet(); return action(inspection, owner)
            }
        }
        val controller = ReaderBubbleToolsController(native.root, scope, authority, { providerGate(); store }, AtomicMemoryJournalWriter,
            { explanation }, { _, _, _ -> error("Preview cannot open a correction editor") },
            isPageAllowed = { pageAllowed.get() }, artworkRepairProvider = { repair })
        init { if (linked) runBlocking { memory.createSeries("Series", "series-one"); memory.associateChapter(native.chapter.id, "series-one", 0) } }
        suspend fun open() { controller.open(selected, 0, 0, task.pages.single().lettering.single())
            withTimeout(2000) { controller.state.first { it.view != null && !it.busy } } }
        suspend fun awaitIdleError() = withTimeout(2000) { controller.state.first { !it.busy && it.error } }
        fun assertNoWrites() {
            assertFalse(File(native.root, "reader_memory/chapters/${native.chapter.id}.json").exists())
            assertArrayEquals(nativeBefore, native.journal(task.id).readBytes()); assertArrayEquals(sourceBefore, native.source.readBytes())
        }
        override fun close() { controller.dismiss(); scopeJob.cancel(); native.close() }
    }
}
