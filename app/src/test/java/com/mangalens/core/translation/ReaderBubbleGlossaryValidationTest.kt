package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Prepared additive action tests; these are not compiled/run or native OCR/image claims. */
class ReaderBubbleGlossaryValidationTest {
    @Test fun onlyAnExplicitLiveLinkedSeriesCanReceiveASavedBubbleTerm() = runBlocking {
        Fixture(link = false).use { f ->
            f.adapter.memory.createSeries("Unselected series", "other-series")
            f.rejected(f.term().copy(origin = null, originSourceSha256 = null))
            assertTrue(f.adapter.memory.profile("other-series")!!.glossary.isEmpty())
        }
    }

    @Test fun nativeTargetAndCapturedSourceOriginCannotBeChangedByTheTermForm() = runBlocking {
        Fixture().use { f ->
            f.rejected(f.term().copy(targetLanguage = "en"))
            f.rejected(f.term().copy(origin = MemoryLocation("foreign-chapter", 0)))
            f.rejected(f.term().copy(origin = MemoryLocation(f.fixture.chapter.id, 1)))
            f.rejected(f.term().copy(originSourceSha256 = "0".repeat(64)))
        }
    }

    @Test fun unobservedSourcePhraseAndLatinSubstringCannotClaimTheSelectedSavedOrigin() = runBlocking {
        Fixture().use { f ->
            f.rejected(f.term().copy(source = "Invented person"))
            f.rejected(f.term().copy(source = "ell"))
        }
    }

    @Test fun nativeOcrPhraseUsesExistingNormalizedTokenBoundaries() = runBlocking {
        Fixture().use { f ->
            f.adapter.addGlossaryTerm(f.editor, 0, f.term().copy(source = "HELLO"))
            val saved = f.adapter.memory.profile("series-one")!!.glossary.single()
            assertEquals("HELLO", saved.source)
            assertEquals(f.editor.receipt.source.sourceSha256, saved.originSourceSha256)
        }
    }

    @Test fun anActualCurrentPersonalOcrPhraseCanBePromotedWithoutChangingNativeOcr() = runBlocking {
        Fixture().use { f ->
            f.adapter.correct(f.editor, 0, MemoryCorrectionEdit(correctedOcr = "Hello, friend."))
            f.adapter.addGlossaryTerm(f.editor, 1, f.term().copy(source = "friend"))
            val saved = f.adapter.memory.profile("series-one")!!.glossary.single()
            assertEquals("friend", saved.source)
            assertEquals("Hello.", f.store.get(f.task.id)!!.pages.single().lettering.single().source)
            f.assertNativeUnchanged()
        }
    }

    @Test fun aHeldOldPersonalRevisionCannotPromoteTheNewEditorsPhrase() = runBlocking {
        Fixture().use { f ->
            f.adapter.correct(f.editor, 0, MemoryCorrectionEdit(correctedOcr = "Hello, friend."))
            f.adapter.correct(f.editor, 1, MemoryCorrectionEdit(correctedOcr = "Hello, teacher."))
            f.rejected(f.term().copy(source = "friend"), expectedRevision = 1)
            f.rejected(f.term().copy(source = "friend"), expectedRevision = 2)
            f.adapter.addGlossaryTerm(f.editor, 2, f.term().copy(source = "teacher"))
            assertEquals("teacher", f.adapter.memory.profile("series-one")!!.glossary.single().source)
        }
    }

    @Test fun unlinkAndRelinkToTheSameSeriesRetiresTheCapturedSourcedTerm() = runBlocking {
        Fixture().use { f ->
            f.adapter.memory.unlinkChapter(f.fixture.chapter.id)
            f.adapter.memory.associateChapter(f.fixture.chapter.id, "series-one", 0)
            f.rejected(f.term())
            assertEquals(3L, f.adapter.memory.inspectChapter(f.fixture.chapter.id).associationRevision)
        }
    }

    @Test fun removedCorrectionHistoryCannotBeRevivedByTheOriginalTermEditorRevision() = runBlocking {
        Fixture().use { f ->
            f.adapter.correct(f.editor, 0, MemoryCorrectionEdit(correctedOcr = "Hello, friend."))
            f.adapter.removeCorrection(f.editor, 1)
            f.rejected(f.term(), expectedRevision = 0)
            f.rejected(f.term().copy(source = "friend"), expectedRevision = 2)
            f.adapter.addGlossaryTerm(f.editor, 2, f.term())
            assertEquals("Hello", f.adapter.memory.profile("series-one")!!.glossary.single().source)
        }
    }

    @Test fun removedSeriesCannotBeRecreatedByALateBubbleTerm() = runBlocking {
        Fixture().use { f ->
            f.adapter.memory.removeSeries("series-one")
            f.rejected(f.term())
            assertNull(f.adapter.memory.profile("series-one"))
        }
    }

    @Test fun duplicateSpellingDoesNotReplaceAnExistingTermWithAnotherIdentifier() = runBlocking {
        Fixture().use { f ->
            f.adapter.addGlossaryTerm(f.editor, 0, f.term())
            f.rejected(f.term().copy(id = "other-term", source = "HELLO", preferred = "अलग"))
            assertEquals("नमस्ते", f.adapter.memory.profile("series-one")!!.glossary.single().preferred)
        }
    }

    private class Fixture(link: Boolean = true) : AutoCloseable {
        val fixture = NativeMemoryPublicationAdapterTest.Fixture()
        val store = fixture.store()
        val task = fixture.completed(nativeStore = store)
        private val authority = ReaderMemoryPublicationAuthority()
        private val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
        val adapter = NativeMemoryPublicationAdapter(fixture.root, store, authority)
        private val native = fixture.journal(task.id).readBytes()
        private val source = fixture.source.readBytes()
        private val output = File(task.pages.single().cleanedPath!!).readBytes()
        val editor: MemoryBubbleEditor = runBlocking {
            if (link) {
                adapter.memory.createSeries("Explicit series", "series-one")
                adapter.memory.associateChapter(fixture.chapter.id, "series-one", 0)
            }
            requireNotNull(adapter.openEditor(selected, 0, 0))
        }
        fun term() = SeriesGlossaryTerm("source-term", "Hello", "नमस्ते", "hi",
            origin = MemoryLocation(editor.receipt.source.chapterId, editor.receipt.source.pageIndex),
            originSourceSha256 = editor.receipt.source.sourceSha256)
        suspend fun rejected(term: SeriesGlossaryTerm, expectedRevision: Int = 0) {
            val profile = File(fixture.root, "reader_memory/series/series-one.json")
            val before = profile.takeIf { it.isFile }?.readBytes()
            assertTrue(runCatching { adapter.addGlossaryTerm(editor, expectedRevision, term) }.isFailure)
            if (before != null) assertArrayEquals(before, profile.readBytes()) else assertFalse(profile.exists())
            assertNativeUnchanged()
        }
        fun assertNativeUnchanged() {
            assertArrayEquals(native, fixture.journal(task.id).readBytes())
            assertArrayEquals(source, fixture.source.readBytes())
            assertArrayEquals(output, File(task.pages.single().cleanedPath!!).readBytes())
        }
        override fun close() = fixture.close()
    }
}
