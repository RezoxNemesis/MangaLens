package com.mangalens.ui.video

import com.mangalens.download.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Durable production transitions, not mock receipts or metadata-only completion. */
class ProviderCaptionStoreLifecycleTest {
    @Test fun reopeningMasksProviderExportsAndMediaValidationCannotSubstituteForCaptionRefetch() =
        CaptionFixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            val saved = f.checkpoint(store, task); val receipt = requireNotNull(saved.providerCaptionReceipt)
            saved.windows.forEach { window -> window.sourceCues.indices.forEach { index ->
                store.checkpointProviderTarget(task.id, task.generation, window.index, receipt,
                    SubtitleTranslatedCue(index, window.sourceCues[index].text))
            } }
            store.finish(task.id, task.generation)
            val reopened = f.store()
            assertTrue("Reopened provider text bypassed fresh document verification", requireNotNull(reopened.get(task.id)).validationPending)
            assertNull(reopened.exportVerified(task.id, task.generation))
            reopened.confirmValidated(task.id, task.generation, pcmVerified = true)
            assertTrue("Decoded media validation forged provider-caption confirmation", requireNotNull(reopened.get(task.id)).validationPending)
        }

    @Test fun completeProviderDocumentExportsEvenWhenTheLastDialoguePrecedesVideoEnd() =
        CaptionFixture().use { f ->
            val store = f.store()
            val task = store.start(f.source, f.config)
            assertEquals(SubtitleGenerationStatus.QUEUED, task.status)
            assertNull(task.config.modelSha256)
            val document = f.checkpoint(store, task)
            val receipt = requireNotNull(document.providerCaptionReceipt)
            document.windows.forEach { window -> window.sourceCues.indices.forEach { index ->
                assertTrue(store.checkpointProviderTarget(task.id, task.generation, window.index, receipt,
                    SubtitleTranslatedCue(index, window.sourceCues[index].text)))
            } }
            val complete = requireNotNull(store.finish(task.id, task.generation))
            assertEquals(SubtitleGenerationStatus.COMPLETED, complete.status)
            assertFalse(complete.audioComplete)
            assertEquals(60_000L, complete.durationMs)
            assertEquals(5_000L, complete.processedMs)
            assertTrue(complete.windows.all { it.pcmSha256.isEmpty() })
            assertTrue(hasVerifiedProviderCaptions(complete))
            assertEquals(SubtitleFormats.srt(complete.cues), File(complete.srtPath!!).readText())
            assertEquals(SubtitleFormats.vtt(complete.cues), File(complete.vttPath!!).readText())
            assertNotNull(store.exportVerified(task.id, task.generation))
        }

    @Test fun pauseResumeRetainsTargetsButMasksTheirPublicationUntilFreshExactDocumentProof() =
        CaptionFixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            val saved = f.checkpoint(store, task); val receipt = requireNotNull(saved.providerCaptionReceipt)
            assertTrue(store.checkpointProviderTarget(task.id, task.generation, 0, receipt,
                SubtitleTranslatedCue(0, saved.windows[0].sourceCues[0].text)))
            store.pause(task.id, task.generation)
            val resumed = requireNotNull(store.resume(task.id, task.generation))
            assertNotEquals(task.generation, resumed.generation)
            assertEquals(1, resumed.translatedCueCount)
            assertTrue(resumed.validationPending)
            assertTrue(resumed.cues.isEmpty())
            assertFalse(hasVerifiedProviderCaptions(resumed))
            assertFalse(store.checkpointProviderTarget(task.id, task.generation, 0, receipt,
                SubtitleTranslatedCue(1, "The next line.")))
            val freshReceipt = providerReceipt(resumed, f.track, f.document)
            val confirmed = requireNotNull(store.confirmProviderDocument(resumed.id, resumed.generation,
                freshReceipt, providerCaptionWindows(f.document, "en")))
            assertEquals(1, confirmed.translatedCueCount)
            assertFalse(confirmed.validationPending)
            assertTrue(hasVerifiedProviderCaptions(confirmed))
        }

    @Test fun changedDocumentDoesNotReplaceSavedOriginalsOrSuccessfulTargetCheckpoints() =
        CaptionFixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            val saved = f.checkpoint(store, task)
            val before = store.get(task.id)
            val changed = ProviderCaptionParser.parse(
                "WEBVTT\n\n00:01.000 --> 00:03.000\nAn unrelated later document.\n".toByteArray(),
                ProviderCaptionFormat.VTT, 60_000)
            try {
                store.confirmProviderDocument(task.id, task.generation, providerReceipt(saved, f.track, changed),
                    providerCaptionWindows(changed, "en"))
                fail("Changed provider document was silently adopted")
            } catch (_: ProviderCaptionChangedException) { }
            assertEquals(before, store.get(task.id))
            assertEquals(before?.windows, f.store().get(task.id)?.windows)
        }

    @Test fun providerCannotEnterAsrPcmOrAudioCompletionTransitions() = CaptionFixture().use { f ->
        val store = f.store(); val task = store.start(f.source, f.config)
        f.checkpoint(store, task)
        try { store.completeAudio(task.id, task.generation, 1); fail("Captions completed decoded audio") }
        catch (_: IllegalArgumentException) { }
        try {
            store.checkpoint(task.id, task.generation, SubtitleWindow(1, 5000, 8000, "c".repeat(64)), 60_000)
            fail("PCM entered provider-caption task")
        } catch (_: IllegalArgumentException) { }
        assertFalse(requireNotNull(store.get(task.id)).audioComplete)
    }

    @Test fun failedAtomicDocumentAndTargetWritesRetainTheLastPublishedState() = CaptionFixture().use { f ->
        val store = f.store(); val task = store.start(f.source, f.config)
        val before = store.get(task.id)
        f.failWrite = true
        try { f.checkpoint(store, task); fail("Interrupted document was published") } catch (_: IOException) { }
        f.failWrite = false
        assertEquals(before, store.get(task.id))
        val saved = f.checkpoint(store, task); val receipt = requireNotNull(saved.providerCaptionReceipt)
        f.failWrite = true
        try {
            store.checkpointProviderTarget(task.id, task.generation, 0, receipt,
                SubtitleTranslatedCue(0, saved.windows[0].sourceCues[0].text))
            fail("Interrupted target was published")
        } catch (_: IOException) { }
        f.failWrite = false
        assertEquals(saved, store.get(task.id))
        assertEquals(saved.windows, f.store().get(task.id)?.windows)
    }

    @Test fun exportFailureNeverPublishesCompletedOrDiscardsOriginalDocument() = CaptionFixture().use { f ->
        val store = f.store(); val task = store.start(f.source, f.config)
        val saved = f.checkpoint(store, task); val receipt = requireNotNull(saved.providerCaptionReceipt)
        saved.windows.forEach { window -> window.sourceCues.indices.forEach { index ->
            store.checkpointProviderTarget(task.id, task.generation, window.index, receipt,
                SubtitleTranslatedCue(index, window.sourceCues[index].text))
        } }
        f.failSuffix = ".vtt"
        try { store.finish(task.id, task.generation); fail("One export was called complete") }
        catch (_: IOException) { }
        f.failSuffix = null
        assertNotEquals(SubtitleGenerationStatus.COMPLETED, store.get(task.id)?.status)
        assertNull(store.exportVerified(task.id, task.generation))
        assertEquals(f.document.cues.size, store.get(task.id)?.sourceCueCount)
        assertNotNull(store.get(task.id)?.providerCaptionReceipt)
    }

    @Test fun cancellationAndReplacementRejectLateProviderDocumentsAndTargets() = CaptionFixture().use { f ->
        val store = f.store(); val old = store.start(f.source, f.config)
        val saved = f.checkpoint(store, old); val receipt = requireNotNull(saved.providerCaptionReceipt)
        store.cancel(old.id, old.generation)
        assertNull(store.confirmProviderDocument(old.id, old.generation, receipt, saved.windows))
        val replacement = store.start(f.source, f.config, force = true)
        assertNull(store.checkpointProviderDocument(old.id, old.generation, receipt, saved.windows))
        assertFalse(store.checkpointProviderTarget(old.id, old.generation, 0, receipt,
            SubtitleTranslatedCue(0, saved.windows[0].sourceCues[0].text)))
        assertEquals(replacement.generation, store.get(old.id)?.generation)
        assertTrue(requireNotNull(store.get(old.id)).windows.isEmpty())
    }
}

