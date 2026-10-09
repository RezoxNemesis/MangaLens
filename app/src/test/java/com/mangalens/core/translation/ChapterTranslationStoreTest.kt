package com.mangalens.core.translation

import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real journals/files; only Android's AtomicFile and image decoder are replaced on the JVM. */
class ChapterTranslationStoreTest {
    @Test fun resourceWaitSurvivesRestartKeepsCompletedPagesAndCannotWakeUserPauseOrReplacement() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config, ownerRequestId = "orez:resource-wait")
            store.markRunning(task.id, task.generation)
            val completed = f.completed(store, task)
            assertTrue(store.commitPage(task.id, task.generation, completed))
            val reason = "Waiting for device memory; saved progress will resume automatically."
            assertTrue(store.deferForResources(task.id, task.generation, reason))
            val reopened = f.store().refresh(task.id, task.generation)!!
            assertEquals(ChapterTranslationStatus.QUEUED, reopened.status)
            assertEquals(task.generation, reopened.generation)
            assertEquals(task.ownerRequestId, reopened.ownerRequestId)
            assertEquals(completed, reopened.pages.single())
            assertEquals(reason, reopened.error)
            store.pause(task.id, task.generation)
            assertFalse(store.deferForResources(task.id, task.generation, reason))
            assertEquals(ChapterTranslationStatus.PAUSED, store.get(task.id)!!.status)
            val resumed = store.resume(task.id, task.generation)!!
            assertFalse(store.deferForResources(task.id, task.generation, reason))
            assertEquals(resumed.generation, store.get(task.id)!!.generation)
        }
    }

    @Test fun explicitPageRetranslationCreatesFreshWorkAndRetainsOtherCompletedPages() {
        Fixture().use { f ->
            val second = File(f.sources, "2.img").apply { writeText("second original page") }
            val chapter = f.chapter().copy(pages = f.chapter().pages + ChapterPage(2, "local:second", second.absolutePath))
            val store = f.store()
            val old = store.start(chapter, f.config, ownerRequestId = "reader:first")
            store.markRunning(old.id, old.generation)
            val firstResult = f.completed(store, old)
            val secondResult = f.completed(store, old, index = 2)
            assertTrue(store.commitPage(old.id, old.generation, firstResult))
            assertTrue(store.commitPage(old.id, old.generation, secondResult))
            store.finish(old.id, old.generation)
            val hashes = listOf(f.source, second).map(ChapterTranslationStore::sha256)
            val fresh = store.start(chapter, f.config, requestedPages = listOf(1),
                ownerRequestId = "reader:again", forceReprocess = true)
            assertNotEquals(old.generation, fresh.generation)
            assertEquals(ChapterTranslationPageStatus.PENDING, fresh.pages.first().status)
            assertTrue(fresh.pages.first().lettering.isEmpty())
            assertNull(fresh.pages.first().cleanedPath)
            assertEquals(secondResult, fresh.pages[1])
            assertEquals(hashes, listOf(f.source, second).map(ChapterTranslationStore::sha256))
            assertTrue(File(firstResult.cleanedPath!!).isFile)
            store.markRunning(fresh.id, fresh.generation)
            assertNotNull(store.beginPage(fresh.id, fresh.generation, 1))
            assertFalse(store.commitPage(old.id, old.generation, firstResult))
        }
    }

    @Test fun forcedWholeChapterReprocessDoesNotReplayTheSameOwnersCompletedJournal() {
        Fixture().use { f ->
            val store = f.store()
            val old = store.start(f.chapter(), f.config, ownerRequestId = "reader:repeat")
            store.markRunning(old.id, old.generation)
            assertTrue(store.commitPage(old.id, old.generation, f.completed(store, old)))
            store.finish(old.id, old.generation)
            val fresh = store.start(f.chapter(), f.config, ownerRequestId = "reader:repeat", forceReprocess = true)
            assertNotEquals(old.generation, fresh.generation)
            assertEquals(ChapterTranslationPageStatus.PENDING, fresh.pages.single().status)
        }
    }

    @Test fun forcedReprocessCannotReplaceAnotherOwnersReservedGeneration() {
        Fixture().use { f ->
            val store = f.store()
            val owned = store.start(f.chapter(), f.config, ownerRequestId = "orez:reserved")
            try {
                store.start(f.chapter(), f.config, ownerRequestId = "orez:other",
                    allowOwnerReplacement = false, forceReprocess = true)
                fail("Forced reprocessing bypassed request ownership")
            } catch (_: IllegalStateException) { }
            assertEquals(owned, store.get(owned.id))
        }
    }

    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("chapter-translation").toFile()
        val sources = File(directory, "chapters").apply { mkdirs() }
        val journals = File(directory, "translations").apply { mkdirs() }
        val source = File(sources, "1.img").apply { writeText("original page") }
        var failWrite = false
        var failDelete = false
        var surfaceCheck: ((File) -> Unit)? = null
        val io = object : ChapterJournalIo {
            override fun read(file: File): ByteArray = file.readBytes()
            override fun write(file: File, bytes: ByteArray) {
                if (failWrite) throw java.io.IOException("injected journal interruption")
                val temporary = File(file.parentFile, file.name + ".test-new")
                temporary.outputStream().use { it.write(bytes); it.fd.sync() }
                Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            override fun delete(file: File) {
                if (failDelete) throw java.io.IOException("injected delete failure")
                super<ChapterJournalIo>.delete(file)
            }
        }
        fun store() = ChapterTranslationStore(journals, sources, io) { file ->
            surfaceCheck?.invoke(file)
            file.readText().startsWith("valid surface:")
        }
        fun chapter() = SavedChapter("a".repeat(32), "Fixture", "content://explicit-picker",
            listOf(ChapterPage(1, "content://explicit-picker", source.absolutePath)))
        val config = ChapterTranslationConfig("hi", localRefinement = false)
        fun completed(store: ChapterTranslationStore, task: ChapterTranslationTask,
            status: ChapterTranslationPageStatus = ChapterTranslationPageStatus.COMPLETED, index: Int = 1): ChapterTranslationPage {
            val running = store.beginPage(task.id, task.generation, index)!!
            val output = store.createOutputFile(task.id, task.generation, index).apply { writeText("valid surface: recovered paper") }
            return running.copy(status = status, cleanedPath = output.absolutePath,
                cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 100, imageHeight = 200,
                lettering = listOf(SavedMangaLettering("Hello.", "नमस्ते।", 5, 10, 90, 60,
                    "sans-serif", 0, -16777216, 24f, "ALIGN_CENTER", 10, 15, 80, 45)),
                rejectedRegions = if (status == ChapterTranslationPageStatus.PARTIAL) 1 else 0)
        }
        override fun close() { directory.deleteRecursively() }
    }

    @Test fun replacementRejectsAnOlderWorkersOutputWithoutChangingTheNewGeneration() {
        Fixture().use { f ->
            val store = f.store()
            val old = store.start(f.chapter(), f.config)
            store.markRunning(old.id, old.generation)
            val oldResult = f.completed(store, old)
            val replacement = store.start(f.chapter(), f.config)
            assertNotEquals(old.generation, replacement.generation)
            assertFalse(store.commitPage(old.id, old.generation, oldResult))
            assertEquals(replacement, store.get(old.id))
            assertNull(store.pause(old.id, old.generation))
            assertEquals(ChapterTranslationStatus.QUEUED, store.get(old.id)!!.status)
        }
    }

    @Test fun rapidPauseResumeFencesTheInterruptedGenerationAndKeepsCommittedPages() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            assertTrue(store.commitPage(task.id, task.generation, f.completed(store, task)))
            val paused = store.pause(task.id, task.generation)!!
            val resumed = store.resume(task.id, paused.generation)!!
            assertNotEquals(paused.generation, resumed.generation)
            assertEquals(1, resumed.completedPages)
            assertNull(store.cancel(task.id, paused.generation))
            assertEquals(resumed.generation, store.get(task.id)!!.generation)
            assertEquals(ChapterTranslationPageStatus.COMPLETED, store.get(task.id)!!.pages.single().status)
        }
    }

    @Test fun sameLengthSourceReplacementInvalidatesDespiteUnchangedTimestamp() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            assertTrue(store.commitPage(task.id, task.generation, f.completed(store, task)))
            store.finish(task.id, task.generation)
            val timestamp = f.source.lastModified()
            f.source.writeText("different img")
            assertEquals("original page".length.toLong(), f.source.length())
            f.source.setLastModified(timestamp)
            val restored = f.store().refresh(task.id)!!
            assertEquals(ChapterTranslationPageStatus.PENDING, restored.pages.single().status)
            assertTrue(restored.pages.single().lettering.isEmpty())
            assertNull(restored.pages.single().cleanedPath)
            assertNotEquals(ChapterTranslationStatus.COMPLETED, restored.status)
            assertTrue(f.source.exists())
        }
    }

    @Test fun missingOrCorruptSurfaceCannotBeRestoredAsCompleted() = runBlocking {
        for (remove in listOf(true, false)) Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            assertTrue(store.commitPage(task.id, task.generation, result))
            store.finish(task.id, task.generation)
            File(result.cleanedPath!!).let { if (remove) it.delete() else it.writeText("broken surface") }
            val restored = f.store().refresh(task.id)!!
            assertEquals(ChapterTranslationPageStatus.PENDING, restored.pages.single().status)
            assertTrue(restored.pages.single().lettering.isEmpty())
            assertNotEquals(ChapterTranslationStatus.COMPLETED, restored.status)
        }
    }

    @Test fun journalFailureLeavesLastCommittedPageObservableAndRecoverable() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            f.failWrite = true
            try { store.commitPage(task.id, task.generation, result); fail("Expected journal interruption") }
            catch (_: java.io.IOException) { }
            assertEquals(ChapterTranslationPageStatus.RUNNING, store.get(task.id)!!.pages.single().status)
            f.failWrite = false
            val restored = f.store().get(task.id)!!
            assertEquals(ChapterTranslationPageStatus.PENDING, restored.pages.single().status)
            assertTrue(restored.pages.single().lettering.isEmpty())
            assertTrue(f.source.exists())
        }
    }

    @Test fun partialPageRetainsItsSuccessfulBubbleAcrossPauseAndReopen() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            assertTrue(store.commitPage(task.id, task.generation,
                f.completed(store, task, ChapterTranslationPageStatus.PARTIAL)))
            assertEquals(ChapterTranslationStatus.PARTIAL, store.finish(task.id, task.generation)!!.status)
            val reopenedStore = f.store()
            assertTrue(reopenedStore.get(task.id)!!.validationPending)
            assertTrue(reopenedStore.states.value.single().pages.single().lettering.isEmpty())
            val reopened = reopenedStore.refresh(task.id)!!
            assertEquals("नमस्ते।", reopened.pages.single().lettering.single().translated)
            val retry = store.resume(task.id, task.generation)!!
            assertEquals("नमस्ते।", retry.pages.single().lettering.single().translated)
            assertEquals(ChapterTranslationPageStatus.PARTIAL, retry.pages.single().status)
        }
    }

    @Test fun hinglishShortNounsKeepExactHindiEvidenceAcrossColdJournalReopen() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config.copy(targetLanguage = "hi-latn"))
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            val letters = listOf("Fire!" to "आग!", "Power!" to "शक्ति!", "Sword!" to "तलवार!").map { (source, hindi) ->
                val draft = HinglishTranslationOutput.fromHindiDraft(source, hindi)
                val selected = TranslationQualityPolicy.chooseDraft(source, draft, "I was beaten up.", "hi-latn")
                val recalled = TranslationMemoryCodec.decode(source, TranslationMemoryCodec.encode(source, selected, "hi-latn"), "hi-latn")!!
                result.lettering.single().copy(source = source, translated = recalled.text, savedHindiDraft = recalled.hindiDraft)
            }
            assertTrue(store.commitPage(task.id, task.generation, result.copy(lettering = letters)))
            assertEquals(ChapterTranslationStatus.COMPLETED, store.finish(task.id, task.generation)!!.status)
            val reopenedStore = f.store()
            assertTrue(reopenedStore.get(task.id)!!.validationPending)
            val restored = reopenedStore.refresh(task.id)!!
            assertEquals(letters, restored.pages.single().lettering)
            for (text in restored.pages.single().lettering) {
                assertTrue(TranslationQualityPolicy.isUsable(text.source, text.translated, "hi-latn", text.savedHindiDraft))
                assertFalse(TranslationQualityPolicy.isUsable(text.source, text.translated, "hi-latn"))
            }
        }
    }

    @Test fun aLegacyHinglishJournalWithoutEvidenceStillUsesTheStrictPlainGate() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config.copy(targetLanguage = "hi-latn"))
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            val letter = result.lettering.single().copy(source = "I", translated = "main")
            assertTrue(store.commitPage(task.id, task.generation, result.copy(lettering = listOf(letter))))
            store.finish(task.id, task.generation)
            assertFalse(File(f.journals, task.id + ".json").readText().contains("savedHindiDraft"))
            val reopened = f.store().refresh(task.id)!!
            assertEquals(letter, reopened.pages.single().lettering.single())
            assertNull(reopened.pages.single().lettering.single().savedHindiDraft)
        }
    }

    @Test fun unprovenOrMismatchedShortNounsNeverEnterTheDurableJournal() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config.copy(targetLanguage = "hi-latn"))
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            for (proof in listOf(null, "तलवार!", "Fire!", "आ".repeat(8001))) {
                val invalid = result.lettering.single().copy(source = "Fire!", translated = "aag!", savedHindiDraft = proof)
                assertThrows(IllegalArgumentException::class.java) {
                    store.commitPage(task.id, task.generation, result.copy(lettering = listOf(invalid)))
                }
            }
            assertEquals(ChapterTranslationPageStatus.RUNNING, store.get(task.id)!!.pages.single().status)
        }
    }

    @Test fun ordinaryHindiCannotUseRomanEvidenceAsAnException() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            val invalid = result.lettering.single().copy(savedHindiDraft = "नमस्ते।")
            assertThrows(IllegalArgumentException::class.java) {
                store.commitPage(task.id, task.generation, result.copy(lettering = listOf(invalid)))
            }
        }
    }

    @Test fun changedEvidenceCannotRestoreAnOtherwiseValidShortNounJournal() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config.copy(targetLanguage = "hi-latn"))
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            val draft = HinglishTranslationOutput.fromHindiDraft("Fire!", "आग!")
            val valid = result.lettering.single().copy(source = "Fire!", translated = draft.text, savedHindiDraft = draft.hindiDraft)
            assertTrue(store.commitPage(task.id, task.generation, result.copy(lettering = listOf(valid))))
            store.finish(task.id, task.generation)
            val file = File(f.journals, task.id + ".json")
            file.writeText(file.readText().replace("आग!", "तलवार!"))
            assertNull(f.store().get(task.id))
            assertTrue(f.source.exists())
        }
    }

    @Test fun capturedConfigurationSeparatesStyleOcrAndRefinementJobs() {
        Fixture().use { f ->
            val store = f.store()
            val chapter = f.chapter()
            val first = store.start(chapter, f.config.copy(ocrScript = "LATIN", customStyle = "original"))
            val second = store.start(chapter, f.config.copy(ocrScript = "JAPANESE", localRefinement = true))
            assertNotEquals(first.id, second.id)
            assertEquals("LATIN", store.get(first.id)!!.config.ocrScript)
            assertFalse(store.get(first.id)!!.config.localRefinement)
        }
    }

    @Test fun rejectsSourcePathAndSymlinkEscapeBeforeReadingUserFiles() {
        Fixture().use { f ->
            val store = f.store()
            val unrelated = File(f.directory, "outside.img").apply { writeText("unrelated") }
            val link = File(f.sources, "link.img")
            Files.createSymbolicLink(link.toPath(), unrelated.toPath())
            for (file in listOf(unrelated, link)) {
                val chapter = f.chapter().copy(pages = listOf(ChapterPage(1, "content://selected", file.absolutePath)))
                try { store.start(chapter, f.config); fail("Accepted a source outside managed chapters") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals("unrelated", unrelated.readText())
            assertTrue(store.states.value.isEmpty())
        }
    }

    @Test fun outputEscapeAndOversizedLetteringNeverEnterJournal() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            val outside = File(f.directory, "outside.png").apply { writeText("valid surface: private") }
            try { store.commitPage(task.id, task.generation, result.copy(cleanedPath = outside.absolutePath)); fail("Accepted output escape") }
            catch (_: IllegalArgumentException) { }
            val text = result.lettering.single().copy(translated = "अ".repeat(20_000))
            try { store.commitPage(task.id, task.generation, result.copy(lettering = listOf(text))); fail("Accepted unbounded metadata") }
            catch (_: IllegalArgumentException) { }
            assertEquals(ChapterTranslationPageStatus.RUNNING, store.get(task.id)!!.pages.single().status)
        }
    }

    @Test fun orphanCleanupPreservesSourceCommittedSurfacesAndTheActiveGeneration() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            assertTrue(store.commitPage(task.id, task.generation, result))
            val unfinished = store.createOutputFile(task.id, task.generation, 1).apply { writeText("valid surface: uncommitted") }
            val retained = File(result.cleanedPath!!)
            unfinished.setLastModified(1L); retained.setLastModified(1L); f.source.setLastModified(1L)
            store.cleanupOrphans()
            assertTrue(unfinished.exists())
            assertTrue(retained.exists())
            store.cancel(task.id, task.generation)
            store.cleanupOrphans()
            assertFalse(unfinished.exists())
            assertTrue(retained.exists())
            assertTrue(f.source.exists())
        }
    }

    @Test fun cancellationKeepsAcceptedOutputButRejectsAllLaterMutations() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            assertTrue(store.commitPage(task.id, task.generation, result))
            val cancelled = store.cancel(task.id, task.generation)!!
            assertTrue(cancelled.hasTranslations)
            assertFalse(store.commitPage(task.id, task.generation, result))
            store.interrupted(task.id, task.generation)
            assertNull(store.finish(task.id, task.generation))
            assertNull(store.resume(task.id, task.generation))
            assertEquals(ChapterTranslationStatus.CANCELLED, store.get(task.id)!!.status)
        }
    }

    @Test fun refreshDuringOcrRejectsOnlyTheStalePageCommit() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val oldResult = f.completed(store, task)
            f.source.writeText("different img")
            store.refresh(task.id)
            assertFalse(store.commitPage(task.id, task.generation, oldResult))
            assertEquals(ChapterTranslationStatus.RUNNING, store.get(task.id)!!.status)
            assertEquals(ChapterTranslationPageStatus.PENDING, store.get(task.id)!!.pages.single().status)
            assertTrue(store.get(task.id)!!.pages.single().lettering.isEmpty())
        }
    }

    @Test fun progressDeliveryFailureCannotDowngradeAllCommittedPageEvidence() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            assertTrue(store.commitPage(task.id, task.generation, f.completed(store, task)))
            assertEquals(ChapterTranslationStatus.COMPLETED,
                store.fail(task.id, task.generation, "foreground progress could not be delivered")!!.status)
            assertTrue(store.get(task.id)!!.hasTranslations)
            assertNull(store.get(task.id)!!.error)
        }
    }

    @Test fun replacingSameConfigurationRetriesIncompletePagesAndKeepsCompleteResults() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            assertTrue(store.commitPage(task.id, task.generation, result))
            store.finish(task.id, task.generation)
            val retry = store.start(f.chapter(), f.config)
            assertNotEquals(task.generation, retry.generation)
            assertEquals(result.cleanedPath, retry.pages.single().cleanedPath)
            assertEquals(1, retry.completedPages)
            assertNull(store.beginPage(retry.id, retry.generation, 1))
        }
    }

    @Test fun replacementRetainsAWorkerCommitThatArrivesDuringSourceValidation() {
        Fixture().use { f ->
            val source2 = File(f.sources, "2.img").apply { writeText("second original") }
            val chapter = f.chapter().copy(pages = f.chapter().pages + ChapterPage(2, "content://second", source2.absolutePath))
            val store = f.store()
            val task = store.start(chapter, f.config)
            store.markRunning(task.id, task.generation)
            val first = f.completed(store, task)
            assertTrue(store.commitPage(task.id, task.generation, first))
            val second = f.completed(store, task, index = 2)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val once = AtomicBoolean(true)
            f.surfaceCheck = { file -> if (file.absolutePath == first.cleanedPath && once.compareAndSet(true, false)) {
                entered.countDown()
                assertTrue("Validation was not released", release.await(10, TimeUnit.SECONDS))
            } }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val replacement = executor.submit<ChapterTranslationTask> { store.start(chapter, f.config) }
                assertTrue("Replacement did not validate the earlier page", entered.await(10, TimeUnit.SECONDS))
                assertTrue(store.commitPage(task.id, task.generation, second))
                release.countDown()
                val next = replacement.get(10, TimeUnit.SECONDS)
                assertEquals(2, next.completedPages)
                assertEquals(second.cleanedPath, next.pages.last().cleanedPath)
                assertFalse(store.commitPage(task.id, task.generation, second))
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun deletingOneChapterFreesAFullHistorySlotAndPreservesSharedOriginals() {
        Fixture().use { f ->
            val store = f.store()
            val chapters = (1..64).map { number -> f.chapter().copy(id = "%032x".format(number)) }
            val tasks = chapters.map { store.start(it, f.config) }
            try { store.start(f.chapter().copy(id = "%032x".format(65)), f.config); fail("Expected history limit") }
            catch (_: IllegalArgumentException) { }
            val removal = store.beginChapterRemoval(chapters.first().id)
            assertFalse(store.isCurrent(tasks.first().id, tasks.first().generation))
            store.finishChapterRemoval(removal)
            assertNull(store.get(tasks.first().id))
            assertEquals(ChapterTranslationStatus.QUEUED, store.get(tasks.last().id)!!.status)
            assertTrue(f.source.exists())
            assertNotNull(store.start(f.chapter().copy(id = "%032x".format(65)), f.config))
            assertEquals(64, store.states.value.size)
        }
    }

    @Test fun chapterRemovalCleansManagedSurfacesAtomicRemnantsAndFencesLateEffects() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            assertTrue(store.commitPage(task.id, task.generation, result))
            val otherChapter = f.chapter().copy(id = "c".repeat(32))
            val other = store.start(otherChapter, f.config)
            store.markRunning(other.id, other.generation)
            val otherResult = f.completed(store, other)
            assertTrue(store.commitPage(other.id, other.generation, otherResult))
            val removal = store.beginChapterRemoval(task.chapterId)
            val file = File(f.journals, task.id + ".json")
            File(file.path + ".bak").writeBytes(file.readBytes())
            File(file.path + ".new").writeText("interrupted replacement")
            File(result.cleanedPath!! + ".part").writeText("unpublished surface")
            store.finishChapterRemoval(removal)
            assertFalse(file.exists())
            assertFalse(File(file.path + ".bak").exists())
            assertFalse(File(file.path + ".new").exists())
            assertFalse(File(f.journals, task.id).exists())
            assertTrue(File(otherResult.cleanedPath!!).exists())
            assertTrue(store.isCurrent(other.id, other.generation))
            assertEquals("original page", f.source.readText())
            assertFalse(store.commitPage(task.id, task.generation, result))
            assertNull(store.fail(task.id, task.generation, "late native callback"))
            store.interrupted(task.id, task.generation)
            assertNull(store.get(task.id))
            try { store.createOutputFile(task.id, task.generation, 1); fail("Old worker recreated removed output folder") }
            catch (_: CancellationException) { }
            assertFalse(File(f.journals, task.id).exists())
            assertNull(f.store().get(task.id))
        }
    }

    @Test fun deletionFencesAStartAlreadyValidatingSourcesAndAllowsASeparateLaterReimport() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            assertTrue(store.commitPage(task.id, task.generation, result))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val once = AtomicBoolean(true)
            f.surfaceCheck = { file -> if (file.absolutePath == result.cleanedPath && once.compareAndSet(true, false)) {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            } }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val pendingStart = executor.submit<ChapterTranslationTask> { store.start(f.chapter(), f.config) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                store.finishChapterRemoval(store.beginChapterRemoval(task.chapterId))
                release.countDown()
                try { pendingStart.get(10, TimeUnit.SECONDS); fail("An older start resurrected a deleted chapter task") }
                catch (failure: java.util.concurrent.ExecutionException) { assertTrue(failure.cause is CancellationException) }
                assertNull(store.get(task.id))
                assertFalse(File(f.journals, task.id + ".json").exists())
                val later = store.start(f.chapter(), f.config)
                assertNotEquals(task.generation, later.generation)
                assertFalse(store.commitPage(task.id, task.generation, result))
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun failedJournalDeletionKeepsTheChapterFencedUntilAnExplicitRetry() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.markRunning(task.id, task.generation)
            val result = f.completed(store, task)
            assertTrue(store.commitPage(task.id, task.generation, result))
            val removal = store.beginChapterRemoval(task.chapterId)
            f.failDelete = true
            try { store.finishChapterRemoval(removal); fail("Expected delete interruption") }
            catch (_: java.io.IOException) { }
            assertFalse(store.isCurrent(task.id, task.generation))
            assertFalse(store.commitPage(task.id, task.generation, result))
            assertTrue(File(result.cleanedPath!!).exists())
            assertTrue(f.source.exists())
            f.failDelete = false
            store.finishChapterRemoval(store.beginChapterRemoval(task.chapterId))
            assertNull(store.get(task.id))
            assertFalse(File(result.cleanedPath).exists())
        }
    }

    @Test fun requestedPageRepairInvalidatesItsChangedSourceAndKeepsVerifiedNeighbours() = runBlocking {
        Fixture().use { f ->
            val source2 = File(f.sources, "2.img").apply { writeText("second original") }
            val chapter = f.chapter().copy(pages = f.chapter().pages + ChapterPage(2, "content://second", source2.absolutePath))
            val store = f.store()
            val task = store.start(chapter, f.config)
            store.markRunning(task.id, task.generation)
            assertTrue(store.commitPage(task.id, task.generation, f.completed(store, task)))
            val neighbour = f.completed(store, task, index = 2)
            assertTrue(store.commitPage(task.id, task.generation, neighbour))
            store.finish(task.id, task.generation)
            f.source.writeText("different img")
            val repair = store.start(chapter, f.config, requestedPages = listOf(1))
            assertEquals(task.id, repair.id)
            assertNotEquals(task.generation, repair.generation)
            assertEquals(listOf(1), repair.requestedPages)
            assertEquals(ChapterTranslationPageStatus.PENDING, repair.pages.first().status)
            assertEquals(neighbour, repair.pages.last())
            val reopened = f.store().refresh(repair.id)!!
            assertEquals(listOf(1), reopened.requestedPages)
            assertEquals(neighbour.cleanedPath, reopened.pages.last().cleanedPath)
            assertFalse(store.commitPage(task.id, task.generation, neighbour))
        }
    }

    @Test fun invalidRequestedIndicesNeverReplaceOrExpandTheExistingTask() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            for (scope in listOf(emptyList(), listOf(2), listOf(1, 1), listOf(-1))) {
                try { store.start(f.chapter(), f.config, requestedPages = scope); fail("Accepted invalid page scope $scope") }
                catch (_: IllegalArgumentException) { }
                assertEquals(task, store.get(task.id))
            }
        }
    }

    @Test fun scopedWorkerCannotBeginAnotherPageAndResumeKeepsItsCapturedScope() {
        Fixture().use { f ->
            val source2 = File(f.sources, "2.img").apply { writeText("second original") }
            val chapter = f.chapter().copy(pages = f.chapter().pages + ChapterPage(2, "content://second", source2.absolutePath))
            val store = f.store()
            val scoped = store.start(chapter, f.config, requestedPages = listOf(1))
            store.markRunning(scoped.id, scoped.generation)
            assertNull(store.beginPage(scoped.id, scoped.generation, 2))
            assertEquals(ChapterTranslationPageStatus.PENDING, store.get(scoped.id)!!.pages.last().status)
            assertTrue(store.commitPage(scoped.id, scoped.generation, f.completed(store, scoped)))
            val partial = store.finish(scoped.id, scoped.generation)!!
            assertEquals(ChapterTranslationStatus.PARTIAL, partial.status)
            val resumed = store.resume(partial.id, partial.generation)!!
            assertEquals(listOf(1), resumed.requestedPages)
            assertNull(store.beginPage(resumed.id, resumed.generation, 2))
            val whole = store.start(chapter, f.config)
            assertEquals(scoped.id, whole.id)
            assertNull(whole.requestedPages)
            assertNotNull(store.beginPage(whole.id, whole.generation, 2))
        }
    }

    @Test fun ownedRequestReplayKeepsExactGenerationStatusAndPagePhase() {
        for (status in ChapterTranslationStatus.entries) Fixture().use { f ->
            val store = f.store()
            val owner = "orez:fixture:" + status.name
            val task = store.start(f.chapter(), f.config, requestedPages = listOf(1), ownerRequestId = owner)
            when (status) {
                ChapterTranslationStatus.QUEUED -> Unit
                ChapterTranslationStatus.RUNNING -> { store.markRunning(task.id, task.generation); store.beginPage(task.id, task.generation, 1) }
                ChapterTranslationStatus.PAUSED -> store.pause(task.id, task.generation)
                ChapterTranslationStatus.CANCELLED -> store.cancel(task.id, task.generation)
                ChapterTranslationStatus.FAILED -> store.fail(task.id, task.generation, "model unavailable")
                ChapterTranslationStatus.COMPLETED, ChapterTranslationStatus.PARTIAL -> {
                    store.markRunning(task.id, task.generation)
                    assertTrue(store.commitPage(task.id, task.generation, f.completed(store, task,
                        if (status == ChapterTranslationStatus.PARTIAL) ChapterTranslationPageStatus.PARTIAL else ChapterTranslationPageStatus.COMPLETED)))
                    store.finish(task.id, task.generation)
                }
            }
            val before = store.get(task.id)!!
            val replay = store.start(f.chapter(), f.config.copy(targetLanguage = "HI"), listOf(1), owner)
            assertEquals(owner, replay.ownerRequestId)
            assertEquals(before.generation, replay.generation)
            assertEquals(status, replay.status)
            assertEquals(before.pages, replay.pages)
        }
    }

    @Test fun ownershipAndLimitedScopeSurviveReopenAndExplicitResume() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val owner = "orez:stable-request-0"
            val task = store.start(f.chapter(), f.config, listOf(1), owner)
            store.pause(task.id, task.generation)
            val reopened = f.store()
            val replay = reopened.start(f.chapter(), f.config, listOf(1), owner)
            assertEquals(owner, replay.ownerRequestId)
            assertEquals(task.generation, replay.generation)
            assertEquals(ChapterTranslationStatus.PAUSED, replay.status)
            val resumed = reopened.resume(task.id, task.generation)!!
            assertNotEquals(task.generation, resumed.generation)
            assertEquals(owner, resumed.ownerRequestId)
            assertEquals(listOf(1), resumed.requestedPages)
            val checked = f.store().refresh(task.id)!!
            assertEquals(owner, checked.ownerRequestId)
            assertEquals(resumed.generation, checked.generation)
        }
    }

    @Test fun ownedReplayRequiresUnchangedScopeConfigAndSourceBytes() {
        Fixture().use { f ->
            val store = f.store()
            val owner = "orez:stable-request"
            val first = store.start(f.chapter(), f.config, listOf(1), owner)
            val full = store.start(f.chapter(), f.config, ownerRequestId = owner)
            assertNotEquals(first.generation, full.generation)
            assertNull(full.requestedPages)
            f.source.writeText("different img")
            val changed = store.start(f.chapter(), f.config, ownerRequestId = owner)
            assertNotEquals(full.generation, changed.generation)
            assertNotEquals(full.pages.single().sourceSha256, changed.pages.single().sourceSha256)
            val otherConfig = store.start(f.chapter(), f.config.copy(ocrScript = "JAPANESE"), ownerRequestId = owner)
            assertNotEquals(changed.id, otherConfig.id)
            assertEquals(owner, otherConfig.ownerRequestId)
        }
    }

    @Test fun freshAndUnownedRequestsReplaceOwnershipWithoutLettingOldControlsAct() {
        Fixture().use { f ->
            val store = f.store()
            val old = store.start(f.chapter(), f.config, ownerRequestId = "orez:first")
            val fresh = store.start(f.chapter(), f.config, ownerRequestId = "orez:second")
            assertNotEquals(old.generation, fresh.generation)
            assertEquals("orez:second", fresh.ownerRequestId)
            assertNull(store.pause(old.id, old.generation))
            assertNull(store.cancel(old.id, old.generation))
            val manual = store.start(f.chapter(), f.config)
            assertNull(manual.ownerRequestId)
            assertNotEquals(fresh.generation, manual.generation)
            assertNull(store.cancel(fresh.id, fresh.generation))
            assertNotEquals(manual.generation, store.start(f.chapter(), f.config).generation)
        }
    }

    @Test fun failedRemovalJournalWriteStillFencesExplicitResume() {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter(), f.config)
            store.pause(task.id, task.generation)
            f.failWrite = true
            try { store.beginChapterRemoval(task.chapterId); fail("Removal should fail its atomic cancellation checkpoint") }
            catch (_: IOException) { }
            f.failWrite = false
            assertNull(store.resume(task.id, task.generation))
            val removal = store.beginChapterRemoval(task.chapterId)
            store.finishChapterRemoval(removal)
            assertNull(store.get(task.id))
            assertTrue(f.source.exists())
        }
    }

    @Test fun heldStartCompletionCannotHandAnotherOwnersGenerationToPendingCancel() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val first = store.start(f.chapter(), f.config, ownerRequestId = "orez:first-request")
            val scheduling = CompletableDeferred<Unit>()
            val dispatched = async {
                scheduling.await()
                store.commandSnapshot(first)
            }
            val replacement = store.start(f.chapter(), f.config, ownerRequestId = "orez:replacement")
            store.markRunning(replacement.id, replacement.generation)
            scheduling.complete(Unit)
            val receipt = dispatched.await()
            assertEquals(first.generation, receipt.generation)
            assertEquals(first.ownerRequestId, receipt.ownerRequestId)
            assertNull(store.cancel(receipt.id, receipt.generation))
            assertEquals(ChapterTranslationStatus.RUNNING, store.get(replacement.id)!!.status)
            assertEquals(replacement.generation, store.get(replacement.id)!!.generation)
        }
    }

    @Test fun pauseResumeAndCancelReceiptsCannotRebaseAfterAnotherCommandReplacesTheirRun() {
        Fixture().use { f ->
            val store = f.store()
            val first = store.start(f.chapter(), f.config, ownerRequestId = "orez:first-request")
            val paused = store.pause(first.id, first.generation)!!
            val resumed = store.resume(paused.id, paused.generation)!!
            assertEquals(resumed.generation, store.commandSnapshot(resumed).generation)
            assertEquals(paused.generation, store.commandSnapshot(paused).generation)
            val cancelled = store.cancel(resumed.id, resumed.generation)!!
            val replacement = store.start(f.chapter(), f.config, ownerRequestId = "orez:replacement")
            for (captured in listOf(paused, resumed, cancelled)) {
                val receipt = store.commandSnapshot(captured)
                assertEquals(captured, receipt)
                assertNull(store.pause(receipt.id, receipt.generation))
                assertNull(store.cancel(receipt.id, receipt.generation))
                assertNull(store.resume(receipt.id, receipt.generation))
            }
            assertEquals(replacement, store.get(replacement.id))
        }
    }

    @Test fun ownedReplayCannotReplaceReaderOwnershipCommittedDuringSourceValidation() {
        Fixture().use { f ->
            val store = f.store()
            val old = store.start(f.chapter(), f.config, ownerRequestId = "orez:replaying-request")
            store.markRunning(old.id, old.generation)
            assertTrue(store.commitPage(old.id, old.generation, f.completed(store, old)))
            store.finish(old.id, old.generation)
            val validating = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blockedOnce = AtomicBoolean(false)
            f.surfaceCheck = {
                if (blockedOnce.compareAndSet(false, true)) {
                    validating.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val replay = executor.submit<ChapterTranslationTask> {
                    store.start(f.chapter(), f.config, ownerRequestId = old.ownerRequestId, allowOwnerReplacement = false)
                }
                assertTrue(validating.await(5, TimeUnit.SECONDS))
                val reader = store.start(f.chapter(), f.config)
                store.markRunning(reader.id, reader.generation)
                release.countDown()
                try { replay.get(5, TimeUnit.SECONDS); fail("Replay overwrote a newer reader-owned task") }
                catch (failure: java.util.concurrent.ExecutionException) { assertTrue(failure.cause is IllegalStateException) }
                assertEquals(reader.generation, store.get(reader.id)!!.generation)
                assertNull(store.get(reader.id)!!.ownerRequestId)
                assertEquals(ChapterTranslationStatus.RUNNING, store.get(reader.id)!!.status)
                assertNull(store.cancel(old.id, old.generation))
                val explicit = store.start(f.chapter(), f.config, ownerRequestId = "orez:fresh-explicit-request")
                assertNotEquals(reader.generation, explicit.generation)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test fun ownerReplacementPreconditionAllowsAbsentAndSameOwnerButRejectsDifferentOwnerWithoutMutation() {
        Fixture().use { f ->
            val store = f.store()
            val first = store.start(f.chapter(), f.config, ownerRequestId = "orez:same", allowOwnerReplacement = false)
            val replay = store.start(f.chapter(), f.config, ownerRequestId = "orez:same", allowOwnerReplacement = false)
            assertEquals(first, replay)
            try {
                store.start(f.chapter(), f.config, ownerRequestId = "orez:different", allowOwnerReplacement = false)
                fail("Different owner replaced an existing request")
            } catch (_: IllegalStateException) { }
            assertEquals(first, store.get(first.id))
        }
    }
}
