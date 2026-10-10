package com.mangalens.ui.video

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** These exercise the actual Store after an actual fsynced stage, before its final commit gate. */
class BrowserCaptionStoreAuthorityTest {
    @Test fun retirementBeforeStoreBindingCannotPublishAQueuedGeneration() =
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val operation = f.operation(store)
            store.browserCaptionAuthority.revoke(operation)
            assertThrows(BrowserCaptionAuthorityRetired::class.java) { f.start(store, operation) }
            assertTrue(store.states.value.isEmpty())
            assertTrue(f.store().states.value.isEmpty())
        }

    @Test fun explicitPausedResumeKeepsCapturedTargetsStyleAndContextUnderAFreshLiveGeneration() =
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val operation = f.operation(store); val task = f.start(store, operation)
            val original = f.document(store, task); val receipt = requireNotNull(original.providerCaptionReceipt)
            val target = SubtitleTranslatedCue(0, original.windows[0].sourceCues[0].text)
            assertTrue(store.checkpointProviderTarget(task.id, task.generation, 0, receipt, target))
            val paused = requireNotNull(store.pause(task.id, task.generation))
            store.browserCaptionAuthority.revoke(operation)
            assertThrows(BrowserCaptionAuthorityRetired::class.java) { store.resume(paused.id, paused.generation) }
            assertEquals(paused, store.get(paused.id))
            val currentOperation = f.operation(store)
            val resumed = requireNotNull(store.resume(paused.id, paused.generation, currentOperation))
            assertNotEquals(paused.generation, resumed.generation)
            assertEquals(paused.source, resumed.source)
            assertEquals(paused.config, resumed.config)
            assertEquals(paused.windows, resumed.windows)
            assertTrue(resumed.validationPending)
            assertFalse(resumed.audioComplete)
            assertNull(resumed.config.modelSha256)
            assertFalse(store.checkpointProviderTarget(paused.id, paused.generation, 0, receipt, target))
            val confirmed = f.document(store, resumed)
            assertFalse(confirmed.validationPending)
            assertEquals(target, confirmed.windows[0].translations.single())
            assertTrue(store.browserCaptionAuthority.permits(f.binding(confirmed)))
        }

    @Test fun navigationTabAndSourceRetirementRejectARealPreparedProviderDocument() {
        listOf("navigation", "tab", "video source").forEach { replacement ->
            BrowserCaptionStoreFixture().use { f ->
                val store = f.store(); val operation = f.operation(store); val task = f.start(store, operation)
                val before = requireNotNull(store.get(task.id))
                f.holdNextManifest()
                f.runHeld(store, operation) { f.document(store, task) }
                assertEquals("$replacement published an old document", before, store.get(task.id))
                val reopened = f.store()
                assertNull(reopened.get(task.id)?.providerCaptionReceipt)
                assertTrue(requireNotNull(reopened.get(task.id)).windows.isEmpty())
            }
        }
    }

    @Test fun retirementRejectsARealPreparedTargetWithoutDiscardingTheOriginalDocument() =
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val operation = f.operation(store); val task = f.start(store, operation)
            val document = f.document(store, task); val receipt = requireNotNull(document.providerCaptionReceipt)
            f.holdNextManifest()
            f.runHeld(store, operation) {
                store.checkpointProviderTarget(task.id, task.generation, 0, receipt,
                    SubtitleTranslatedCue(0, document.windows[0].sourceCues[0].text))
            }
            assertEquals(document, store.get(task.id))
            assertEquals(0, f.store().get(task.id)?.translatedCueCount)
            assertNotNull(f.store().get(task.id)?.providerCaptionReceipt)
        }

    @Test fun retirementRejectsFinalCompletedManifestAfterActualExportsWereWritten() =
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val operation = f.operation(store); val task = f.start(store, operation)
            val document = f.document(store, task); val receipt = requireNotNull(document.providerCaptionReceipt)
            document.windows.forEach { window -> window.sourceCues.forEachIndexed { index, cue ->
                assertTrue(store.checkpointProviderTarget(task.id, task.generation, window.index, receipt,
                    SubtitleTranslatedCue(index, cue.text)))
            } }
            val before = requireNotNull(store.get(task.id))
            f.holdNextManifest()
            f.runHeld(store, operation) { store.finish(task.id, task.generation) }
            assertEquals(before, store.get(task.id))
            assertNull(store.exportVerified(task.id, task.generation))
            val reopened = f.store()
            assertNotEquals(SubtitleGenerationStatus.COMPLETED, reopened.get(task.id)?.status)
            assertNull(reopened.exportVerified(task.id, task.generation))
        }

    @Test fun retiringDuringStartCannotPublishOrScheduleAnUnboundOldGeneration() =
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val operation = f.operation(store)
            f.holdNextManifest()
            f.runHeld(store, operation) { f.start(store, operation) }
            assertTrue(store.states.value.isEmpty())
            assertTrue(f.store().states.value.isEmpty())
        }

    @Test fun explicitRestartHasANewGenerationAndOldNonceCannotCancelItsAuthority() =
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val oldOperation = f.operation(store); val old = f.start(store, oldOperation)
            val newerOperation = f.operation(store); val newer = f.start(store, newerOperation)
            assertNotEquals(old.generation, newer.generation)
            assertNull(store.browserCaptionAuthority.revoke(oldOperation))
            assertTrue(store.browserCaptionAuthority.permits(f.binding(newer)))
            assertNull(f.documentOrNull(store, old))
            assertEquals(newer.generation, store.get(old.id)?.generation)
            assertNotNull(f.document(store, newer).providerCaptionReceipt)
        }

    @Test fun coldJournalCannotRestoreBrowserAuthorityOrClearItsProviderMask() =
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val operation = f.operation(store); val task = f.start(store, operation)
            val document = f.document(store, task); val receipt = requireNotNull(document.providerCaptionReceipt)
            val cold = f.store(); val saved = requireNotNull(cold.get(task.id))
            assertTrue(saved.validationPending)
            assertFalse(cold.browserCaptionAuthority.permits(f.binding(saved)))
            assertThrows(BrowserCaptionAuthorityRetired::class.java) {
                cold.confirmProviderDocument(saved.id, saved.generation, receipt, saved.windows)
            }
            assertTrue(requireNotNull(cold.get(task.id)).validationPending)
            assertFalse(cold.current(saved.id, saved.generation))
            val currentOperation = f.operation(cold)
            val explicitRestart = f.start(cold, currentOperation)
            assertNotEquals(saved.generation, explicitRestart.generation)
            assertEquals(saved.config, explicitRestart.config)
            assertEquals(saved.source, explicitRestart.source)
            assertNotNull(f.document(cold, explicitRestart).providerCaptionReceipt)
        }
}

