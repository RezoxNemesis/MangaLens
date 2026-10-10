package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class BrowserCaptionPauseRetirementTest {
    @Test fun lateWorkerRetirementKeepsExplicitPauseAndCannotCancelTheResumedGeneration() {
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val operation = f.operation(store); val task = f.start(store, operation)
            val document = f.document(store, task)
            val receipt = requireNotNull(document.providerCaptionReceipt)
            assertTrue(store.checkpointProviderTarget(task.id, task.generation, 0, receipt,
                SubtitleTranslatedCue(0, document.windows.first().sourceCues.first().text)))
            val paused = requireNotNull(store.pause(task.id, task.generation))
            store.browserCaptionAuthority.revoke(operation)
            assertEquals(paused, store.retireBrowserGeneration(task.id, task.generation))
            assertEquals(SubtitleGenerationStatus.PAUSED, f.store().get(task.id)?.status)
            val resumed = requireNotNull(store.resume(task.id, task.generation, f.operation(store)))
            assertNotEquals(paused.generation, resumed.generation)
            assertEquals(paused.source, resumed.source)
            assertEquals(paused.config, resumed.config)
            assertEquals(paused.windows, resumed.windows)
            assertNull(store.retireBrowserGeneration(task.id, task.generation))
            assertEquals(resumed, store.get(task.id))
            assertTrue(store.browserCaptionAuthority.permits(f.binding(resumed)))
        }
    }

    @Test fun retiredWorkerCancelsRunningSourceButKeepsAlreadyCommittedVerifiedHistory() {
        BrowserCaptionStoreFixture().use { f ->
            val store = f.store(); val operation = f.operation(store); val task = f.start(store, operation)
            store.browserCaptionAuthority.revoke(operation)
            assertEquals(SubtitleGenerationStatus.CANCELLED,
                store.retireBrowserGeneration(task.id, task.generation)?.status)
            assertEquals(SubtitleGenerationStatus.CANCELLED, f.store().get(task.id)?.status)
            val replacement = f.start(store, f.operation(store))
            val document = f.document(store, replacement)
            val receipt = requireNotNull(document.providerCaptionReceipt)
            document.windows.forEach { window -> window.sourceCues.forEachIndexed { index, cue ->
                assertTrue(store.checkpointProviderTarget(replacement.id, replacement.generation,
                    window.index, receipt, SubtitleTranslatedCue(index, cue.text)))
            } }
            val completed = requireNotNull(store.finish(replacement.id, replacement.generation))
            assertEquals(SubtitleGenerationStatus.COMPLETED, completed.status)
            // A source replacement retires presentation authority, not an accepted old history entry.
            val oldBinding = f.binding(completed)
            store.browserCaptionAuthority.begin(oldBinding.key)
            assertEquals(completed, store.retireBrowserGeneration(completed.id, completed.generation))
            assertNull(store.exportVerified(completed.id, completed.generation))
            assertEquals(SubtitleGenerationStatus.COMPLETED, f.store().get(completed.id)?.status)
        }
    }
}
