package com.mangalens.ui.video

import com.mangalens.ui.web.browserCaptionPublicationCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Unit suspension boundary with actual completed/fysnced/export-verified Store proof, not a fake JS/runtime claim. */
class BrowserCaptionPublicationFenceTest {
    @Test fun authorityRetiredDuringClockAwaitCannotPublishThePreviouslyVerifiedResult() = heldBoundary { f, store, _, operation ->
        store.browserCaptionAuthority.revoke(operation)
    }
    @Test fun exactTaskCancelledDuringClockAwaitCannotPublishThePreviouslyVerifiedResult() = heldBoundary { _, store, exported, _ ->
        assertNotNull(store.cancel(exported.id, exported.generation))
    }
    @Test fun newGenerationDuringClockAwaitCannotPublishThePreviouslyVerifiedResult() = heldBoundary { f, store, exported, _ ->
        val replacement = f.start(store, f.operation(store))
        assertNotEquals(exported.generation, replacement.generation)
    }
    @Test fun unchangedLiveAuthorityAndExactJournalStillAcceptGenuineCompletedProof() {
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val operation = f.operation(store); val completed = complete(f, store, operation)
            assertTrue(browserCaptionPublicationCurrent(store.browserCaptionAuthority, completed, store.states.value))
            assertEquals(2, completed.cues.size)
        }
    }
    private fun heldBoundary(replace: (BrowserCaptionStoreFixture, SubtitleGenerationStore,
            SubtitleGenerationTask, BrowserCaptionOperation) -> Unit) {
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val operation = f.operation(store); val completed = complete(f, store, operation)
            assertTrue(browserCaptionPublicationCurrent(store.browserCaptionAuthority, completed, store.states.value))
            runBlocking {
                val awaitingClock = CompletableDeferred<Unit>(); val actualClockResponse = CompletableDeferred<Unit>()
                val publication = async {
                    awaitingClock.complete(Unit)
                    actualClockResponse.await()
                    browserCaptionPublicationCurrent(store.browserCaptionAuthority, completed, store.states.value)
                }
                awaitingClock.await()
                replace(f, store, completed, operation)
                actualClockResponse.complete(Unit)
                assertFalse("The previous verified result crossed the changed post-clock publication boundary", publication.await())
            }
        }
    }
    private fun complete(f: BrowserCaptionStoreFixture, store: SubtitleGenerationStore,
            operation: BrowserCaptionOperation): SubtitleGenerationTask {
        val task = f.start(store, operation); val document = f.document(store, task)
        val receipt = requireNotNull(document.providerCaptionReceipt)
        document.windows.forEach { window -> window.sourceCues.forEachIndexed { index, cue ->
            assertTrue(store.checkpointProviderTarget(task.id, task.generation, window.index, receipt, SubtitleTranslatedCue(index, cue.text)))
        } }
        requireNotNull(store.finish(task.id, task.generation))
        return requireNotNull(store.exportVerified(task.id, task.generation))
    }
}
