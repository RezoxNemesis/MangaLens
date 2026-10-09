package com.mangalens.ui.video

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production processor called by the actual Worker, with real Store and parser. */
class ProviderCaptionProcessorTest {
    @Test fun genuineProviderDocumentCompletesWithoutWhisperOrAudioTailFabrication() = runBlocking {
        CaptionFixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            var fetches = 0; var translations = 0
            val processor = ProviderCaptionProcessor(store, task, owner(store, task), fetch = { inventory, language, saved ->
                assertEquals(f.inventory, inventory); assertEquals("auto", language); assertNull(saved)
                fetches++; FetchedProviderCaptions(f.track, f.document)
            }, translate = { captured, window, index ->
                assertEquals(f.config, captured.config); assertEquals("A locked door.", captured.config.sceneContext)
                translations++; SubtitleTranslatedCue(index, window.sourceCues[index].text)
            })
            assertTrue(processor.process())
            assertEquals(1, fetches); assertEquals(2, translations)
            val complete = requireNotNull(store.get(task.id))
            assertEquals(SubtitleGenerationStatus.COMPLETED, complete.status)
            assertFalse(complete.audioComplete); assertNull(complete.config.modelSha256)
            assertEquals(5000L, complete.processedMs); assertNotNull(complete.srtPath)
        }
    }

    @Test fun unavailableFreshCaptionsAllowAsrFallbackWithoutAnyCaptionCheckpoint() = runBlocking {
        CaptionFixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config.copy(modelSha256 = "b".repeat(64)))
            val processor = ProviderCaptionProcessor(store, task, owner(store, task), fetch = { _, _, _ ->
                throw ProviderCaptionUnavailable()
            }, translate = { _, _, _ -> error("Unavailable caption reached translator") })
            assertFalse(processor.process())
            assertNull(store.get(task.id)?.providerCaptionReceipt)
            assertTrue(requireNotNull(store.get(task.id)).windows.isEmpty())
        }
    }

    @Test fun unavailableSavedDocumentNeverFallsBackToAsrOrPublishesSavedCues() = runBlocking {
        CaptionFixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            f.checkpoint(store, task); store.pause(task.id, task.generation)
            val resumed = requireNotNull(store.resume(task.id, task.generation))
            val processor = ProviderCaptionProcessor(store, resumed, owner(store, resumed), fetch = { _, _, saved ->
                assertNotNull(saved); throw ProviderCaptionUnavailable()
            }, translate = { _, _, _ -> error("Unverified saved caption reached translator") })
            try { processor.process(); fail("Saved caption failure allowed ASR fallback") }
            catch (_: ProviderCaptionUnavailable) { }
            val retained = requireNotNull(store.get(task.id))
            assertNotNull(retained.providerCaptionReceipt)
            assertEquals(2, retained.sourceCueCount)
            assertTrue(retained.validationPending); assertTrue(retained.cues.isEmpty())
            assertNull(store.exportVerified(task.id, resumed.generation))
        }
    }

    @Test fun resumeVerifiesSavedDocumentThenTranslatesOnlyUnfinishedTargets() = runBlocking {
        CaptionFixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            val saved = f.checkpoint(store, task)
            store.checkpointProviderTarget(task.id, task.generation, 0, requireNotNull(saved.providerCaptionReceipt),
                SubtitleTranslatedCue(0, saved.windows[0].sourceCues[0].text))
            store.pause(task.id, task.generation); val resumed = requireNotNull(store.resume(task.id, task.generation))
            val indices = mutableListOf<Int>()
            val processor = ProviderCaptionProcessor(store, resumed, owner(store, resumed), fetch = { _, _, proof ->
                assertEquals(saved.providerCaptionReceipt, proof); FetchedProviderCaptions(f.track, f.document)
            }, translate = { captured, window, index ->
                assertEquals(f.config, captured.config); indices += index
                SubtitleTranslatedCue(index, window.sourceCues[index].text)
            })
            assertTrue(processor.process()); assertEquals(listOf(1), indices)
            assertEquals(SubtitleGenerationStatus.COMPLETED, store.get(task.id)?.status)
        }
    }

    @Test fun cancelledRequestWhileFetchIsSuspendedCannotCheckpointItsLateDocument() = runBlocking {
        CaptionFixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val processor = ProviderCaptionProcessor(store, task, owner(store, task), fetch = { _, _, _ ->
                entered.complete(Unit); release.await(); FetchedProviderCaptions(f.track, f.document)
            }, translate = { _, _, _ -> error("Cancelled document reached translator") })
            val work = async { runCatching { processor.process() } }
            assertNotNull("Production processor never reached provider fetch", withTimeoutOrNull(1000) { entered.await(); true })
            store.cancel(task.id, task.generation); release.complete(Unit)
            assertTrue(work.await().isFailure)
            assertEquals(SubtitleGenerationStatus.CANCELLED, store.get(task.id)?.status)
            assertNull(store.get(task.id)?.providerCaptionReceipt)
            assertTrue(requireNotNull(store.get(task.id)).windows.isEmpty())
        }
    }

    @Test fun replacementWhileTranslationIsSuspendedCannotPublishIntoNewGeneration() = runBlocking {
        CaptionFixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val processor = ProviderCaptionProcessor(store, task, owner(store, task), fetch = { _, _, _ ->
                FetchedProviderCaptions(f.track, f.document)
            }, translate = { _, window, index ->
                entered.complete(Unit); release.await(); SubtitleTranslatedCue(index, window.sourceCues[index].text)
            })
            val work = async { runCatching { processor.process() } }
            assertNotNull("Production processor never reached provider translation", withTimeoutOrNull(1000) { entered.await(); true })
            val replacement = store.start(f.source, f.config, force = true); release.complete(Unit)
            assertTrue(work.await().isFailure)
            assertEquals(replacement.generation, store.get(task.id)?.generation)
            assertTrue(requireNotNull(store.get(task.id)).windows.isEmpty())
            assertNull(store.get(task.id)?.srtPath)
        }
    }

    private fun owner(store: SubtitleGenerationStore, task: SubtitleGenerationTask): suspend () -> Unit = {
        enforceSubtitleOwnerGate(store, task) { true }
    }
}
