package com.mangalens.core.translation

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
import java.util.concurrent.atomic.AtomicInteger

/** Prepared actual Store/fsync tests; fixture decoder metadata is not Android pixels or OCR evidence. */
class ReaderBubbleExplicitActionPublicationTest {
    @Test fun explicitIndexCarriesActualNativeEvidenceWithoutChangingOriginalArtifacts() = runBlocking {
        Fixture().use { f ->
            val native = f.nativeBytes(); val original = f.fixture.source.readBytes(); val output = f.output.readBytes()
            val delivery = requireNotNull(f.adapter.openInspectedEditor(f.inspect()))
            val editor = requireNotNull(f.adapter.tryAcceptEditor(delivery))
            assertNotNull(editor.captured.scope)
            assertEquals("Hello.", editor.captured.receipt.originalOcr)
            assertEquals("नमस्ते।", editor.captured.receipt.originalTranslation)
            assertEquals(MemoryRegionBounds(4, 6, 93, 143), editor.captured.receipt.source.bounds)
            assertEquals(f.task.pages.single().lettering.single().savedHindiDraft, editor.savedHindiDraft)
            val encoded = f.journal.readText()
            assertFalse(encoded.contains("\"scope\"")); assertFalse(encoded.contains("\"fileKey\""))
            assertArrayEquals(native, f.nativeBytes()); assertArrayEquals(original, f.fixture.source.readBytes()); assertArrayEquals(output, f.output.readBytes())
        }
    }

    @Test fun alreadyCurrentIndexDoesNotRewriteThePersonalJournal() = runBlocking {
        val count = AtomicInteger()
        val writer = MemoryJournalWriter { file, bytes -> count.incrementAndGet(); AtomicMemoryJournalWriter.prepare(file, bytes) }
        Fixture(writer).use { f ->
            requireNotNull(f.adapter.openInspectedEditor(f.inspect()))
            val before = f.journal.readBytes(); val writes = count.get()
            assertNotNull(f.adapter.openInspectedEditor(f.inspect()))
            assertEquals(writes, count.get()); assertArrayEquals(before, f.journal.readBytes())
        }
    }

