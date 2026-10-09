package com.mangalens.core.translation

import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class NativeMemoryPublicationAdapterTest {
    @Test fun verifiedColdCompletedAndPartialPageZeroUseSavedOriginalBoundsAndKeepNativeBytes() = runBlocking {
        for (partial in listOf(false, true)) Fixture().use { f ->
            val original = f.completed(partial)
            val originalJournal = f.journal(original.id).readBytes()
            val sources = f.source.readBytes(); val output = File(original.pages.single().cleanedPath!!).readBytes()
            val store = f.store()
            val checked = store.refresh(original.id, original.generation)!!
            assertEquals(if (partial) ChapterTranslationStatus.PARTIAL else ChapterTranslationStatus.COMPLETED, checked.status)
            val authority = ReaderMemoryPublicationAuthority()
            val selected = authority.activate(ReaderTranslationPresentation.receipt(checked))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val editor = adapter.openEditor(selected, 0, 0)
            assertNotNull("A verified saved result must publish without an active worker", editor)
            val captured = editor!!.receipt
            assertEquals(0, captured.source.pageIndex)
            assertEquals(1001, captured.source.imageWidth); assertEquals(1009, captured.source.imageHeight)
            assertEquals(MemoryRegionBounds(4, 6, 93, 143), captured.source.bounds)
            assertEquals("reader:fixture", captured.ownerRequestId)
            assertEquals(selected.epoch, captured.presentationEpoch)
            adapter.correct(editor, 0, MemoryCorrectionEdit(correctedOcr = "Hello, friend.", translated = "नमस्ते, मित्र।"))
            val reopened = SeriesMemoryStore(f.root).inspectChapter(f.chapter.id).bubbles.single()
            assertEquals("Hello, friend.", reopened.correction!!.edit.correctedOcr)
            assertArrayEquals(originalJournal, f.journal(original.id).readBytes())
            assertArrayEquals(sources, f.source.readBytes()); assertArrayEquals(output, File(original.pages.single().cleanedPath!!).readBytes())
        }
    }


    @Test fun navigationDuringActualSyncedPreparationRejectsHeldEditorWithoutBlockingRetirement() = heldEdit { _, _, authority, _, _ -> authority.retire() }

    @Test fun exactSameReceiptSelectedAgainRetiresTheOldPresentationEpoch() = heldEdit { _, _, authority, selection, _ ->
        assertNotEquals(selection.epoch, authority.activate(selection.receipt).epoch)
    }

    @Test fun replacedGenerationAndOwnerRejectG1ThenOnlyTheActualG2CanPublish() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val first = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority()
            val selected = authority.activate(ReaderTranslationPresentation.receipt(first))
            val writer = HeldWriter(); val adapter = NativeMemoryPublicationAdapter(f.root, store, authority, writer)
            val oldEditor = adapter.openEditor(selected, 0, 0)!!
            val before = memoryJournal(f).readBytes()
            writer.hold.set(true)
            val pending = async(Dispatchers.Default) { runCatching { adapter.correct(oldEditor, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) } }
            try {
                assertTrue(writer.prepared.await(5, TimeUnit.SECONDS))
                val second = withTimeout(2_000) { withContext(Dispatchers.Default) {
                    f.completed(nativeStore = store, owner = "reader:G2", forceReprocess = true)
                } }
                assertNotEquals(first.generation, second.generation)
                val current = authority.activate(ReaderTranslationPresentation.receipt(second))
                writer.release.countDown()
                assertTrue(pending.await().isFailure)
                assertArrayEquals(before, memoryJournal(f).readBytes())
                val newEditor = adapter.openEditor(current, 0, 0)!!
                val saved = adapter.correct(newEditor, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
                assertEquals(1, saved.revision)
                assertEquals("reader:G2", adapter.inspect(newEditor)!!.receipt.ownerRequestId)
            } finally { writer.release.countDown(); pending.cancelAndJoin() }
        }
    }

    @Test fun taskDeletionDuringPreparationCannotResurrectMemory() = heldEdit { f, store, _, _, task ->
        store.finishChapterRemoval(store.beginChapterRemoval(f.chapter.id))
        assertNull(store.get(task.id))
    }

    @Test fun sameLengthSourceByteReplacementDuringPreparationRejectsTheHeldEdit() = heldEdit { f, _, _, _, _ ->
        val previous = f.source.readText(); f.source.writeText(previous.replaceFirst("original", "replaced"))
        assertEquals(previous.length, f.source.length().toInt())
    }

    @Test fun sameLengthOutputByteReplacementDuringPreparationRejectsTheHeldEdit() = heldEdit { _, _, _, _, task ->
        val output = File(task.pages.single().cleanedPath!!)
        val previous = output.readText(); output.writeText(previous.replaceFirst("saved", "other"))
        assertEquals(previous.length, output.length().toInt())
    }

    @Test fun relinkToExactSameSeriesAndOrdinalRejectsAPreviouslyCapturedEditor() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            adapter.memory.createSeries("Explicit series", "series")
            adapter.memory.associateChapter(f.chapter.id, "series", 0)
            val old = adapter.openEditor(selected, 0, 0)!!
            assertEquals(1L, old.receipt.associationRevision)
            adapter.memory.unlinkChapter(f.chapter.id)
            adapter.memory.associateChapter(f.chapter.id, "series", 0)
            assertEquals(3L, adapter.memory.inspectChapter(f.chapter.id).associationRevision)
            val before = memoryJournal(f).readBytes()
            assertTrue(runCatching { adapter.correct(old, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) }.isFailure)
            assertArrayEquals(before, memoryJournal(f).readBytes())
            val fresh = adapter.openEditor(selected, 0, 0)!!
            adapter.correct(fresh, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
            assertEquals(3L, adapter.inspect(fresh)!!.receipt.associationRevision)
        }
    }

    @Test fun removedSeriesCannotAcceptHeldCorrectionsOrNewEditors() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            adapter.memory.createSeries("Removed series", "removed-series")
            adapter.memory.associateChapter(f.chapter.id, "removed-series", 0)
            val editor = adapter.openEditor(selected, 0, 0)!!
            adapter.memory.removeSeries("removed-series")
            val before = memoryJournal(f).readBytes()
            assertTrue(runCatching { adapter.correct(editor, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) }.isFailure)
            assertArrayEquals(before, memoryJournal(f).readBytes())
            assertNull(adapter.openEditor(selected, 0, 0))
        }
    }

    @Test fun savedOriginalDimensionsMustMatchFreshSourceInspection() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            f.actualSourceDimensions = 2002 to 2018
            assertNull("Stored source dimensions are not evidence of the actual image", adapter.openEditor(selected, 0, 0))
            assertTrue(adapter.memory.inspectChapter(f.chapter.id).bubbles.isEmpty())
        }
    }

    @Test fun legacySampledLetteringRemainsReadableButDoesNotInventOriginalBounds() = runBlocking {
        Fixture().use { f ->
            val task = f.completed(legacyGeometry = true)
            val json = JSONObject(f.journal(task.id).readText()).put("version", 1)
            f.journal(task.id).writeText(json.toString())
            val store = f.store(); val verified = store.refresh(task.id)!!
            assertEquals(ChapterTranslationStatus.COMPLETED, verified.status)
            assertEquals(task.pages.single().lettering, verified.pages.single().lettering)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(verified))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            assertNull(adapter.openEditor(selected, 0, 0))
            assertTrue(adapter.memory.inspectChapter(f.chapter.id).bubbles.isEmpty())
        }
    }

    @Test fun awaitingColdFileValidationAndCancelledResultsCannotPublish() = runBlocking {
        Fixture().use { f ->
            val task = f.completed(); val store = f.store()
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            assertNull(adapter.openEditor(selected, 0, 0))
            val verified = store.refresh(task.id)!!
            assertNotNull(adapter.openEditor(selected, 0, 0))
            store.beginChapterRemoval(verified.chapterId)
            assertNull(adapter.openEditor(selected, 0, 0))
        }
    }

    @Test fun failingPreparedWriteLeavesThePreviousCorrectionAndNativeJournalIntact() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val good = NativeMemoryPublicationAdapter(f.root, store, authority)
            val editor = good.openEditor(selected, 0, 0)!!
            val accepted = good.correct(editor, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
            val before = memoryJournal(f).readBytes(); val nativeBefore = f.journal(task.id).readBytes()
            val broken = NativeMemoryPublicationAdapter(f.root, store, authority, MemoryJournalWriter { _, _ -> throw java.io.IOException("Injected fsync failure") })
            assertTrue(runCatching { broken.correct(editor, 1, MemoryCorrectionEdit(translated = "नमस्ते, साथी।")) }.isFailure)
            assertArrayEquals(before, memoryJournal(f).readBytes()); assertArrayEquals(nativeBefore, f.journal(task.id).readBytes())
            assertEquals(accepted, good.inspect(editor)!!.correction)
        }
    }

    @Test fun legacyReceiptCannotGainNativeAuthorityByCopyingOnlyTheOwner() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val actual = adapter.openEditor(selected, 0, 0)!!
            val legacy = actual.receipt.copy(nativeAuthorityVersion = 0, presentationEpoch = null, associationRevision = null)
            val encoded = SeriesMemoryCodec.chapter(MemoryChapterJournal(f.chapter.id, bubbles = listOf(MemoryIndexedBubble(legacy))))
            memoryJournal(f).writeBytes(encoded)
            val before = memoryJournal(f).readBytes()
            assertTrue(runCatching { adapter.correct(MemoryBubbleEditor(legacy), 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) }.isFailure)
            assertArrayEquals(before, memoryJournal(f).readBytes())
        }
    }

    private fun memoryJournal(f: Fixture) = File(f.root, "reader_memory/chapters/${f.chapter.id}.json")

    private class HeldWriter : MemoryJournalWriter {
        val hold = AtomicBoolean(false); val prepared = CountDownLatch(1); val release = CountDownLatch(1)
        override fun prepare(file: File, bytes: ByteArray): PreparedMemoryJournal {
            val pending = AtomicMemoryJournalWriter.prepare(file, bytes)
            try {
                if (hold.getAndSet(false)) { prepared.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return pending
            } catch (failure: Throwable) { pending.close(); throw failure }
        }
    }

    private fun heldEdit(mutation: (Fixture, ChapterTranslationStore, ReaderMemoryPublicationAuthority, ReaderMemoryPresentation, ChapterTranslationTask) -> Unit) = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val writer = HeldWriter(); val adapter = NativeMemoryPublicationAdapter(f.root, store, authority, writer)
            val editor = adapter.openEditor(selected, 0, 0)!!
            val before = memoryJournal(f).readBytes()
            writer.hold.set(true)
            val pending = async(Dispatchers.Default) { runCatching { adapter.correct(editor, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) } }
            try {
                assertTrue(writer.prepared.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { withContext(Dispatchers.Default) { mutation(f, store, authority, selected, task) } }
                writer.release.countDown()
                assertTrue("Stale native evidence published after prepared fsync", pending.await().isFailure)
                assertArrayEquals(before, memoryJournal(f).readBytes())
            } finally { writer.release.countDown(); pending.cancelAndJoin() }
        }
    }

    @Test fun alteredOwnerConfigurationOrFullSourceReceiptCannotOpenTheSavedBubble() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority()
            val actual = ReaderTranslationPresentation.receipt(task)
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val choices = listOf(actual.copy(owner = "another-reader"), actual.copy(generation = "f".repeat(32)),
                actual.copy(configuration = actual.configuration.copy(highAccuracy = false)),
                actual.copy(sources = actual.sources.map { it.copy(sha256 = "f".repeat(64)) }),
                actual.copy(chapterId = "b".repeat(32)))
            for (choice in choices) assertNull(adapter.openEditor(authority.activate(choice), 0, 0))
            assertTrue(adapter.memory.inspectChapter(f.chapter.id).bubbles.isEmpty())
        }
    }

    @Test fun nativeRevisionRemoveAndRollbackCannotReviveTheHeldOldEdit() = runBlocking {
        Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val editor = adapter.openEditor(selected, 0, 0)!!
            adapter.correct(editor, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
            adapter.rollback(editor, 1, 0)
            assertEquals(MemoryCorrectionEdit(), adapter.inspect(editor)!!.correction!!.edit)
            adapter.removeCorrection(editor, 2)
            assertNull(adapter.inspect(editor)!!.correction)
            assertEquals(3, adapter.inspect(editor)!!.editRevision)
            assertTrue(runCatching { adapter.correct(editor, 0, MemoryCorrectionEdit(translated = "नमस्ते, साथी।")) }.isFailure)
            adapter.correct(editor, 3, MemoryCorrectionEdit(translated = "नमस्ते, साथी।"))
            assertEquals(4, adapter.inspect(editor)!!.editRevision)
        }
    }

    internal class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("native-reader-memory").toFile()
        val sources = File(root, "chapters").apply { mkdirs() }
        val journals = File(root, "chapter_translations").apply { mkdirs() }
        val source = File(sources, "page-zero.jpg").apply { writeText("original source bytes") }
        val chapter = SavedChapter("a".repeat(32), "Native fixture", "local:fixture", listOf(ChapterPage(0, "local:zero", source.path)))
        val config = ChapterTranslationConfig("hi")
        val io = object : ChapterJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) {
                val temp = File(file.parentFile, file.name + ".test-pending")
                temp.outputStream().use { it.write(bytes); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        var actualSourceDimensions = 1001 to 1009
        fun store() = ChapterTranslationStore(journals, sources, io, originalDimensions = { actualSourceDimensions }) { it.readText().startsWith("valid surface:") }
        fun journal(id: String) = File(journals, "$id.json")
        fun completed(partial: Boolean = false, legacyGeometry: Boolean = false, nativeStore: ChapterTranslationStore = store(),
            owner: String = "reader:fixture", forceReprocess: Boolean = false): ChapterTranslationTask {
            val store = nativeStore
            val task = store.start(chapter, config, ownerRequestId = owner, forceReprocess = forceReprocess)
            store.markRunning(task.id, task.generation)
            val page = store.beginPage(task.id, task.generation, 0)!!
            val output = store.createOutputFile(task.id, task.generation, 0).apply { writeText("valid surface: saved paper") }
            val bounds = OriginalMangaGeometry.fromSampled(1, 2, 23, 47, 250, 333, 1001, 1009)
            assertTrue(store.commitPage(task.id, task.generation, page.copy(
                status = if (partial) ChapterTranslationPageStatus.PARTIAL else ChapterTranslationPageStatus.COMPLETED,
                cleanedPath = output.path, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 250, imageHeight = 333,
                lettering = listOf(SavedMangaLettering("Hello.", "नमस्ते।", 0, 0, 100, 100, "sans-serif", 0, -16777216, 20f,
                    "ALIGN_CENTER", 1, 2, 23, 47, originalSourceBounds = if (legacyGeometry) null else bounds)),
                rejectedRegions = if (partial) 1 else 0,
                originalWidth = if (legacyGeometry) null else 1001, originalHeight = if (legacyGeometry) null else 1009)))
            return store.finish(task.id, task.generation)!!
        }
        override fun close() { root.deleteRecursively() }
    }
}
