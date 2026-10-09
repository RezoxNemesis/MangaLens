package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SubtitleGenerationStoreTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("subtitle-journal").toFile()
        var failWrite = false
        var failSuffix: String? = null
        val io = object : SubtitleJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) {
                if (failWrite || failSuffix?.let { file.name.endsWith(it) } == true) throw IOException("interrupted checkpoint")
                val temp = File(file.path + ".test-new")
                temp.outputStream().use { it.write(bytes); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val source = SubtitleSourceIdentity(SubtitleMediaSource("content://fixture/video"), "a".repeat(64))
        val config = SubtitleGenerationConfig(modelSha256 = "b".repeat(64))
        fun store() = SubtitleGenerationStore(root, io)
        val window = SubtitleWindow(0, 0, 8000, "c".repeat(64), listOf(SpeechCue(500, 2500, "A useful completed line.")))
        override fun close() { root.deleteRecursively() }
    }
    @Test fun completedWindowSurvivesRestartWithoutRepeatingInference() {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            store.running(task.id, task.generation)
            assertTrue(store.checkpoint(task.id, task.generation, f.window, 20_000))
            val reopened = f.store().get(task.id)!!
            assertEquals(task.generation, reopened.generation)
            assertEquals(listOf(f.window), reopened.windows)
        }
    }
    @Test fun pauseResumeCancelAndReplacementFenceEveryLateWindow() {
        Fixture().use { f ->
            val store = f.store(); val old = store.start(f.source, f.config)
            store.running(old.id, old.generation)
            store.pause(old.id, old.generation)
            assertFalse(store.checkpoint(old.id, old.generation, f.window, 20_000))
            val resumed = store.resume(old.id, old.generation)!!
            assertNotEquals(old.generation, resumed.generation)
            assertFalse(store.checkpoint(old.id, old.generation, f.window, 20_000))
            assertTrue(store.checkpoint(resumed.id, resumed.generation, f.window, 20_000))
            store.cancel(resumed.id, resumed.generation)
            assertFalse(store.current(resumed.id, resumed.generation))
            val replacement = store.start(f.source, f.config, force = true)
            assertNull(store.cancel(old.id, old.generation))
            assertEquals(replacement.generation, store.get(replacement.id)!!.generation)
        }
    }
    @Test fun failedAtomicCheckpointDoesNotPublishOrDestroyPriorWindow() {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            store.running(task.id, task.generation)
            assertTrue(store.checkpoint(task.id, task.generation, f.window, 20_000))
            val before = store.get(task.id)
            f.failWrite = true
            try { store.checkpoint(task.id, task.generation, f.window.copy(index = 1, startMs = 7000, endMs = 15000,
                cues = listOf(SpeechCue(7500, 9500, "The next line."))), 20_000); fail("Published interrupted window") }
            catch (_: IOException) { }
            f.failWrite = false
            assertEquals(before, store.get(task.id))
            assertEquals(listOf(f.window), f.store().get(task.id)!!.windows)
        }
    }
    @Test fun sourceModelAndLanguageChangesCannotReuseAnotherConfiguration() {
        Fixture().use { f ->
            val store = f.store(); val original = store.start(f.source, f.config)
            val same = store.start(f.source, f.config)
            assertEquals(original.generation, same.generation)
            assertNotEquals(original.id, store.start(f.source.copy(fingerprint = "d".repeat(64)), f.config).id)
            assertNotEquals(original.id, store.start(f.source, f.config.copy(modelSha256 = "e".repeat(64))).id)
            assertNotEquals(original.id, store.start(f.source, f.config.copy(sourceLanguage = "hi")).id)
        }
    }
    @Test fun fullVideoMergeKeepsEarlyCuesBeyondTheLiveCaptionTailLimit() {
        val original = (0 until 5100).map { SpeechCue(it * 2000L, it * 2000L + 1000, "Line number $it.") }
        assertEquals(original, original.fold(emptyList<SpeechCue>()) { all, cue -> SubtitleFormats.append(all, listOf(cue)) })
        assertTrue(SubtitleFormats.vtt(original.take(1)).startsWith("WEBVTT\n\n00:00:00.000"))
    }
    @Test fun bothAtomicExportsSurviveRestartAndCorruptionKeepsUsefulWindowsForRepair() {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            val complete = store.finish(task.id, task.generation)!!
            assertEquals(SubtitleGenerationStatus.COMPLETED, complete.status)
            assertTrue(File(complete.srtPath!!).readText().contains("00:00:00,500 --> 00:00:02,500"))
            assertTrue(File(complete.vttPath!!).readText().startsWith("WEBVTT\n\n00:00:00.500"))
            File(complete.vttPath).writeText("corrupt replacement")
            val reopened = f.store().get(task.id)!!
            assertEquals(SubtitleGenerationStatus.PARTIAL, reopened.status)
            assertEquals(listOf(f.window), reopened.windows)
            assertNull(reopened.srtPath); assertNull(reopened.vttPath)
            val resumed = f.store().resume(task.id, task.generation)!!
            assertNotEquals(task.generation, resumed.generation)
        }
    }
    @Test fun exportFailureNeverPublishesCompletedAndKeepsItsCheckpoint() {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            f.failSuffix = ".vtt"
            try { store.finish(task.id, task.generation); fail("Published a half-written export pair") }
            catch (_: IOException) { store.fail(task.id, task.generation, "VTT export failed") }
            f.failSuffix = null
            val reopened = f.store().get(task.id)!!
            assertEquals(SubtitleGenerationStatus.PARTIAL, reopened.status)
            assertEquals(listOf(f.window), reopened.windows)
            assertNull(reopened.srtPath); assertNull(reopened.vttPath)
        }
    }
    @Test fun silenceAndMissingModelAreHonestFailuresWithoutInventedCues() {
        Fixture().use { f ->
            val store = f.store()
            val missing = store.start(f.source, f.config.copy(modelSha256 = null))
            assertEquals(SubtitleGenerationStatus.FAILED, missing.status)
            assertTrue(missing.error!!.contains("model"))
            val task = store.start(f.source, f.config)
            store.running(task.id, task.generation)
            store.checkpoint(task.id, task.generation, f.window.copy(cues = emptyList(), silent = true), 8000)
            val silent = store.finish(task.id, task.generation)!!
            assertEquals(SubtitleGenerationStatus.FAILED, silent.status)
            assertTrue(silent.cues.isEmpty()); assertNull(silent.srtPath)
        }
    }
    @Test fun changedSourceInvalidatesAllOldCuesAndLateCommandReceiptsNeverReturnReplacement() {
        Fixture().use { f ->
            val store = f.store(); val old = store.start(f.source, f.config)
            store.running(old.id, old.generation); store.checkpoint(old.id, old.generation, f.window, 8000)
            store.fail(old.id, old.generation, "source changed", invalidate = true)
            assertTrue(store.get(old.id)!!.windows.isEmpty())
            val replacement = store.start(f.source, f.config, force = true)
            assertEquals(old.generation, store.commandSnapshot(old).generation)
            assertNull(store.cancel(old.id, old.generation))
            assertEquals(replacement.generation, store.get(replacement.id)!!.generation)
        }
    }
    @Test fun invalidOrOversizedWindowsCannotDestroyTheCommittedPrefix() {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            val before = store.get(task.id)
            for (window in listOf(f.window.copy(index = 3), f.window.copy(index = 1, endMs = 9000),
                f.window.copy(index = 1, silent = true))) {
                try { store.checkpoint(task.id, task.generation, window, 20_000); fail("Accepted invalid window") }
                catch (_: IllegalArgumentException) { }
                assertEquals(before, store.get(task.id))
            }
        }
    }

    @Test fun forceRegenerationClearsOnlySupersededManagedExportsAndPreservesOriginals() {
        Fixture().use { f ->
            val original = File(f.root, "original-video.mp4").apply { writeText("Original must survive") }
            val store = f.store(); val task = store.start(f.source, f.config)
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            val complete = store.finish(task.id, task.generation)!!
            val unknown = File(File(complete.srtPath!!).parentFile, "user-file.srt").apply { writeText("Unknown must survive") }
            val fresh = store.start(f.source, f.config, force = true)
            assertFalse(File(complete.srtPath).exists()); assertFalse(File(complete.vttPath!!).exists())
            assertTrue(original.exists()); assertTrue(unknown.exists())
            assertEquals(fresh.generation, f.store().get(fresh.id)!!.generation)
            assertNull(store.finish(task.id, task.generation))
        }
    }

    @Test fun mutableRequestHeadersCannotChangeCapturedSourceIdentityInputs() {
        val headers = mutableMapOf("Authorization" to "initial credential")
        val captured = SubtitleMediaSource("https://fixture.invalid/video", headers).captureSnapshot()
        headers["Authorization"] = "replacement credential"
        assertEquals("initial credential", captured.headers["Authorization"])
    }
    @Test fun generationRequestCapturesHeadersBeforeHeldDispatchCanRun() = runBlocking {
        val headers = mutableMapOf("Authorization" to "original request")
        val operation = SubtitleGenerationRequest(1, SubtitleMediaSource("https://fixture.invalid", headers), "hi")
        val dispatch = CompletableDeferred<Unit>()
        val launched = async { dispatch.await(); operation.source.headers["Authorization"] }
        headers["Authorization"] = "another request"
        dispatch.complete(Unit)
        assertEquals("original request", launched.await())
        assertEquals("hi", operation.sourceLanguage)
    }
    @Test fun generatedTrackNormalizerRetainsTheFirstCueBeyondLiveTailLimit() {
        val cues = (0 until 5100).map { SpeechCue(it * 2000L, it * 2000L + 1000, "Line number $it.") }
        val attached = SubtitleFormats.normalizeGenerated(cues)
        assertEquals(cues.size, attached.size)
        assertEquals(cues.first(), attached.first())
    }
    @Test fun failingControlPublishesAnErrorWithoutEscapingToAnUncaughtHandler() = runBlocking {
        var error: String? = null
        runSubtitleControl(action = { throw IOException("disk full") }, accepted = { true }, onFailure = { error = it })
        assertEquals("disk full", error)
    }
    @Test fun heldOldControlFailureCannotPublishAnErrorForAnotherSource() = runBlocking {
        val gate = SubtitlePublicationGate(); val old = gate.advance()
        val release = CompletableDeferred<Unit>(); var error: String? = null
        val control = async {
            runSubtitleControl(action = { release.await(); throw IOException("old operation failure") },
                accepted = { gate.current() == old }, onFailure = { error = it })
        }
        gate.advance(); release.complete(Unit); control.await()
        assertNull(error)
    }
    @Test fun heldOldPublicationCannotAttachCuesOrCancelTheNewSourcesObserver() = runBlocking {
        val gate = SubtitlePublicationGate(); val old = gate.advance()
        assertEquals(old, gate.current()) // A has passed its early check before expensive IO.
        val release = CompletableDeferred<Unit>(); var source = "A"; var oldAttached = false; var observer = "A"
        val publish = async { release.await(); gate.publish(old) { source = "A"; oldAttached = true; observer = "cancelled" } }
        val next = gate.advance(); gate.publish(next) { source = "B"; observer = "B" }
        release.complete(Unit)
        assertFalse(publish.await()); assertEquals("B", source); assertEquals("B", observer); assertFalse(oldAttached)
    }
    @Test fun unavailableFinalSourceProbeKeepsExactCompletedWindowsAndHidesUnverifiedCues() {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            val unavailable = f.source.copy(fingerprint = "d".repeat(64), verifiable = false)
            assertEquals(SubtitleSourceCheck.UNVERIFIED, assessSubtitleSource(f.source, unavailable))
            val partial = store.fail(task.id, task.generation, "Cannot verify online source", requireValidation = true)!!
            assertEquals(SubtitleGenerationStatus.PARTIAL, partial.status)
            assertEquals(listOf(f.window), partial.windows); assertTrue(partial.validationPending)
            assertTrue(partial.cues.isEmpty()); assertNull(partial.srtPath); assertNull(partial.vttPath)
            assertEquals(listOf(f.window), f.store().get(task.id)!!.windows)
        }
    }
    @Test fun heldDocumentExportWritesTheRequestedGenerationAfterSourceChanges() = runBlocking {
        val source = SubtitleMediaSource("content://fixture/A")
        var state = FullSubtitleState(taskId = "old-task", generation = "old-generation", sourceCacheKey = source.cacheKey,
            cues = listOf(SpeechCue(0, 1000, "Original speech.")), srt = "Original speech SRT", vtt = "Original speech VTT")
        val receipt = SubtitleExportReceipt.capture(state, source, "srt")!!
        val openDocument = CompletableDeferred<Unit>(); val output = java.io.ByteArrayOutputStream()
        val writing = async { receipt.write { openDocument.await(); output } }
        state = state.copy(generation = "new-generation", sourceCacheKey = "content://fixture/B", srt = "New video SRT")
        openDocument.complete(Unit); writing.await()
        assertEquals("Original speech SRT", output.toString("UTF-8"))
        assertEquals("old-generation", receipt.generation)
        assertNull(SubtitleExportReceipt.capture(state, source, "srt"))
    }
    @Test fun discontinuousDecodedTimelineCannotShiftLaterCuesAndKeepsTheCommittedPrefix() {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            val chunker = SubtitleAudioDecoder.Pcm16kChunker()
            val first = chunker.consume(java.nio.ByteBuffer.allocate(16000 * 8 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN),
                16000, 1, android.media.AudioFormat.ENCODING_PCM_16BIT, 0).single()
            assertEquals(0L, first.startMs)
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 12_000)
            try {
                chunker.consume(java.nio.ByteBuffer.allocate(16000 * 2 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN),
                    16000, 1, android.media.AudioFormat.ENCODING_PCM_16BIT, 10_000)
                fail("Accepted a two-second media timestamp gap as continuous audio")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("timestamp"))
                store.fail(task.id, task.generation, expected.message!!)
            }
            assertEquals(listOf(f.window), f.store().get(task.id)!!.windows)
        }
    }
    @Test fun continuousResampledAudioAcceptsBoundedCodecTimestampRounding() {
        val chunker = SubtitleAudioDecoder.Pcm16kChunker()
        repeat(20) { index ->
            chunker.consume(java.nio.ByteBuffer.allocate(4800 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN), 48000, 1,
                android.media.AudioFormat.ENCODING_PCM_16BIT, index * 100L + if (index % 2 == 0) 0 else 1)
        }
        val tail = chunker.flush()!!
        assertEquals(0L, tail.startMs); assertEquals(32000, tail.samples.size)
    }
    @Test fun sameProcessReopenHidesAnUnverifiableOnlineTrackUntilItsPcmIsCheckedAgain() {
        Fixture().use { f ->
            val source = f.source.copy(verifiable = false)
            val store = f.store(); val task = store.start(source, f.config)
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            val completed = store.finish(task.id, task.generation)!!
            assertFalse(completed.validationPending); assertEquals(f.window.cues, completed.cues)
            val reopened = store.find(source, f.config)!!
            assertTrue(reopened.validationPending); assertTrue(reopened.cues.isEmpty())
            assertEquals(listOf(f.window), reopened.windows)
            assertEquals(completed.generation, reopened.generation)
        }
    }
    @Test fun legacyHeadOnlyOnlineReceiptIsHiddenOnReopenUntilItsDecodedBytesAreVerified() {
        Fixture().use { f ->
            val source = f.source.copy(source = SubtitleMediaSource("https://fixture.invalid/video"), verifiable = true)
            val store = f.store(); val task = store.start(source, f.config)
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            store.finish(task.id, task.generation)
            val reopened = store.find(source, f.config)!!
            assertTrue("HEAD-only legacy receipts cannot establish decoded media identity", reopened.validationPending)
            assertTrue(reopened.cues.isEmpty()); assertEquals(listOf(f.window), reopened.windows)
        }
    }
    @Test fun capturedGetProofSurvivesRestartAndUpgradesLegacyWindowsWithoutPrematureExposure() {
        Fixture().use { f ->
            val legacy = f.source.copy(source = SubtitleMediaSource("https://fixture.invalid/video"))
            val verified = legacy.copy(strongEtag = "\"version\"", networkSize = 16000, networkUrl = legacy.source.uri)
            val store = f.store(); val old = store.start(legacy, f.config)
            store.running(old.id, old.generation); store.checkpoint(old.id, old.generation, f.window, 8000); store.finish(old.id, old.generation)
            val upgraded = store.start(verified, f.config)
            assertNotEquals(old.generation, upgraded.generation); assertEquals(old.id, upgraded.id)
            assertEquals(listOf(f.window), upgraded.windows); assertTrue(upgraded.validationPending); assertTrue(upgraded.cues.isEmpty())
            val restored = f.store().get(upgraded.id)!!
            assertEquals(verified, restored.source); assertTrue(canTrustSubtitleSource(restored.source, verified))
            assertFalse(canTrustSubtitleSource(legacy, verified))
            val sourceOnly = store.confirmValidated(upgraded.id, upgraded.generation)!!
            assertTrue("A new GET proof cannot validate old HEAD-only PCM checkpoints", sourceOnly.validationPending)
            assertTrue(sourceOnly.cues.isEmpty())
        }
    }
    @Test fun pendingDocumentExportRestoresExactBytesFromOnlyItsSavedSmallToken() = runBlocking {
        Fixture().use { f ->
            val directory = File(f.root, "export-spool")
            val receipt = SubtitleExportReceipt("a".repeat(32), "b".repeat(32), "content://fixture/A", "srt", "Original accepted speech.")
            val token = SubtitleExportSpool(directory, f.io).stage(receipt)
            assertTrue(token.matches(Regex("[a-f0-9]{32}")))
            val recreated = SubtitleExportSpool(directory, f.io)
            val output = java.io.ByteArrayOutputStream()
            recreated.read(token).write { output }
            assertEquals(receipt.text, output.toString("UTF-8"))
            assertEquals(receipt, recreated.read(token))
        }
    }
    @Test fun failedExportSpoolWriteDoesNotReturnAnUnrestorableToken() {
        Fixture().use { f ->
            f.failWrite = true
            try {
                SubtitleExportSpool(File(f.root, "export-spool"), f.io).stage(
                    SubtitleExportReceipt("a".repeat(32), "b".repeat(32), "content://fixture/A", "vtt", "WEBVTT\n\nAccepted speech."))
                fail("Published a token without durable bytes")
            } catch (_: IOException) { }
        }
    }
    @Test fun returningToTheSameCompletedVideoReattachesItsGenerationAfterAnotherVideo() {
        val receipt = SubtitleAttachmentReceipt()
        val sourceA = SubtitleMediaSource("content://fixture/A"); val sourceB = SubtitleMediaSource("content://fixture/B")
        receipt.bind(sourceA); assertTrue(receipt.shouldAttach("generation-A")); receipt.attached("generation-A")
        assertFalse(receipt.shouldAttach("generation-A"))
        receipt.bind(sourceB); receipt.bind(sourceA)
        assertTrue(receipt.shouldAttach("generation-A"))
    }
    @Test fun exportSpoolIsBoundedAndReleasesOnlyItsManagedReceipt() {
        Fixture().use { f ->
            val directory = File(f.root, "export-spool").apply { mkdirs() }
            val unknown = File(directory, "user-owned.srt").apply { writeText("Keep this file") }
            val original = File(f.root, "original-video.mp4").apply { writeText("Keep original video") }
            val spool = SubtitleExportSpool(directory, f.io)
            val receipt = SubtitleExportReceipt("a".repeat(32), "b".repeat(32), "content://fixture/A", "srt", "Accepted speech.")
            val tokens = (0 until 8).map { spool.stage(receipt) }
            try { spool.stage(receipt); fail("Exceeded bounded pending export count") } catch (_: IllegalArgumentException) { }
            spool.remove(tokens.first()); spool.stage(receipt)
            assertTrue(unknown.exists()); assertTrue(original.exists())
            assertEquals(receipt, spool.read(tokens.last()))
            try { spool.remove("../original-video.mp4"); fail("Accepted foreign path") } catch (_: IllegalArgumentException) { }
            assertTrue(original.exists())
        }
    }
    @Test fun corruptSpoolCannotOpenAProviderOrReportExportSuccess() = runBlocking {
        Fixture().use { f ->
            val directory = File(f.root, "export-spool")
            val spool = SubtitleExportSpool(directory, f.io)
            val token = spool.stage(SubtitleExportReceipt("a".repeat(32), "b".repeat(32), "content://fixture/A", "vtt", "Accepted speech."))
            val file = File(directory, "export-$token.json")
            file.writeText(file.readText().replace("Accepted speech.", "Changed speech."))
            var opened = false
            try { spool.read(token).write { opened = true; java.io.ByteArrayOutputStream() }; fail("Exported corrupt receipt") }
            catch (_: IllegalArgumentException) { }
            assertFalse(opened)
        }
    }
    @Test fun subtitleProbeRecomputesHeaderScopeForEveryRedirectedConnection() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        val base = okhttp3.OkHttpClient()
        try {
            val original = server.url("/video").toString()
            val redirected = server.url("/target").newBuilder().host("127.0.0.1").build().toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", redirected))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("ETag", "\"version-one\""))
            val source = SubtitleMediaSource(original, mapOf("Cookie" to "private=1", "Authorization" to "private-token",
                "Referer" to "https://private.example/account", "X-Private" to "must-not-forward"))
            val client = scopedSubtitleClient(source, base)
            client.newCall(okhttp3.Request.Builder().url(original).head().apply { source.headers.forEach { (key, value) -> header(key, value) } }.build())
                .execute().use { assertTrue(it.isSuccessful) }
            val first = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            val target = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("private=1", first.getHeader("Cookie"))
            assertNull(target.getHeader("Cookie")); assertNull(target.getHeader("Authorization")); assertNull(target.getHeader("Referer"))
            assertNull(first.getHeader("X-Private")); assertNull(target.getHeader("X-Private"))
        } finally { server.shutdown(); base.connectionPool.evictAll(); base.dispatcher.executorService.shutdown() }
    }
    @Test fun heldProviderWriteCannotReuseOrConsumeAnotherPickerRequestWithTheSameToken() = runBlocking {
        Fixture().use { f ->
            val spool = SubtitleExportSpool(File(f.root, "export-spool"), f.io)
            val receipt = SubtitleExportReceipt("a".repeat(32), "b".repeat(32), "content://fixture/A", "srt", "Accepted speech.")
            val token = spool.stage(receipt)
            val opened = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val original = java.io.ByteArrayOutputStream(); var duplicateOpened = false
            val delivery = async { spool.deliver(token) { opened.complete(Unit); release.await(); original } }
            opened.await()
            try {
                spool.deliver(token) { duplicateOpened = true; java.io.ByteArrayOutputStream() }
                fail("Accepted overlapping delivery of a token while another picker still owns it")
            } catch (_: IOException) { }
            assertFalse(duplicateOpened); assertTrue(token in spool.writing.value)
            assertEquals(receipt, spool.read(token))
            release.complete(Unit); delivery.await()
            assertEquals(receipt.text, original.toString("UTF-8")); assertTrue(spool.writing.value.isEmpty())
            try { spool.read(token); fail("Retained a consumed delivery token") } catch (_: IOException) { }
        }
    }
    @Test fun providerFailureReleasesItsLeaseAndKeepsAcceptedBytesForRetry() = runBlocking {
        Fixture().use { f ->
            val spool = SubtitleExportSpool(File(f.root, "export-spool"), f.io)
            val receipt = SubtitleExportReceipt("a".repeat(32), "b".repeat(32), "content://fixture/A", "vtt", "Accepted speech.")
            val token = spool.stage(receipt)
            try { spool.deliver(token) { throw IOException("Provider unavailable") }; fail("Reported successful delivery") } catch (_: IOException) { }
            assertEquals(receipt, spool.read(token)); assertTrue(spool.writing.value.isEmpty())
        }
    }
    @Test fun acceptedExportClaimsItsTokenBeforeHeldProviderDispatchCanRun() = runBlocking {
        Fixture().use { f ->
            val spool = SubtitleExportSpool(File(f.root, "export-spool"), f.io)
            val receipt = SubtitleExportReceipt("a".repeat(32), "b".repeat(32), "content://fixture/A", "srt", "Accepted speech.")
            val token = spool.stage(receipt)
            val accepted = spool.claim(token)
            val dispatch = CompletableDeferred<Unit>(); val output = java.io.ByteArrayOutputStream()
            val job = async { dispatch.await(); spool.deliver(accepted) { output } }
            assertTrue(token in spool.writing.value)
            var reopened = false
            try { spool.deliver(token) { reopened = true; java.io.ByteArrayOutputStream() }; fail("Reused accepted export while its provider dispatch was held") }
            catch (_: IOException) { }
            assertFalse(reopened); assertEquals(receipt, spool.read(token))
            dispatch.complete(Unit); job.await()
            assertEquals(receipt.text, output.toString("UTF-8")); assertTrue(spool.writing.value.isEmpty())
        }
    }
    @Test fun ownedReplayIsIdempotentForPausedAndTerminalTasksEvenWithTheOriginalForceFlag() {
        for (status in listOf(SubtitleGenerationStatus.PAUSED, SubtitleGenerationStatus.CANCELLED, SubtitleGenerationStatus.FAILED,
            SubtitleGenerationStatus.PARTIAL, SubtitleGenerationStatus.COMPLETED)) Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config, ownerRequestId = "orez-plan-1")
            store.running(task.id, task.generation)
            if (status in setOf(SubtitleGenerationStatus.PARTIAL, SubtitleGenerationStatus.COMPLETED))
                store.checkpoint(task.id, task.generation, f.window, 8000)
            when (status) {
                SubtitleGenerationStatus.PAUSED -> store.pause(task.id, task.generation)
                SubtitleGenerationStatus.CANCELLED -> store.cancel(task.id, task.generation)
                SubtitleGenerationStatus.COMPLETED -> store.finish(task.id, task.generation)
                else -> store.fail(task.id, task.generation, "An honest native failure")
            }
            val before = store.get(task.id)!!
            assertEquals(status, before.status)
            val replay = store.start(f.source, f.config, force = true, ownerRequestId = "orez-plan-1", allowOwnerReplacement = false)
            assertEquals(before, replay); assertEquals("orez-plan-1", f.store().get(task.id)!!.ownerRequestId)
        }
    }
    @Test fun replacingOwnershipRotatesTheGenerationAndFencesEveryCapturedOldControl() {
        Fixture().use { f ->
            val store = f.store(); val first = store.start(f.source, f.config, ownerRequestId = "orez-plan-1")
            store.running(first.id, first.generation); store.checkpoint(first.id, first.generation, f.window, 8000)
            val reader = store.start(f.source, f.config)
            assertNotEquals(first.generation, reader.generation); assertNull(reader.ownerRequestId)
            assertEquals(listOf(f.window), reader.windows)
            assertNull(store.cancel(first.id, first.generation)); assertNull(store.pause(first.id, first.generation))
            assertEquals(first, store.commandSnapshot(first)); assertTrue(store.current(reader.id, reader.generation))
            val next = store.start(f.source, f.config, ownerRequestId = "orez-plan-2")
            assertNotEquals(reader.generation, next.generation); assertEquals("orez-plan-2", next.ownerRequestId)
            assertNull(store.cancel(reader.id, reader.generation)); assertTrue(store.current(next.id, next.generation))
        }
    }
    @Test fun replayOwnershipGuardRejectsAReplacementAfterInputsWereCapturedWithoutAnyJournalMutation() {
        Fixture().use { f ->
            val store = f.store(); val captured = f.source.copy(source = f.source.source.captureSnapshot())
            store.start(captured, f.config, ownerRequestId = "orez-plan-1")
            val replacement = store.start(f.source, f.config, force = true, ownerRequestId = "orez-plan-2")
            val bytes = File(f.root, replacement.id + ".json").readBytes()
            try { store.start(captured, f.config, ownerRequestId = "orez-plan-1", allowOwnerReplacement = false); fail("Old replay overwrote another owner") }
            catch (_: IllegalStateException) { }
            assertEquals(replacement, store.get(replacement.id)); assertArrayEquals(bytes, File(f.root, replacement.id + ".json").readBytes())
        }
    }
    @Test fun ownerTokenNeverBypassesExactSourceModelOrNormalizedConfiguration() {
        Fixture().use { f ->
            val store = f.store(); val first = store.start(f.source, f.config, ownerRequestId = "orez-plan-1")
            assertEquals(first.generation, store.start(f.source, f.config.copy(sourceLanguage = " AUTO ", targetLanguage = " EN ",
                style = " WHISPER-ENGLISH "), ownerRequestId = "orez-plan-1").generation)
            assertNotEquals(first.id, store.start(f.source.copy(fingerprint = "d".repeat(64)), f.config, ownerRequestId = "orez-plan-1").id)
            assertNotEquals(first.id, store.start(f.source, f.config.copy(modelSha256 = "e".repeat(64)), ownerRequestId = "orez-plan-1").id)
            assertNotEquals(first.id, store.start(f.source, f.config.copy(sourceLanguage = "hi"), ownerRequestId = "orez-plan-1").id)
        }
    }
    @Test fun malformedOwnerCannotReplaceAValidTaskOrItsPrivateExports() {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config, ownerRequestId = "orez-valid")
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000); store.finish(task.id, task.generation)
            val before = store.get(task.id)!!
            for (owner in listOf("", "bad\nowner", "x".repeat(101))) {
                try { store.start(f.source, f.config, force = true, ownerRequestId = owner); fail("Accepted malformed owner") }
                catch (_: IllegalArgumentException) { }
                assertEquals(before, store.get(task.id)); assertTrue(File(before.srtPath!!).isFile)
            }
        }
    }
    @Test fun verifiedReceiptDetectsLiveExportCorruptionAndCannotRebaseOntoAReplacement() {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config, ownerRequestId = "orez-plan-1")
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            val complete = store.finish(task.id, task.generation)!!
            assertEquals(complete, store.exportVerified(task.id, task.generation))
            File(complete.srtPath!!).writeText("Changed bytes")
            val invalid = store.refresh(task.id, task.generation)!!
            assertEquals(SubtitleGenerationStatus.PARTIAL, invalid.status); assertEquals(listOf(f.window), invalid.windows)
            assertNull(store.exportVerified(task.id, task.generation)); assertEquals(invalid, store.get(task.id))
            val replacement = store.start(f.source, f.config, ownerRequestId = "orez-plan-2")
            assertNull(store.refresh(task.id, task.generation)); assertNull(store.exportVerified(task.id, task.generation))
            assertEquals(replacement, store.get(task.id))
        }
    }
    @Test fun selfConsistentExportHashStillMustMatchTheJournalCues() {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config)
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            val complete = store.finish(task.id, task.generation)!!
            val file = File(complete.vttPath!!); file.writeText(SubtitleFormats.vtt(listOf(SpeechCue(0, 1000, "Another video."))))
            val journal = File(f.root, task.id + ".json")
            journal.writeText(org.json.JSONObject(journal.readText()).put("vttSha256", SubtitleGenerationStore.fileHash(file)).toString())
            val reopened = f.store().refresh(task.id)!!
            assertEquals(SubtitleGenerationStatus.PARTIAL, reopened.status); assertEquals(listOf(f.window), reopened.windows)
            assertNull(reopened.srtPath); assertNull(reopened.vttPath)
        }
    }
    @Test fun pcmVerificationCannotExposeCuesThroughASeparatelyClearedSourceFlag() {
        Fixture().use { f ->
            val task = SubtitleGenerationTask("a".repeat(32), "b".repeat(32), f.source, f.config, SubtitleGenerationStatus.COMPLETED,
                windows = listOf(f.window), pcmValidationRequired = true)
            assertTrue(task.cues.isEmpty())
        }
    }
    @Test fun pendingOwnedControlDefersWorkWithoutOverwritingOrLosingItsCheckpoint() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config, ownerRequestId = "orez-plan-1")
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            val before = store.get(task.id)!!
            try { enforceSubtitleOwnerGate(store, task) { false }; fail("Advanced computation while pending owned stop lacked acknowledgement") }
            catch (blocked: SubtitleOwnedWorkBlocked) { assertTrue(blocked.retry) }
            assertEquals(before, store.get(task.id)); assertEquals(listOf(f.window), store.get(task.id)!!.windows)
        }
    }
    @Test fun acknowledgedOwnedStopExitsWithoutFailingTheNativeTaskAndCannotTouchItsResumedGeneration() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config, ownerRequestId = "orez-plan-1")
            store.running(task.id, task.generation); store.checkpoint(task.id, task.generation, f.window, 8000)
            try { enforceSubtitleOwnerGate(store, task) { store.pause(it.id, it.generation); false }; fail("Advanced paused native work") }
            catch (blocked: SubtitleOwnedWorkBlocked) { assertFalse(blocked.retry) }
            assertEquals(SubtitleGenerationStatus.PAUSED, store.get(task.id)!!.status)
            val resumed = store.resume(task.id, task.generation)!!
            var inspectedNewGeneration = false
            try { enforceSubtitleOwnerGate(store, task) { inspectedNewGeneration = true; true }; fail("Old gate attached to resumed generation") }
            catch (blocked: SubtitleOwnedWorkBlocked) { assertFalse(blocked.retry) }
            assertFalse(inspectedNewGeneration); assertTrue(store.current(resumed.id, resumed.generation))
        }
    }
    @Test fun heldOldOwnerGateCannotPermitWorkAfterTheReaderReplacesItsGeneration() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = store.start(f.source, f.config, ownerRequestId = "orez-plan-1")
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val gate = async {
                try { enforceSubtitleOwnerGate(store, task) { entered.complete(Unit); release.await(); true }; false }
                catch (blocked: SubtitleOwnedWorkBlocked) { assertFalse(blocked.retry); true }
            }
            entered.await()
            val reader = store.start(f.source, f.config)
            release.complete(Unit)
            assertTrue(gate.await()); assertTrue(store.current(reader.id, reader.generation)); assertNull(reader.ownerRequestId)
        }
    }
    @Test fun unownedWorkDoesNotAllocateOrInspectTheWorkflowStoreAndOwnedLookupFailureDefersSafely() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val reader = store.start(f.source, f.config)
            enforceSubtitleOwnerGate(store, reader) { fail("Unowned player job inspected Orez workflow state"); true }
            val owned = store.start(f.source, f.config, ownerRequestId = "orez-plan-1")
            try { enforceSubtitleOwnerGate(store, owned) { throw IOException("workflow disk unavailable") }; fail("Advanced after unverified control lookup") }
            catch (blocked: SubtitleOwnedWorkBlocked) { assertTrue(blocked.retry) }
            assertTrue(store.current(owned.id, owned.generation))
        }
    }
}