internal class CaptionFixture : AutoCloseable {
    val directory = Files.createTempDirectory("caption-production-lifecycle-").toFile()
    var failWrite = false
    var failSuffix: String? = null
    val page = "https://www.youtube.com/watch?v=abcdefghijk"
    val track = ProviderCaptionTrack("https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en",
        "en", ProviderCaptionKind.MANUAL, ProviderCaptionFormat.VTT)
    val inventory = ProviderCaptionInventory(page, "abcdefghijk", "en", "en", 60_000, listOf(track))
    val source = SubtitleSourceIdentity(SubtitleMediaSource("https://video.fixture.invalid/media", cacheKey = page,
        providerCaptions = inventory, sourceResolutionId = "f".repeat(32)), "a".repeat(64), verifiable = false)
    val config = SubtitleGenerationConfig().withTarget(SubtitleTargetOptions(targetLanguage = "en", sceneContext = "A locked door."))
    val document = ProviderCaptionParser.parse(
        "WEBVTT\n\n00:01.000 --> 00:03.000\nDon't open the door.\n\n00:04.000 --> 00:05.000\nPlease wait here.\n".toByteArray(),
        ProviderCaptionFormat.VTT, 60_000)
    private val io = object : SubtitleJournalIo {
        override fun read(file: File) = file.readBytes()
        override fun write(file: File, bytes: ByteArray) {
            if (failWrite || failSuffix?.let(file.name::endsWith) == true) throw IOException("Interrupted fixture write")
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".new").apply { writeBytes(bytes) }
            Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
    fun store() = SubtitleGenerationStore(directory, io)
    fun checkpoint(store: SubtitleGenerationStore, task: SubtitleGenerationTask): SubtitleGenerationTask =
        requireNotNull(store.checkpointProviderDocument(task.id, task.generation, providerReceipt(task, track, document),
            providerCaptionWindows(document, "en")))
    override fun close() { directory.deleteRecursively() }
}
