package com.mangalens.ui.video

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SubtitleWindowProcessorTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("source-speech-processor").toFile()
        val store = SubtitleGenerationStore(root, object : SubtitleJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
        })
        val source = SubtitleSourceIdentity(SubtitleMediaSource("content://fixture/audio"), "a".repeat(64))
        val config = SubtitleGenerationConfig(modelSha256 = "b".repeat(64), threads = 2).withTarget(SubtitleTargetOptions(targetLanguage = "hi"))
        val task = store.start(source, config)
        val original = listOf(SpeechCue(500, 2500, "Where are you?"), SpeechCue(3000, 5000, "Come here."))
        val targets = listOf("तुम कहाँ हो?", "यहाँ आओ।")
        fun processor(task: SubtitleGenerationTask = this.task,
            translator: suspend (SubtitleGenerationTask, SubtitleWindow, Int) -> SubtitleTranslatedCue) =
            SubtitleWindowProcessor(store, task, { if (!store.current(task.id, task.generation)) throw CancellationException() }, translator)
        override fun close() { root.deleteRecursively() }
    }

    @Test fun originalSpeechCommitsBeforeTranslationAndFailedCueRetryKeepsItsSuccessfulNeighbour() = runBlocking {
        Fixture().use { f ->
            var recognitions = 0
            val calls = ArrayList<Int>()
            val first = f.processor { _, _, index ->
                assertEquals("Recognition must be durable before the first provider call", f.original, f.store.get(f.task.id)!!.windows.single().sourceCues)
                calls += index
                if (index == 1) error("translation model unavailable")
                SubtitleTranslatedCue(index, f.targets[index])
            }.process(0, 0, 8000, "c".repeat(64), 8000, false) { recognitions++; SubtitleRecognizedWindow(f.original, "en") }
            assertEquals(1, first.failedTargets)
            assertEquals(1, f.store.get(f.task.id)!!.pendingTargetCues)
            f.store.fail(f.task.id, f.task.generation, first.error!!)
            val resumed = f.store.resume(f.task.id, f.task.generation)!!
            calls.clear()
            f.processor(resumed) { _, _, index -> calls += index; SubtitleTranslatedCue(index, f.targets[index]) }
                .process(0, 0, 8000, "c".repeat(64), 8000, false) { recognitions++; error("Saved original ASR was repeated") }
            assertEquals(1, recognitions)
            assertEquals(listOf(1), calls)
            assertEquals(0, f.store.get(f.task.id)!!.pendingTargetCues)
        }
    }

    @Test fun pauseDuringTargetProviderWaitRejectsItsLateResultWithoutLosingOriginalSpeech() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pending = async {
                f.processor { _, _, index -> entered.complete(Unit); release.await(); SubtitleTranslatedCue(index, f.targets[index]) }
                    .process(0, 0, 8000, "c".repeat(64), 8000, false) { SubtitleRecognizedWindow(f.original, "en") }
            }
            entered.await()
            f.store.pause(f.task.id, f.task.generation)
            release.complete(Unit)
            try { pending.await(); fail("A paused generation accepted a late target") }
            catch (_: CancellationException) { }
            val saved = f.store.get(f.task.id)!!
            assertEquals(SubtitleGenerationStatus.PAUSED, saved.status)
            assertEquals(f.original, saved.sourceCues)
            assertTrue(saved.windows.single().translations.isEmpty())
        }
    }

    @Test fun positivelyDifferentDecodedPcmInvalidatesOriginalAndTargetCheckpoints() = runBlocking {
        Fixture().use { f ->
            f.processor { _, _, index -> SubtitleTranslatedCue(index, f.targets[index]) }
                .process(0, 0, 8000, "c".repeat(64), 8000, false) { SubtitleRecognizedWindow(f.original, "en") }
            try {
                f.processor { _, _, _ -> error("Changed audio was translated") }
                    .process(0, 0, 8000, "d".repeat(64), 8000, false) { error("Changed audio reused old ASR") }
                fail("Changed PCM kept old speech")
            } catch (_: CancellationException) { }
            assertTrue(f.store.get(f.task.id)!!.windows.isEmpty())
        }
    }

    @Test fun changingOnlyTargetReusesProvenOriginalSpeechButClearsOldTargetCheckpoints() = runBlocking {
        Fixture().use { f ->
            f.processor { _, _, index -> SubtitleTranslatedCue(index, f.targets[index]) }
                .process(0, 0, 8000, "c".repeat(64), 8000, false) { SubtitleRecognizedWindow(f.original, "en") }
            f.store.completeAudio(f.task.id, f.task.generation, 1)
            f.store.finish(f.task.id, f.task.generation)
            val hinglish = f.store.start(f.source, f.config.copy(targetLanguage = "hi-latn"))
            assertTrue("A target change should retain a proof-bound finished original track", hinglish.audioComplete)
            assertEquals(f.original, hinglish.sourceCues)
            assertTrue(hinglish.windows.single().translations.isEmpty())
            assertTrue(f.store.start(f.source.copy(fingerprint = "e".repeat(64)), f.config).windows.isEmpty())
            assertTrue(f.store.start(f.source, f.config.copy(modelSha256 = "f".repeat(64))).windows.isEmpty())
        }
    }

    @Test fun providerCloseWaitsForActualPlatformCompletionAfterCallerCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finishNative = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val lifetime = SubtitleProviderLifetime { released.complete(Unit) }
        val caller = async { lifetime.run { entered.complete(Unit); finishNative.await(); "target" } }
        entered.await()
        caller.cancelAndJoin()
        lifetime.close()
        assertFalse("An active provider was closed before its actual callback", released.isCompleted)
        finishNative.complete(Unit)
        withTimeout(2000) { released.await() }
    }

    @Test fun queuedCancelledProviderDoesNoLateInferenceAndReleasesWithoutWaitingForOtherProvider() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val first = SubtitleProviderLifetime { }
        val running = async { first.run { firstEntered.complete(Unit); finishFirst.await() } }
        firstEntered.await()
        val secondReleased = CompletableDeferred<Unit>()
        val second = SubtitleProviderLifetime { secondReleased.complete(Unit) }
        var called = false
        val queued = async { second.run { called = true } }
        kotlinx.coroutines.yield()
        queued.cancelAndJoin()
        second.close()
        withTimeout(2000) { secondReleased.await() }
        assertFalse(called)
        finishFirst.complete(Unit)
        running.await()
        first.close()
    }
}