    @Test fun anotherPersonalRevisionBeforeExplicitEditRejectsTheHeldReadSnapshot() = runBlocking {
        Fixture().use { f ->
            val editor = requireNotNull(f.adapter.openEditor(f.selected, 0, 0))
            val inspection = f.inspect()
            f.adapter.correct(editor, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
            val before = f.journal.readBytes()
            assertNull(f.adapter.openInspectedEditor(inspection))
            assertArrayEquals(before, f.journal.readBytes())
            assertEquals(1, f.adapter.inspect(editor)!!.editRevision)
        }
    }

    @Test fun sameSeriesRelinkRejectsTheOldBubbleBeforeIndexing() = runBlocking {
        Fixture().use { f ->
            val inspection = f.inspect()
            f.adapter.memory.unlinkChapter(f.fixture.chapter.id)
            f.adapter.memory.associateChapter(f.fixture.chapter.id, "series-one", 0)
            val before = f.journal.readBytes()
            assertNull(f.adapter.openInspectedEditor(inspection)); assertArrayEquals(before, f.journal.readBytes())
        }
    }

    @Test fun identicalBytesWithANewSourceIncarnationCannotIndexTheHeldInspection() = runBlocking {
        Fixture().use { f ->
            val inspection = f.inspect(); val before = f.journal.readBytes(); val source = f.fixture.source.readBytes()
            replaceIdentical(f.fixture.source)
            assertNull(f.adapter.openInspectedEditor(inspection)); assertArrayEquals(before, f.journal.readBytes())
            assertArrayEquals(source, f.fixture.source.readBytes())
        }
    }

    @Test fun nativeG2WithoutReaderRecaptureRejectsTheOldActionAtTheSameIndex() = runBlocking {
        Fixture().use { f ->
            val inspection = f.inspect(); val before = f.journal.readBytes()
            f.fixture.completed(nativeStore = f.store, owner = "reader:G2", forceReprocess = true)
            assertTrue(f.authority.isCurrent(f.selected))
            assertNull(f.adapter.openInspectedEditor(inspection)); assertArrayEquals(before, f.journal.readBytes())
        }
    }

    @Test fun aNewPersonalWriteBeforeMainDeliveryRejectsTheCopiedEditor() = runBlocking {
        Fixture().use { f ->
            val delivery = requireNotNull(f.adapter.openInspectedEditor(f.inspect()))
            f.adapter.correct(delivery.editor.captured, 0, MemoryCorrectionEdit(translated = "नमस्ते, शिक्षक।"))
            assertNull(f.adapter.tryAcceptEditor(delivery))
            assertEquals(1, f.adapter.inspect(delivery.editor.captured)!!.editRevision)
        }
    }

    @Test fun acceptedEditorRetainsTheOriginalFileIncarnationUntilCorrectionPublication() = runBlocking {
        Fixture().use { f ->
            val delivery = requireNotNull(f.adapter.openInspectedEditor(f.inspect()))
            val editor = requireNotNull(f.adapter.tryAcceptEditor(delivery))
            val before = f.journal.readBytes()
            replaceIdentical(f.fixture.source)
            assertTrue(runCatching { f.adapter.correct(editor.captured, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) }.isFailure)
            assertArrayEquals(before, f.journal.readBytes())
            assertTrue(File(f.fixture.root, "reader_memory").walkTopDown().none { it.name.contains(".pending-") })
        }
    }

    @Test fun readerRetirementAfterActualIndexFsyncRejectsTheLateRename() = heldIndex { f -> f.authority.retire() }
    @Test fun nativeReplacementAfterActualIndexFsyncRejectsTheLateRename() = heldIndex { f ->
        f.fixture.completed(nativeStore = f.store, owner = "reader:G2", forceReprocess = true)
    }
    @Test fun identicalSourceReplacementAfterActualIndexFsyncRejectsTheLateRename() = heldIndex { f -> replaceIdentical(f.fixture.source) }
    @Test fun identicalProfileReplacementAfterActualIndexFsyncRejectsTheLateRename() = heldIndex { f -> replaceIdentical(f.profile) }

    @Test fun cancellationAfterIndexFsyncPreservesThePreviousJournalAndCleansPreparation() = runBlocking {
        val writer = HeldWriter()
        Fixture(writer).use { f ->
            val inspection = f.inspect(); val before = f.journal.readBytes(); val original = f.fixture.source.readBytes()
            writer.hold.set(true)
            val pending = async(Dispatchers.Default) { f.adapter.openInspectedEditor(inspection) }
            try {
                assertTrue(writer.prepared.await(5, TimeUnit.SECONDS))
                pending.cancel()
                writer.release.countDown(); pending.join()
                assertTrue(pending.isCancelled)
                assertArrayEquals(before, f.journal.readBytes()); assertArrayEquals(original, f.fixture.source.readBytes())
                assertNoPending(f)
            } finally { writer.release.countDown(); pending.cancelAndJoin() }
        }
    }

    @Test fun failedIndexPreparationLeavesExistingPersonalAndNativeBytesIntact() = runBlocking {
        val fail = AtomicBoolean()
        val writer = MemoryJournalWriter { file, bytes ->
            if (fail.get()) throw java.io.IOException("Injected prepared index failure")
            AtomicMemoryJournalWriter.prepare(file, bytes)
        }
        Fixture(writer).use { f ->
            val inspection = f.inspect(); val before = f.journal.readBytes(); val native = f.nativeBytes()
            fail.set(true)
            assertTrue(runCatching { f.adapter.openInspectedEditor(inspection) }.isFailure)
            assertArrayEquals(before, f.journal.readBytes()); assertArrayEquals(native, f.nativeBytes()); assertNoPending(f)
        }
    }

    @Test fun cancellationAfterCorrectionFsyncPreservesTheAcceptedEditorJournal() = cancelledScopedWrite(glossary = false)
    @Test fun cancellationAfterGlossaryFsyncPreservesThePreviousProfile() = cancelledScopedWrite(glossary = true)

    private fun cancelledScopedWrite(glossary: Boolean) = runBlocking {
        val writer = HeldWriter()
        Fixture(writer).use { f ->
            val delivery = requireNotNull(f.adapter.openInspectedEditor(f.inspect()))
            val editor = requireNotNull(f.adapter.tryAcceptEditor(delivery))
            val inspection = f.inspect()
            val memory = f.journal.readBytes(); val profile = f.profile.readBytes(); val native = f.nativeBytes()
            writer.hold.set(true)
            val pending = async(Dispatchers.Default) {
                if (glossary) f.adapter.addInspectedGlossaryTerm(inspection, editor,
                    SeriesGlossaryTerm("selected-term", "Hello", "नमस्ते", "hi", kind = MemoryTermKind.PHRASE,
                        origin = MemoryLocation(f.fixture.chapter.id, 0), originSourceSha256 = editor.captured.receipt.source.sourceSha256))
                else f.adapter.correct(editor.captured, editor.bubble.editRevision, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
            }
            try {
                assertTrue(writer.prepared.await(5, TimeUnit.SECONDS))
                pending.cancel(); writer.release.countDown(); pending.join()
                assertTrue(pending.isCancelled)
                assertArrayEquals(memory, f.journal.readBytes()); assertArrayEquals(profile, f.profile.readBytes())
                assertArrayEquals(native, f.nativeBytes()); assertNoPending(f)
            } finally { writer.release.countDown(); pending.cancelAndJoin() }
        }
    }

    @Test fun explicitCurrentGenerationRebindPreservesAnExistingPersonalHistory() = runBlocking {
        Fixture().use { f ->
            val old = requireNotNull(f.adapter.openEditor(f.selected, 0, 0))
            f.adapter.correct(old, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
            val newer = f.fixture.completed(nativeStore = f.store, owner = "reader:G2", forceReprocess = true)
            val presentation = f.authority.activate(ReaderTranslationPresentation.receipt(newer))
            val inspection = requireNotNull(f.adapter.inspectSavedBubble(presentation, 0, 0, newer.pages.single().lettering.single()))
            assertNull(requireNotNull(f.adapter.tryAcceptInspection(inspection)).personalTranslation)
            val delivery = requireNotNull(f.adapter.openInspectedEditor(inspection))
            val editor = requireNotNull(f.adapter.tryAcceptEditor(delivery))
            assertEquals(newer.generation, editor.captured.receipt.generation)
            assertEquals(1, editor.bubble.editRevision)
            assertEquals("नमस्ते, मित्र।", editor.bubble.correction!!.edit.translated)
            assertEquals(1, editor.bubble.correction.revisions.size)
        }
    }

    @Test fun legacyInspectionCannotAcquireIndexOrEditAuthority() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(legacyGeometry = true, nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, task.pages.single().lettering.single()))
            assertNull(adapter.openInspectedEditor(inspection)); assertFalse(File(f.root, "reader_memory").exists())
        }
    }

