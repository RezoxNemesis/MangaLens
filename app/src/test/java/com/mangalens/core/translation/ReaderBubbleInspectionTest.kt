package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Prepared actual-store metadata/publication controls; no Android crop/model claim. */
class ReaderBubbleInspectionTest {
    @Test fun openingSavedTextForInspectionCreatesNoPersonalJournalOrProfile() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority()
            val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val source = f.source.readBytes(); val native = f.journal(task.id).readBytes()
            val output = File(task.pages.single().cleanedPath!!).readBytes()
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, task.pages.single().lettering.single()))
            val view = requireNotNull(adapter.tryAcceptInspection(inspection))
            assertEquals("Hello.", view.originalOcr)
            assertEquals("नमस्ते।", view.originalTranslation)
            assertNull(view.personalOcr); assertNull(view.personalTranslation)
            assertTrue(view.hasOriginalCrop)
            assertFalse(File(f.root, "reader_memory").exists())
            assertArrayEquals(source, f.source.readBytes()); assertArrayEquals(native, f.journal(task.id).readBytes())
            assertArrayEquals(output, File(task.pages.single().cleanedPath!!).readBytes())
        }
    }

    @Test fun partialSavedPageUsesItsActualOriginalBoundsRatherThanTheRendererRectangle() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(partial = true, nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val native = task.pages.single().lettering.single()
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, native))
            assertEquals(MemoryRegionBounds(4, 6, 93, 143), inspection.source?.bounds)
            assertEquals(1001, inspection.source?.imageWidth)
            assertEquals(1009, inspection.source?.imageHeight)
            assertNotEquals(MemoryRegionBounds(native.left, native.top, native.right, native.bottom), inspection.source?.bounds)
            assertNotNull(adapter.tryAcceptInspection(inspection))
        }
    }

    @Test fun staleExpectedNativeEntryCannotSelectTheNewTextAtTheSameNumericIndex() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val actual = task.pages.single().lettering.single()
            assertNull(adapter.inspectSavedBubble(selected, 0, 0, actual.copy(source = "An earlier OCR result.")))
            assertNull(adapter.inspectSavedBubble(selected, 0, 0, actual.copy(translated = "एक पुराना अनुवाद।")))
            assertNull(adapter.inspectSavedBubble(selected, 0, 1, actual))
            assertFalse(File(f.root, "reader_memory").exists())
        }
    }

    @Test fun legacyTextRemainsReadableWithoutAcquiringOriginalCropOrWriteCredentials() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(legacyGeometry = true, nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, task.pages.single().lettering.single()))
            val view = requireNotNull(adapter.tryAcceptInspection(inspection))
            assertEquals("Hello.", view.originalOcr)
            assertFalse(view.hasOriginalCrop)
            assertNull(inspection.source)
            assertFalse(File(f.root, "reader_memory").exists())
        }
    }

    @Test fun readerNavigationAfterIoProofRejectsTheCopiedDialogMetadata() = held { _, _, authority, _, _ -> authority.retire() }

    @Test fun exactReceiptReopenedAfterIoProofRetiresTheOldDialogPresentationEpoch() = held { _, _, authority, selected, _ ->
        authority.activate(selected.receipt)
    }

    @Test fun nativeReplacementAfterIoProofRejectsG1EvenWhenItsTextAndSourceAreIdentical() = held { f, store, authority, _, _ ->
        val newer = f.completed(nativeStore = store, owner = "reader:G2", forceReprocess = true)
        authority.activate(ReaderTranslationPresentation.receipt(newer))
    }

    @Test fun exactSameSeriesRelinkAfterIoProofRejectsTheOldAssociationRevision() = held(link = true) { f, _, _, _, adapter ->
        adapter.memory.unlinkChapter(f.chapter.id)
        adapter.memory.associateChapter(f.chapter.id, "series-one", 0)
    }

    @Test fun sourceReplacementAfterIoProofRejectsTheCopiedTextBeforeMainDelivery() = held { f, _, _, _, _ ->
        f.source.writeText(f.source.readText().replaceFirst("original", "replaced"))
    }

    @Test fun outputReplacementAfterIoProofRejectsTheCopiedTextBeforeMainDelivery() = held { _, store, _, selected, _ ->
        val output = File(store.get(selected.receipt.taskId)!!.pages.single().cleanedPath!!)
        output.writeText(output.readText().replaceFirst("saved", "other"))
    }

    @Test fun currentPersonalRevisionIsLabelledSeparatelyAndRetiredByAnotherPersonalWrite() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val editor = requireNotNull(adapter.openEditor(selected, 0, 0))
            adapter.correct(editor, 0, MemoryCorrectionEdit(correctedOcr = "Hello, friend.", translated = "नमस्ते, मित्र।"))
            val native = task.pages.single().lettering.single()
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, native))
            val view = requireNotNull(adapter.tryAcceptInspection(inspection))
            assertEquals("Hello.", view.originalOcr); assertEquals("नमस्ते।", view.originalTranslation)
            assertEquals("Hello, friend.", view.personalOcr); assertEquals("नमस्ते, मित्र।", view.personalTranslation)
            assertEquals(1, view.personalRevision)
            adapter.correct(editor, 1, MemoryCorrectionEdit(correctedOcr = "Hello, teacher.", translated = "नमस्ते, शिक्षक।"))
            assertNull(adapter.tryAcceptInspection(inspection))
        }
    }

    private fun held(link: Boolean = false, change: suspend (NativeMemoryPublicationAdapterTest.Fixture,
        ChapterTranslationStore, ReaderMemoryPublicationAuthority, ReaderMemoryPresentation, NativeMemoryPublicationAdapter) -> Unit) = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            if (link) { adapter.memory.createSeries("Explicit series", "series-one"); adapter.memory.associateChapter(f.chapter.id, "series-one", 0) }
            // Hold between the real IO proof and the future Main-delivery gate, rather than sleeping.
            val inspection = requireNotNull(adapter.inspectSavedBubble(selected, 0, 0, task.pages.single().lettering.single()))
            change(f, store, authority, selected, adapter)
            assertNull("An IO proof alone must not deliver stale bubble metadata", adapter.tryAcceptInspection(inspection))
        }
    }
}
