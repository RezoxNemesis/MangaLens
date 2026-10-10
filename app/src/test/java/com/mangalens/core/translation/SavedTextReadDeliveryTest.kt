package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual persisted native/personal data; controlled surface predicates are not model/render quality evidence. */
class SavedTextReadDeliveryTest {
    @Test fun unchangedCompletedAndPartialOriginalPageZeroProofDeliversWithoutWritingAnyJournal() = runBlocking {
        for (partial in listOf(false, true)) fixture(partial) { f, native, memory, selected, snapshot, proof ->
            val before = bytes(f, selected.receipt.taskId)
            val lease = memory.prepareReadDelivery(snapshot)!!
            var delivered = false
            assertTrue(lease.tryCommit { native.tryCommitMemoryDelivery(selected.receipt, proof) { delivered = true } } == true)
            assertTrue(delivered)
            assertEquals(before.keys, bytes(f, selected.receipt.taskId).keys)
            before.forEach { (path, expected) -> assertArrayEquals(path, expected, File(path).readBytes()) }
        }
    }

    @Test fun replacementWithIdenticalTextAndSourceStillRejectsG1AtHeldMainDelivery() = retired { f, native, _, _, _, _ ->
        f.completed(nativeStore = native, owner = "reader:new", forceReprocess = true)
    }

    @Test fun sourceReplacementAfterIoAcceptanceCannotReachMain() = retired { f, _, _, _, _, _ -> f.source.appendText(" replacement") }

    @Test fun outputReplacementAfterIoAcceptanceCannotReachMain() = retired { _, _, _, _, _, proof -> File(proof.page.cleanedPath!!).appendText(" replacement") }

    @Test fun pauseCancelAndChapterRemovalAfterIoAcceptanceRejectMainDelivery() = runBlocking {
        for (action in listOf("pause", "cancel", "delete")) fixture(running = true) { f, native, memory, selected, snapshot, proof ->
            val lease = memory.prepareReadDelivery(snapshot)!!
            when (action) {
                "pause" -> assertNotNull(native.pause(selected.receipt.taskId, selected.receipt.generation))
                "cancel" -> assertNotNull(native.cancel(selected.receipt.taskId, selected.receipt.generation))
                else -> native.finishChapterRemoval(native.beginChapterRemoval(f.chapter.id))
            }
            var delivered = false
            lease.tryCommit { native.tryCommitMemoryDelivery(selected.receipt, proof) { delivered = true } }
            assertFalse(action, delivered)
        }
    }

    @Test fun exactAssociationRelinkAndAbaAfterIoAcceptanceRejectOldMainDelivery() = runBlocking {
        for (aba in listOf(false, true)) fixture(linked = true) { f, native, memory, selected, snapshot, proof ->
            val lease = memory.prepareReadDelivery(snapshot)!!
            val original = snapshot.association!!
            val other = memory.createSeries("Other explicit series")
            memory.associateChapter(f.chapter.id, other.id, 0)
            if (aba) memory.associateChapter(f.chapter.id, original.seriesId, original.ordinal)
            var delivered = false
            lease.tryCommit { native.tryCommitMemoryDelivery(selected.receipt, proof) { delivered = true } }
            assertFalse(delivered)
        }
    }

    @Test fun removingAssociatedProfileAfterIoAcceptanceRejectsOldMainDelivery() = retired(linked = true) { _, _, memory, _, snapshot, _ ->
        memory.removeSeries(snapshot.association!!.seriesId)
    }

    @Test fun correctionEditAndRemovalAfterIoAcceptanceCannotPublishOldRevision() = runBlocking {
        for (remove in listOf(false, true)) fixture { f, native, memory, selected, snapshot, proof ->
            val lease = memory.prepareReadDelivery(snapshot)!!
            val receipt = snapshot.bubbles.single().receipt
            if (remove) memory.removeCorrection(receipt, receipt, 0) else memory.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।"))
            var delivered = false
            lease.tryCommit { native.tryCommitMemoryDelivery(selected.receipt, proof) { delivered = true } }
            assertFalse(delivered)
        }
    }

    @Test fun heldProfileFsyncDoesNotBlockTheMainDeliveryLease() = fixture(linked = true) { f, native, _, selected, snapshot, proof ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val heldWriter = MemoryJournalWriter { file, bytes ->
            val actual = AtomicMemoryJournalWriter.prepare(file, bytes)
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); actual
        }
        val memory = SeriesMemoryStore(f.root, heldWriter)
        val lease = memory.prepareReadDelivery(snapshot)!!
        val write = async(Dispatchers.Default) { memory.setStyle(snapshot.association!!.seriesId, SeriesStylePreference("natural", "hi")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            var delivered = false
            withTimeout(500) { withContext(Dispatchers.Default) {
                assertTrue(lease.tryCommit { native.tryCommitMemoryDelivery(selected.receipt, proof) { delivered = true } } == true)
            } }
            assertTrue("Preparation has not committed a changed profile; current proof may deliver without waiting for fsync", delivered)
            release.countDown(); write.await()
            assertNull(lease.tryCommit { true })
        } finally { release.countDown(); write.cancelAndJoin() }
    }

