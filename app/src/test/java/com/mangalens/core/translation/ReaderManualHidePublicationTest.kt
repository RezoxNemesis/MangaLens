package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real staged personal journal publication; display denial does not modify original or Native files. */
class ReaderManualHidePublicationTest {
    @Test fun hidingASelectedSourceRetiresAHeldSaveBeforeActualJournalRename() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { fixture ->
            val store = fixture.store()
            val task = fixture.completed(nativeStore = store)
            val hold = AtomicBoolean(false)
            val prepared = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writer = MemoryJournalWriter { destination, bytes ->
                val pending = AtomicMemoryJournalWriter.prepare(destination, bytes)
                try {
                    if (hold.getAndSet(false)) {
                        prepared.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    pending
                } catch (problem: Throwable) { pending.close(); throw problem }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val controller = ReaderMemoryController(fixture.root, scope, { store }, writer)
                controller.bindAccepted(task, ReaderTranslationPresentation.receipt(task))
                controller.enterReader(); controller.openPage(0); controller.selectBubble(0)
                val oldPresentation = requireNotNull(controller.selectionForTest())
                val source = fixture.source.readBytes()
                val image = File(requireNotNull(task.pages.single().cleanedPath)).readBytes()
                val nativeJournal = fixture.journal(task.id).readBytes()
                val personalJournal = File(fixture.root, "reader_memory/chapters/${fixture.chapter.id}.json")
                val originalPersonal = personalJournal.readBytes()
                hold.set(true)
                val saving = async(Dispatchers.Default) { controller.save(MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) }
                try {
                    assertTrue("The real personal write never reached its staged boundary", prepared.await(5, TimeUnit.SECONDS))
                    withTimeout(1_000) { withContext(Dispatchers.Default) { controller.onManuallyHiddenPages(setOf(0)) } }
                    assertNotEquals(oldPresentation, controller.selectionForTest())
                    assertFalse(controller.state.value.open)
                    controller.openPage(0)
                    assertFalse("A hidden page reopened its personal editor", controller.state.value.open)
                } finally { release.countDown() }
                withTimeout(5_000) { saving.await() }
                assertArrayEquals(originalPersonal, personalJournal.readBytes())
                assertArrayEquals(source, fixture.source.readBytes())
                assertArrayEquals(image, File(requireNotNull(task.pages.single().cleanedPath)).readBytes())
                assertArrayEquals(nativeJournal, fixture.journal(task.id).readBytes())
                controller.onManuallyHiddenPages(emptySet())
                controller.openPage(0); controller.selectBubble(0)
                assertNotNull("Revealing the unchanged page did not restore its editor", controller.state.value.editor)
                assertEquals(0, controller.state.value.editor!!.bubble.editRevision)
            } finally {
                release.countDown()
                scope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }
    }

    @Test fun hidingAnotherVisibleSourceAlsoRetiresAnExistingCapturedEditor() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { fixture ->
            val store = fixture.store(); val task = fixture.completed(nativeStore = store)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val controller = ReaderMemoryController(fixture.root, scope, { store })
                controller.bindAccepted(task, ReaderTranslationPresentation.receipt(task)); controller.enterReader()
                controller.openPage(0); controller.selectBubble(0)
                val old = requireNotNull(controller.selectionForTest())
                controller.onManuallyHiddenPages(setOf(7))
                assertNotEquals(old, controller.selectionForTest())
                assertFalse(controller.state.value.open)
                // A changed presentation invalidates old effects while retaining ordinary current-page access.
                controller.openPage(0); controller.selectBubble(0)
                assertNotNull(controller.state.value.editor)
                assertEquals(0, controller.state.value.editor!!.bubble.editRevision)
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
}
