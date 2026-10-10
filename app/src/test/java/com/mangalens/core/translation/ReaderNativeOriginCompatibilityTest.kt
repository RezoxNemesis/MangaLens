package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual Store receipts/publication controls; uses existing JVM metadata fixture, not a raster/OCR claim. */
class ReaderNativeOriginCompatibilityTest {
    @Test fun severalSavedBubblesWithOneExactNativePageOwnerKeepTheOldSourcedTermApiUsable() = runBlocking {
        Fixture().use { f ->
            val first = requireNotNull(f.adapter.openEditor(f.selected, 0, 0))
            val second = requireNotNull(f.adapter.openEditor(f.selected, 0, 1))
            assertNotEquals(first.receipt.bubbleId, second.receipt.bubbleId)
            f.adapter.memory.upsertTerm("series-one", f.term(second))
            val saved = f.adapter.memory.profile("series-one")!!.glossary.single()
            assertEquals("Friend", saved.source)
            assertEquals(second.receipt.source.sourceSha256, saved.originSourceSha256)
            f.assertNativeUnchanged()
        }
    }

    @Test fun conflictingIndexedReaderEpochsRequireTheExplicitCurrentBubbleRatherThanAnArbitraryOwner() = runBlocking {
        Fixture().use { f ->
            requireNotNull(f.adapter.openEditor(f.selected, 0, 0))
            val current = f.authority.activate(f.selected.receipt)
            val second = requireNotNull(f.adapter.openEditor(current, 0, 1))
            val profile = File(f.fixture.root, "reader_memory/series/series-one.json")
            val before = profile.readBytes()
            assertTrue(runCatching { f.adapter.memory.upsertTerm("series-one", f.term(second)) }.isFailure)
            assertArrayEquals(before, profile.readBytes())
            f.adapter.addGlossaryTerm(second, 0, f.term(second))
            assertEquals("Friend", f.adapter.memory.profile("series-one")!!.glossary.single().source)
            f.assertNativeUnchanged()
        }
    }

    @Test fun aColdStoreWithoutReaderPublicationAuthorityCannotPromoteANativeOwnedOrigin() = runBlocking {
        Fixture().use { f ->
            val editor = requireNotNull(f.adapter.openEditor(f.selected, 0, 0))
            val profile = File(f.fixture.root, "reader_memory/series/series-one.json")
            val before = profile.readBytes()
            val cold = SeriesMemoryStore(f.fixture.root)
            assertTrue(runCatching { cold.upsertTerm("series-one", f.term(editor).copy(source = "Hello")) }.isFailure)
            assertArrayEquals(before, profile.readBytes())
            assertTrue(cold.profile("series-one")!!.glossary.isEmpty())
            f.assertNativeUnchanged()
        }
    }

    private class Fixture : AutoCloseable {
        val fixture = NativeMemoryPublicationAdapterTest.Fixture()
        val store = fixture.store()
        val authority = ReaderMemoryPublicationAuthority()
        val task: ChapterTranslationTask = run {
            val started = store.start(fixture.chapter, fixture.config, ownerRequestId = "reader:two-bubbles")
            store.markRunning(started.id, started.generation)
            val page = requireNotNull(store.beginPage(started.id, started.generation, 0))
            val output = store.createOutputFile(started.id, started.generation, 0).apply { writeText("valid surface: saved paper") }
            fun letter(source: String, translated: String, left: Int, right: Int) = SavedMangaLettering(
                source, translated, left, 0, right, 100, "sans-serif", 0, -16777216, 20f, "ALIGN_CENTER",
                left + 1, 2, right - 1, 47, originalSourceBounds = OriginalMangaGeometry.fromSampled(
                    left + 1, 2, right - 1, 47, 250, 333, 1001, 1009))
            check(store.commitPage(started.id, started.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                cleanedPath = output.path, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 250, imageHeight = 333,
                originalWidth = 1001, originalHeight = 1009,
                lettering = listOf(letter("Hello.", "नमस्ते।", 0, 100), letter("Friend.", "मित्र।", 120, 240)))))
            requireNotNull(store.finish(started.id, started.generation))
        }
        val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
        val adapter = NativeMemoryPublicationAdapter(fixture.root, store, authority)
        private val native = fixture.journal(task.id).readBytes()
        private val source = fixture.source.readBytes()
        private val output = File(task.pages.single().cleanedPath!!).readBytes()
        init { runBlocking { adapter.memory.createSeries("Explicit series", "series-one"); adapter.memory.associateChapter(fixture.chapter.id, "series-one", 0) } }
        fun term(editor: MemoryBubbleEditor) = SeriesGlossaryTerm("source-term", "Friend", "मित्र", "hi",
            origin = MemoryLocation(editor.receipt.source.chapterId, editor.receipt.source.pageIndex),
            originSourceSha256 = editor.receipt.source.sourceSha256)
        fun assertNativeUnchanged() {
            assertArrayEquals(native, fixture.journal(task.id).readBytes())
            assertArrayEquals(source, fixture.source.readBytes())
            assertArrayEquals(output, File(task.pages.single().cleanedPath!!).readBytes())
        }
        override fun close() = fixture.close()
    }
}