    private fun heldIndex(change: suspend (Fixture) -> Unit) = runBlocking {
        val writer = HeldWriter()
        Fixture(writer).use { f ->
            val inspection = f.inspect(); val before = f.journal.readBytes(); val original = f.fixture.source.readBytes(); val output = f.output.readBytes()
            writer.hold.set(true)
            val pending = async(Dispatchers.Default) { runCatching { f.adapter.openInspectedEditor(inspection) } }
            try {
                assertTrue("Actual prepared index must be fsynced before changing ownership", writer.prepared.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { change(f) }
                writer.release.countDown()
                assertTrue("Old action must fail before the personal journal rename", pending.await().isFailure)
                assertArrayEquals(before, f.journal.readBytes()); assertArrayEquals(original, f.fixture.source.readBytes())
                assertTrue(f.output.isFile); assertArrayEquals(output, f.output.readBytes())
                assertNoPending(f)
            } finally { writer.release.countDown(); pending.cancelAndJoin() }
        }
    }

    private class HeldWriter : MemoryJournalWriter {
        val hold = AtomicBoolean(); val prepared = CountDownLatch(1); val release = CountDownLatch(1)
        override fun prepare(file: File, bytes: ByteArray): PreparedMemoryJournal {
            val pending = AtomicMemoryJournalWriter.prepare(file, bytes)
            try {
                if (hold.getAndSet(false)) { prepared.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return pending
            } catch (failure: Throwable) { pending.close(); throw failure }
        }
    }
    private class Fixture(writer: MemoryJournalWriter = AtomicMemoryJournalWriter) : AutoCloseable {
        val fixture = NativeMemoryPublicationAdapterTest.Fixture()
        val store = fixture.store(); val task = fixture.completed(nativeStore = store)
        val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
        val adapter = NativeMemoryPublicationAdapter(fixture.root, store, authority, writer)
        val journal = File(fixture.root, "reader_memory/chapters/${fixture.chapter.id}.json")
        val profile = File(fixture.root, "reader_memory/series/series-one.json")
        val output = File(task.pages.single().cleanedPath!!)
        init { runBlocking { adapter.memory.createSeries("Explicit series", "series-one"); adapter.memory.associateChapter(fixture.chapter.id, "series-one", 0) } }
        suspend fun inspect() = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, task.pages.single().lettering.single()))
        fun nativeBytes() = fixture.journal(task.id).readBytes()
        override fun close() = fixture.close()
    }
    private fun assertNoPending(f: Fixture) = assertTrue(File(f.fixture.root, "reader_memory").walkTopDown().none { it.name.contains(".pending-") })
    private fun replaceIdentical(file: File) {
        val same = File(file.parentFile, file.name + ".identical-replacement")
        same.outputStream().use { it.write(file.readBytes()); it.fd.sync() }
        Files.move(same.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