    @Test fun heldNativeJournalFsyncCannotBlockOrAcceptAPublishedNewGenerationOnMain() = fixture(running = true) { f, native, memory, selected, snapshot, proof ->
        // A real alternate Store uses the same private journals with a held actual IO writer.
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val heldIo = object : ChapterJournalIo {
            override fun read(file: File) = f.io.read(file)
            override fun write(file: File, bytes: ByteArray) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); f.io.write(file, bytes) }
        }
        val heldNative = ChapterTranslationStore(f.journals, f.sources, heldIo, originalDimensions = { f.actualSourceDimensions }) { it.readText().startsWith("valid surface:") }
        // Authorized explicit refresh prepares the cold task before the held writer is invoked.
        val task = heldNative.refresh(selected.receipt.taskId, selected.receipt.generation)!!
        val receipt = ReaderTranslationPresentation.receipt(task)
        val actualProof = heldNative.prepareMemoryPublication(receipt, 0)!!
        val lease = memory.prepareReadDelivery(snapshot)!!
        val pause = async(Dispatchers.Default) { heldNative.pause(receipt.taskId, receipt.generation) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            var delivered = false
            withTimeout(500) { withContext(Dispatchers.Default) {
                assertTrue(lease.tryCommit { heldNative.tryCommitMemoryDelivery(receipt, actualProof) { delivered = true } } == true)
            } }
            assertTrue(delivered)
            release.countDown(); pause.await()
            assertFalse(heldNative.tryCommitMemoryDelivery(receipt, actualProof) {})
        } finally { release.countDown(); pause.cancelAndJoin() }
    }

    @Test fun coldPendingNativeProofNeverBecomesADeliverableLease() = fixture { f, _, memory, selected, snapshot, proof ->
        val cold = f.store(); val lease = memory.prepareReadDelivery(snapshot)!!
        assertFalse(lease.tryCommit { cold.tryCommitMemoryDelivery(selected.receipt, proof) {} } == true)
    }

    private fun retired(linked: Boolean = false,
        retire: suspend CoroutineScope.(NativeMemoryPublicationAdapterTest.Fixture, ChapterTranslationStore, SeriesMemoryStore, ReaderMemoryPresentation, MemoryChapterSnapshot, NativeMemoryPageProof) -> Unit) =
        fixture(linked = linked) { f, native, memory, selected, snapshot, proof ->
            val lease = memory.prepareReadDelivery(snapshot)!!
            // The IO operation finished; this is the deliberately held Main delivery boundary.
            retire(f, native, memory, selected, snapshot, proof)
            var delivered = false
            lease.tryCommit { native.tryCommitMemoryDelivery(selected.receipt, proof) { delivered = true } }
            assertFalse("Old IO acceptance cannot authorize a changed Main publication", delivered)
        }

    private fun fixture(partial: Boolean = false, running: Boolean = false, linked: Boolean = false,
        body: suspend CoroutineScope.(NativeMemoryPublicationAdapterTest.Fixture, ChapterTranslationStore, SeriesMemoryStore, ReaderMemoryPresentation, MemoryChapterSnapshot, NativeMemoryPageProof) -> Unit) = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); var task = f.completed(partial, nativeStore = native)
            if (running) { task = native.start(f.chapter, f.config, ownerRequestId = "reader:running"); task = native.markRunning(task.id, task.generation)!! }
            val memory = SeriesMemoryStore(f.root)
            if (linked) memory.associateChapter(f.chapter.id, memory.createSeries("Explicit series").id, 0)
            val reader = ReaderMemoryPublicationAuthority(); val selected = reader.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, native, reader)
            check(adapter.openEditor(selected, 0, 0) != null)
            val snapshot = memory.inspectChapter(f.chapter.id)
            val proof = native.prepareMemoryPublication(selected.receipt, 0)!!
            body(f, native, adapter.memory, selected, snapshot, proof)
        }
    }
    private fun bytes(f: NativeMemoryPublicationAdapterTest.Fixture, id: String): Map<String, ByteArray> =
        listOf(f.source, f.journal(id), File(f.root, "reader_memory/chapters/${f.chapter.id}.json")).associate { it.path to it.readBytes() }
}