internal class BrowserCaptionStoreFixture : AutoCloseable {
    private val captions = CaptionFixture()
    val source = captions.source.copy(source = captions.source.source.copy(uri = captions.page,
        headers = emptyMap(), captionDocumentOnly = true))
    val config = captions.config
    private var prepared: CountDownLatch? = null
    private var release: CountDownLatch? = null
    private val io = object : SubtitleJournalIo {
        override fun read(file: File): ByteArray = file.readBytes()
        override fun write(file: File, bytes: ByteArray) = prepareSubtitleJournal(file, bytes).use { it.commit() }
        override fun stage(file: File, bytes: ByteArray): SubtitleStagedJournal {
            val stage = prepareSubtitleJournal(file, bytes)
            if (file.extension == "json") {
                val heldPrepared = prepared; val heldRelease = release
                if (heldPrepared != null && heldRelease != null) {
                    prepared = null
                    heldPrepared.countDown()
                    try { check(heldRelease.await(8, TimeUnit.SECONDS)) }
                    catch (failure: Throwable) { stage.close(); throw failure }
                }
            }
            return stage
        }
    }
    fun store() = SubtitleGenerationStore(captions.directory, io)
    fun operation(store: SubtitleGenerationStore) = store.browserCaptionAuthority.begin(
        BrowserCaptionAuthorityKey(requireNotNull(source.source.sourceResolutionId), source.fingerprint, config.fingerprint()))
    fun start(store: SubtitleGenerationStore, operation: BrowserCaptionOperation) = store.startBrowser(source, config, operation)
    fun binding(task: SubtitleGenerationTask) = BrowserCaptionTaskBinding(
        BrowserCaptionAuthorityKey(requireNotNull(task.source.source.sourceResolutionId), task.source.fingerprint, task.config.fingerprint()),
        task.id, task.generation)
    fun document(store: SubtitleGenerationStore, task: SubtitleGenerationTask): SubtitleGenerationTask =
        requireNotNull(documentOrNull(store, task))
    fun documentOrNull(store: SubtitleGenerationStore, task: SubtitleGenerationTask): SubtitleGenerationTask? =
        store.checkpointProviderDocument(task.id, task.generation,
            providerReceipt(task, captions.track, captions.document), providerCaptionWindows(captions.document, "en"))
    fun holdNextManifest() { prepared = CountDownLatch(1); release = CountDownLatch(1) }
    fun runHeld(store: SubtitleGenerationStore, operation: BrowserCaptionOperation, action: () -> Unit) {
        val waiting = requireNotNull(prepared); val unblock = requireNotNull(release)
        val finished = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try { action() } catch (caught: Throwable) { failure.set(caught) }
            finally { finished.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue("Actual IO stage never reached its held final commit", waiting.await(8, TimeUnit.SECONDS))
            val revoked = CountDownLatch(1)
            Thread { store.browserCaptionAuthority.revoke(operation); revoked.countDown() }.apply { isDaemon = true; start() }
            assertTrue("Main retirement waited on the Store or its staged IO", revoked.await(2, TimeUnit.SECONDS))
        } finally { unblock.countDown() }
        assertTrue("Held store command did not finish", finished.await(8, TimeUnit.SECONDS))
        thread.join(100)
        assertTrue("Prepared old journal passed its final authorization: ${failure.get()}",
            failure.get() is BrowserCaptionAuthorityRetired)
        release = null
    }
    override fun close() { release?.countDown(); captions.close() }
}
