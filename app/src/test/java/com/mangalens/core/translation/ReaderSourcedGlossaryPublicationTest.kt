package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GREEN candidate calls the additive receipt-bearing action; baseline assertions are unchanged.
 * Reuses the existing native-memory JVM fixture and actual fsynced atomic writer.
 * This is ownership/publication evidence, not Android image/OCR/model qualification.
 * Baseline exact source and its two actual failures are sealed in baseline-run/.
 */
class ReaderSourcedGlossaryPublicationTest {
    @Test fun cancellingTheExplicitSourcedTermAfterFsyncKeepsTheOldProfileBytes() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { fixture ->
            val store = fixture.store(); val task = fixture.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority()
            val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val writer = HeldProfileWriter()
            val adapter = NativeMemoryPublicationAdapter(fixture.root, store, authority, writer)
            adapter.memory.createSeries("Explicit series", "series-one")
            adapter.memory.associateChapter(fixture.chapter.id, "series-one", 0)
            val editor = requireNotNull(adapter.openEditor(selected, 0, 0))
            val profile = File(fixture.root, "reader_memory/series/series-one.json")
            val before = profile.readBytes()
            writer.hold.set(true)
            val pending = async(Dispatchers.IO) { adapter.addGlossaryTerm(editor, 0, term(editor)) }
            try {
                assertTrue(writer.prepared.await(5, TimeUnit.SECONDS))
                pending.cancel()
                writer.release.countDown()
                assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
                pending.join()
                assertArrayEquals(before, profile.readBytes())
                assertTrue(adapter.memory.profile("series-one")!!.glossary.isEmpty())
            } finally { writer.release.countDown(); pending.cancelAndJoin() }
        }
    }

    @Test fun navigationDuringSyncedSourcedTermPreparationMustRejectTheLateProfileRename() = held { _, _, authority, _, _ ->
        authority.retire()
    }

    @Test fun replacedNativeGenerationDuringSyncedSourcedTermPreparationMustRejectG1() = held { fixture, store, authority, _, _ ->
        val newer = fixture.completed(nativeStore = store, owner = "reader:G2", forceReprocess = true)
        authority.activate(ReaderTranslationPresentation.receipt(newer))
    }

    @Test fun linkedSavedBubbleTermPromotesWithoutChangingItsNativeSourceOutputOrJournal() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { fixture ->
            val store = fixture.store(); val task = fixture.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority()
            val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(fixture.root, store, authority)
            adapter.memory.createSeries("Explicit series", "series-one")
            adapter.memory.associateChapter(fixture.chapter.id, "series-one", 0)
            val editor = requireNotNull(adapter.openEditor(selected, 0, 0))
            val source = fixture.source.readBytes(); val native = fixture.journal(task.id).readBytes()
            val output = File(task.pages.single().cleanedPath!!).readBytes()
            // The explicit saved-bubble action must preserve this existing valid result.
            adapter.addGlossaryTerm(editor, 0, term(editor))
            val saved = SeriesMemoryStore(fixture.root).profile("series-one")!!.glossary.single()
            assertEquals(editor.receipt.source.sourceSha256, saved.originSourceSha256)
            assertEquals(MemoryLocation(fixture.chapter.id, 0), saved.origin)
            assertArrayEquals(source, fixture.source.readBytes())
            assertArrayEquals(native, fixture.journal(task.id).readBytes())
            assertArrayEquals(output, File(task.pages.single().cleanedPath!!).readBytes())
        }
    }

    @Test fun explicitLibraryTermWithoutSourceOriginRemainsIndependentOfReaderRetirement() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { fixture ->
            val store = fixture.store(); val task = fixture.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority()
            authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(fixture.root, store, authority)
            adapter.memory.createSeries("Explicit series", "series-one")
            authority.retire()
            adapter.memory.upsertTerm("series-one", SeriesGlossaryTerm("user-term", "Friend", "मित्र", "hi"))
            val saved = SeriesMemoryStore(fixture.root).profile("series-one")!!.glossary.single()
            assertEquals("मित्र", saved.preferred); assertNull(saved.origin); assertNull(saved.originSourceSha256)
        }
    }

    private fun term(editor: MemoryBubbleEditor) = SeriesGlossaryTerm("source-term", "Hello", "नमस्ते", "hi",
        origin = MemoryLocation(editor.receipt.source.chapterId, editor.receipt.source.pageIndex),
        originSourceSha256 = editor.receipt.source.sourceSha256)

    private fun held(replace: suspend (NativeMemoryPublicationAdapterTest.Fixture, ChapterTranslationStore,
        ReaderMemoryPublicationAuthority, ReaderMemoryPresentation, ChapterTranslationTask) -> Unit) = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { fixture ->
            val store = fixture.store(); val task = fixture.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority()
            val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val writer = HeldProfileWriter()
            val adapter = NativeMemoryPublicationAdapter(fixture.root, store, authority, writer)
            adapter.memory.createSeries("Explicit series", "series-one")
            adapter.memory.associateChapter(fixture.chapter.id, "series-one", 0)
            val editor = requireNotNull(adapter.openEditor(selected, 0, 0))
            val profile = File(fixture.root, "reader_memory/series/series-one.json")
            val before = profile.readBytes()
            val source = fixture.source.readBytes()
            val native = fixture.journal(task.id).readBytes()
            val output = File(task.pages.single().cleanedPath!!).readBytes()
            writer.hold.set(true)
            val pending = async(Dispatchers.Default) {
                runCatching { adapter.addGlossaryTerm(editor, 0, term(editor)) }
            }
            try {
                assertTrue("The real atomic profile bytes must be prepared and fsynced first", writer.prepared.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { withContext(Dispatchers.Default) { replace(fixture, store, authority, selected, task) } }
                writer.release.countDown()
                assertTrue("A retired/replaced Reader must reject its held sourced term before final rename", pending.await().isFailure)
                assertArrayEquals(before, profile.readBytes())
                assertTrue(SeriesMemoryStore(fixture.root).profile("series-one")!!.glossary.isEmpty())
                assertArrayEquals(source, fixture.source.readBytes())
                assertArrayEquals(output, File(task.pages.single().cleanedPath!!).readBytes())
                if (store.get(task.id)?.generation == task.generation) assertArrayEquals(native, fixture.journal(task.id).readBytes())
            } finally { writer.release.countDown(); pending.cancelAndJoin() }
        }
    }

    private class HeldProfileWriter : MemoryJournalWriter {
        val hold = AtomicBoolean()
        val prepared = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun prepare(file: File, bytes: ByteArray): PreparedMemoryJournal {
            val pending = AtomicMemoryJournalWriter.prepare(file, bytes)
            try {
                if (file.parentFile?.name == "series" && hold.get()) {
                    prepared.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Held profile publication was not released." }
                }
                return pending
            } catch (failure: Throwable) { pending.close(); throw failure }
        }
    }
}
