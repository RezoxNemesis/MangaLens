package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReaderMemoryControllerTest {
    @Test fun acceptedSameReceiptAndActualNeighbourPageCommitKeepEditorIdentityUntilReaderLeaves() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); f.completed(nativeStore = store)
            val secondSource = File(f.sources, "page-one.jpg").apply { writeText("second original source") }
            val chapter = f.chapter.copy(pages = f.chapter.pages + com.mangalens.core.reader.ChapterPage(1, "local:one", secondSource.path))
            val task = store.start(chapter, f.config, ownerRequestId = "reader:fixture")
            val running = store.markRunning(task.id, task.generation)!!
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val controller = ReaderMemoryController(f.root, scope, { store })
                val receipt = ReaderTranslationPresentation.receipt(running)
                controller.bindAccepted(running, receipt); controller.enterReader(); controller.onVisiblePage(0)
                controller.openPage(0); controller.selectBubble(0)
                val before = controller.selectionForTest()!!; val editor = controller.state.value.editor!!.captured
                val next = store.beginPage(task.id, task.generation, 1)!!
                val output = store.createOutputFile(task.id, task.generation, 1).apply { writeText("valid surface: neighbour paper") }
                val saved = running.pages.first().copy(index = 1, sourcePath = next.sourcePath, sourceSha256 = next.sourceSha256,
                    cleanedPath = output.path, cleanedSha256 = ChapterTranslationStore.sha256(output))
                assertTrue(store.commitPage(task.id, task.generation, saved))
                val latest = store.get(task.id)!!
                assertEquals(ChapterTranslationPageStatus.COMPLETED, latest.pages[1].status)
                controller.bindAccepted(latest, receipt)
                assertEquals(before, controller.selectionForTest()); assertEquals(editor, controller.state.value.editor!!.captured)
                controller.leaveReader()
                assertNull(controller.selectionForTest()); assertNull(controller.state.value.editor)
                assertTrue(controller.state.value.personalOverlays.isEmpty())
                controller.enterReader()
                assertNotEquals(before.epoch, controller.selectionForTest()!!.epoch)
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun currentPageSaveRemoveRollbackAndColdRecreationKeepNativeAndOriginalFilesUnchanged() = environment { f, store, controller, task ->
        val source = f.source.readBytes(); val png = File(task.pages.single().cleanedPath!!).readBytes(); val manifest = f.journal(task.id).readBytes()
        controller.onVisiblePage(0); controller.openPage(0); controller.selectBubble(0)
        assertEquals("Hello.", controller.state.value.editor!!.captured.receipt.originalOcr)
        controller.save(MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
        await { controller.state.value.personalOverlays[0]?.get(0)?.personal?.translated == "नमस्ते, मित्र।" }
        assertEquals(1, controller.state.value.editor!!.bubble.editRevision)
        controller.rollback(0)
        await { controller.state.value.personalOverlays[0].orEmpty().isEmpty() }
        assertEquals(2, controller.state.value.editor!!.bubble.editRevision)
        controller.rollback(1)
        await { controller.state.value.personalOverlays[0]?.get(0)?.personal?.translated == "नमस्ते, मित्र।" }
        controller.remove()
        await { controller.state.value.personalOverlays[0].orEmpty().isEmpty() }
        assertEquals(4, controller.state.value.editor!!.bubble.editRevision)
        controller.save(MemoryCorrectionEdit(translated = "नमस्ते, साथी।"))
        assertEquals(5, controller.state.value.editor!!.bubble.editRevision)
        controller.leaveReader()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coldStore = f.store(); val cold = coldStore.refresh(task.id)!!
            val reopened = ReaderMemoryController(f.root, scope, { coldStore })
            reopened.bindAccepted(cold, ReaderTranslationPresentation.receipt(cold)); reopened.enterReader(); reopened.onVisiblePage(0)
            await { reopened.state.value.personalOverlays[0]?.get(0)?.personal?.translated == "नमस्ते, साथी।" }
            assertArrayEquals(source, f.source.readBytes()); assertArrayEquals(png, File(task.pages.single().cleanedPath!!).readBytes()); assertArrayEquals(manifest, f.journal(task.id).readBytes())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun wrongTargetSaveReportsFailureWithoutPublishingACorrection() = environment { _, _, controller, _ ->
        controller.openPage(0); controller.selectBubble(0)
        controller.save(MemoryCorrectionEdit(translated = "Hello, friend."))
        assertTrue(controller.state.value.error); assertEquals(0, controller.state.value.editor!!.bubble.editRevision)
        assertTrue(controller.state.value.personalOverlays.isEmpty())
    }

    @Test fun sourceOrOutputByteReplacementHidesEarlierPersonalOverlay() = runBlocking {
        for (replaceSource in listOf(true, false)) environment { f, _, controller, task ->
            controller.onVisiblePage(0); controller.openPage(0); controller.selectBubble(0)
            controller.save(MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
            await { controller.state.value.personalOverlays[0]?.get(0) != null }
            if (replaceSource) f.source.writeText("same bytes are replaced") else File(task.pages.single().cleanedPath!!).writeText("valid surface: other paper")
            controller.refreshVisiblePage()
            await { controller.state.value.personalOverlays.values.all { it.isEmpty() } }
            controller.save(MemoryCorrectionEdit(translated = "नमस्ते, साथी।"))
            assertTrue(controller.state.value.error); assertEquals(1, controller.state.value.editor!!.bubble.editRevision)
        }
    }

    @Test fun legacySavedPageShowsOriginalsAndRequiresRetranslationWithoutAnEditor() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(legacyGeometry = true, nativeStore = store)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val controller = ReaderMemoryController(f.root, scope, { store })
                controller.bindAccepted(task, ReaderTranslationPresentation.receipt(task)); controller.enterReader(); controller.openPage(0)
                assertEquals("Hello.", controller.state.value.choices.single().originalOcr)
                assertFalse(controller.state.value.choices.single().editable)
                assertTrue(controller.state.value.message!!.contains("Retranslate"))
                controller.selectBubble(0)
                assertNull(controller.state.value.editor); assertTrue(controller.state.value.error)
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun actualSyncedHeldSaveCannotBlockReaderLeaveOrPublishAfterIt() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val held = AtomicBoolean(false); val prepared = CountDownLatch(1); val release = CountDownLatch(1)
            val writer = MemoryJournalWriter { file, bytes ->
                val pending = AtomicMemoryJournalWriter.prepare(file, bytes)
                try { if (held.getAndSet(false)) { prepared.countDown(); check(release.await(5, TimeUnit.SECONDS)) }; pending }
                catch (problem: Throwable) { pending.close(); throw problem }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val controller = ReaderMemoryController(f.root, scope, { store }, writer)
                controller.bindAccepted(task, ReaderTranslationPresentation.receipt(task)); controller.enterReader(); controller.openPage(0); controller.selectBubble(0)
                held.set(true)
                val save = async(Dispatchers.Default) { controller.save(MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) }
                try {
                    assertTrue(prepared.await(5, TimeUnit.SECONDS))
                    withTimeout(1_000) { withContext(Dispatchers.Default) { controller.leaveReader() } }
                    release.countDown(); save.await()
                    assertNull(SeriesMemoryStore(f.root).inspectChapter(f.chapter.id).bubbles.single().correction)
                    assertFalse(controller.state.value.open); assertTrue(controller.state.value.personalOverlays.isEmpty())
                } finally { release.countDown(); save.cancelAndJoin() }
            } finally { release.countDown(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }


    @Test fun heldBubbleSelectionCannotAttachToADismissedAndReopenedSamePage() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val hold = AtomicBoolean(false); val prepared = CountDownLatch(1); val release = CountDownLatch(1)
            val writer = MemoryJournalWriter { file, bytes ->
                val pending = AtomicMemoryJournalWriter.prepare(file, bytes)
                try { if (hold.getAndSet(false)) { prepared.countDown(); check(release.await(5, TimeUnit.SECONDS)) }; pending }
                catch (problem: Throwable) { pending.close(); throw problem }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val controller = ReaderMemoryController(f.root, scope, { store }, writer)
                controller.bindAccepted(task, ReaderTranslationPresentation.receipt(task)); controller.enterReader(); controller.openPage(0)
                hold.set(true)
                val selection = async(Dispatchers.Default) { controller.selectBubble(0) }
                try {
                    assertTrue(prepared.await(5, TimeUnit.SECONDS))
                    controller.dismiss(); controller.openPage(0)
                    assertNull(controller.state.value.editor)
                    release.countDown(); selection.await()
                    assertNull("An earlier panel's editor attached to a new opening", controller.state.value.editor)
                } finally { release.countDown(); selection.cancelAndJoin() }
            } finally { release.countDown(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun heldSaveCannotClearANewerSamePageOpeningLoadingState() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val nativeHold = AtomicBoolean(false); val nativeEntered = CountDownLatch(1); val nativeRelease = CountDownLatch(1)
            val store = ChapterTranslationStore(f.journals, f.sources, f.io, originalDimensions = { f.actualSourceDimensions }) { file ->
                if (nativeHold.getAndSet(false)) { nativeEntered.countDown(); check(nativeRelease.await(5, TimeUnit.SECONDS)) }
                file.readText().startsWith("valid surface:")
            }
            val task = f.completed(nativeStore = store)
            val hold = AtomicBoolean(false); val prepared = CountDownLatch(1); val release = CountDownLatch(1)
            val writer = MemoryJournalWriter { file, bytes ->
                val pending = AtomicMemoryJournalWriter.prepare(file, bytes)
                try { if (hold.getAndSet(false)) { prepared.countDown(); check(release.await(5, TimeUnit.SECONDS)) }; pending }
                catch (problem: Throwable) { pending.close(); throw problem }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val controller = ReaderMemoryController(f.root, scope, { store }, writer)
                controller.bindAccepted(task, ReaderTranslationPresentation.receipt(task)); controller.enterReader(); controller.openPage(0); controller.selectBubble(0)
                hold.set(true)
                val save = async(Dispatchers.Default) { controller.save(MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) }
                var opening: Deferred<Unit>? = null
                try {
                    assertTrue(prepared.await(5, TimeUnit.SECONDS))
                    controller.dismiss(); nativeHold.set(true)
                    opening = async(Dispatchers.Default) { controller.openPage(0) }
                    assertTrue(nativeEntered.await(5, TimeUnit.SECONDS))
                    assertTrue(controller.state.value.busy)
                    release.countDown(); save.await()
                    assertTrue("An old save cleared a new opening's loading state", controller.state.value.busy)
                    assertNull(controller.state.value.editor)
                } finally { release.countDown(); nativeRelease.countDown(); save.cancelAndJoin(); opening?.cancelAndJoin() }
            } finally { release.countDown(); nativeRelease.countDown(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    private fun environment(action: suspend (NativeMemoryPublicationAdapterTest.Fixture, ChapterTranslationStore, ReaderMemoryController, ChapterTranslationTask) -> Unit) = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val controller = ReaderMemoryController(f.root, scope, { store })
                controller.bindAccepted(task, ReaderTranslationPresentation.receipt(task)); controller.enterReader()
                action(f, store, controller, task)
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(2_000) { while (!condition()) delay(10) }
}
