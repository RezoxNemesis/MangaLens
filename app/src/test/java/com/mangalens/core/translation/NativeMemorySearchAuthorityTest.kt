package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual journals/fences; the controlled textual surface predicate makes no model-quality claim. */
class NativeMemorySearchAuthorityTest {
    @Test fun completedAndPartialPageZeroHitsPublishOnlyActualOriginalEvidenceAndDoNotWriteJournals() = runBlocking {
        for (partial in listOf(false, true)) fixture(partial = partial) { f, native, memory, _, snapshot, hit ->
            val nativeBefore = f.journal(hitReceipt(snapshot).taskId).readBytes()
            val memoryBefore = memoryJournal(f).readBytes()
            val authority = NativeMemorySearchAuthority(native)
            val prepared = authority.prepare(snapshot, hit)!!
            assertEquals(listOf(hit), authority.publish(listOf(prepared)) { it })
            assertArrayEquals(nativeBefore, f.journal(hitReceipt(snapshot).taskId).readBytes())
            assertArrayEquals(memoryBefore, memoryJournal(f).readBytes())
            assertEquals(MemoryRegionBounds(4, 6, 93, 143), hit.source.bounds)
        }
    }

    @Test fun G2OwnerReplacementDuringHeldProofCannotPublishPreparedG1Hit() = heldRace { f, native, _ ->
        val replacement = f.completed(nativeStore = native, owner = "reader:G2", forceReprocess = true)
        assertEquals("reader:G2", replacement.ownerRequestId)
    }

    @Test fun chapterDeletionDuringHeldProofCannotPublishAnEarlierHit() = heldRace { f, native, _ ->
        native.finishChapterRemoval(native.beginChapterRemoval(f.chapter.id))
    }

    @Test fun pauseAndCancelDuringHeldProofCannotPublishEarlierHits() = runBlocking {
        for (cancel in listOf(false, true)) heldRace(running = true) { _, native, task ->
            val stopped = if (cancel) native.cancel(task.id, task.generation) else native.pause(task.id, task.generation)
            assertNotNull(stopped)
        }
    }

    @Test fun sourceOrOutputReplacementAfterHashingCannotPublishPreparedHit() = runBlocking {
        for (source in listOf(false, true)) heldRace { f, _, task ->
            val file = if (source) f.source else File(task.pages.single().cleanedPath!!)
            file.appendText(" replaced")
        }
    }

    @Test fun aReturnedHitCannotSubstituteAnotherSourceTextTargetOrRevision() = fixture { _, native, _, _, snapshot, hit ->
        val authority = NativeMemorySearchAuthority(native)
        for (substitution in listOf(hit.copy(source = hit.source.copy(sourceSha256 = "f".repeat(64))),
            hit.copy(text = "fabricated OCR"), hit.copy(targetLanguage = "en"), hit.copy(revision = hit.revision + 1),
            hit.copy(configurationIdentity = "other config"))) assertNull(authority.prepare(snapshot, substitution))
    }

    @Test fun coldPendingValidationIsNotPromotedByTheReadOnlySearchAuthority() = fixture { f, _, _, _, snapshot, hit ->
        val cold = f.store()
        assertNull(NativeMemorySearchAuthority(cold).prepare(snapshot, hit))
    }

    private fun heldRace(running: Boolean = false,
        retire: suspend (NativeMemoryPublicationAdapterTest.Fixture, ChapterTranslationStore, ChapterTranslationTask) -> Unit) =
        fixture(running = running) { f, native, _, task, snapshot, hit ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val authority = NativeMemorySearchAuthority(native) { receipt, pageIndex ->
                val actual = native.prepareMemoryPublication(receipt, pageIndex)
                check(actual != null)
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); actual
            }
            val pending = async(Dispatchers.Default) {
                val prepared = authority.prepare(snapshot, hit)!!
                authority.publish(listOf(prepared)) { it }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { withContext(Dispatchers.Default) { retire(f, native, task) } }
                release.countDown()
                assertTrue("Retired native authority must suppress the read-only hit", pending.await().isEmpty())
            } finally { release.countDown(); pending.cancelAndJoin() }
        }

    private fun fixture(partial: Boolean = false, running: Boolean = false,
        body: suspend CoroutineScope.(NativeMemoryPublicationAdapterTest.Fixture, ChapterTranslationStore, SeriesMemoryStore,
            ChapterTranslationTask, MemoryChapterSnapshot, MemorySearchHit) -> Unit) = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); var task = f.completed(partial, nativeStore = native)
            if (running) {
                task = native.start(f.chapter, f.config, ownerRequestId = "reader:running")
                task = native.markRunning(task.id, task.generation)!!
            }
            val reader = ReaderMemoryPublicationAuthority(); val selected = reader.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, native, reader)
            assertNotNull(adapter.openEditor(selected, 0, 0))
            val snapshot = adapter.memory.inspectChapter(f.chapter.id)
            val hit = adapter.memory.search("Hello", 8, chapterIds = setOf(f.chapter.id)).hits.single()
            body(f, native, adapter.memory, task, snapshot, hit)
        }
    }
    private fun hitReceipt(snapshot: MemoryChapterSnapshot) = snapshot.bubbles.single().receipt
    private fun memoryJournal(f: NativeMemoryPublicationAdapterTest.Fixture) =
        File(f.root, "reader_memory/chapters/${f.chapter.id}.json")
}
