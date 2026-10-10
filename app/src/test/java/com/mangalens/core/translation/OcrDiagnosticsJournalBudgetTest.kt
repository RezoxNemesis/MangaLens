package com.mangalens.core.translation

import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. Real private journal-space accounting; only surface decoding is replaced, never OCR quality evidence. */
class OcrDiagnosticsJournalBudgetTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("diagnostics-journal-space").toFile()
        val sources = File(root, "chapters").apply { mkdirs() }; val journals = File(root, "translations").apply { mkdirs() }
        val source = File(sources, "original.img").apply { writeText("original bytes") }
        val io = object : ChapterJournalIo { override fun read(file: File) = file.readBytes(); override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) } }
        val store = ChapterTranslationStore(journals, sources, io) { it.readText().startsWith("valid surface:") }
        val task = store.start(SavedChapter("a".repeat(32), "Bounded diagnostics", "content://original",
            listOf(ChapterPage(37, "content://original", source.path))), ChapterReconstructionInputPolicy.newGeneration(ChapterTranslationConfig("hi")))
        init { store.markRunning(task.id, task.generation) }
        fun result(diagnostics: Boolean): ChapterTranslationPage {
            val page = store.beginPage(task.id, task.generation, 37)!!
            val output = store.createOutputFile(task.id, task.generation, 37).apply { writeText("valid surface: original-backed output fixture") }
            val letter = SavedMangaLettering("Hello.", "नमस्ते।", 5, 10, 90, 60, "sans-serif", 0, -16777216, 24f, "ALIGN_CENTER", 10, 15, 80, 45)
            val detail = if (!diagnostics) null else SavedPageOcrDiagnostics(page.sourceSha256!!, 100, 200, 2, 64,
                (0 until 64).map { ordinal -> SavedOcrFinding(ordinal, SavedOcrBox(10, 15, 80, 45), "LATIN", .8f,
                    List(8) { SavedOcrBox(10, 15, 80, 45) }, if (ordinal == 0) SavedOcrOutcome.TRANSLATED else SavedOcrOutcome.QUALITY_REJECTED,
                    nativeLetteringIndex = if (ordinal == 0) 0 else null) })
            return page.copy(status = ChapterTranslationPageStatus.COMPLETED, cleanedPath = output.path,
                cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 100, imageHeight = 200, lettering = listOf(letter), ocrDiagnostics = detail)
        }
        val journal get() = File(journals, task.id + ".json")
        fun leaveHistorySpace(bytes: Long) {
            // Existing corrupt history still occupies this private budget; no user file is read/deleted.
            RandomAccessFile(File(journals, "b".repeat(32) + ".json"), "rw").use { it.setLength(32_000_000L - bytes) }
        }
        override fun close() { root.deleteRecursively() }
    }
    @Test fun optionalDetailIsDroppedBeforeItCanDisplaceSuccessfulNativeLettering() {
        val nativeBytes = Fixture().use { f -> assertTrue(f.store.commitPage(f.task.id, f.task.generation, f.result(false))); f.journal.length() }
        Fixture().use { f -> val result = f.result(true); val original = f.source.readBytes(); f.leaveHistorySpace(nativeBytes + 200)
            assertTrue(f.store.commitPage(f.task.id, f.task.generation, result))
            val actual = f.store.get(f.task.id)!!.pages.single()
            assertEquals(result.lettering, actual.lettering); assertEquals(result.cleanedSha256, actual.cleanedSha256); assertNull(actual.ocrDiagnostics)
            assertEquals(actual, f.store.states.value.single().pages.single()); assertTrue(f.journal.length() <= nativeBytes + 200)
            assertArrayEquals(original, f.source.readBytes()) }
    }
    @Test fun nativeMetadataWhichGenuinelyCannotFitStillRejectsWithoutChangingItsJournal() {
        Fixture().use { f -> val result = f.result(true); val before = f.journal.readBytes(); f.leaveHistorySpace(1)
            try { f.store.commitPage(f.task.id, f.task.generation, result); fail("An oversized native checkpoint entered its history") } catch (_: IllegalArgumentException) { }
            assertArrayEquals(before, f.journal.readBytes()); assertEquals(ChapterTranslationPageStatus.RUNNING, f.store.get(f.task.id)!!.pages.single().status) }
    }
    @Test fun ordinaryNativeSpaceRetainsOptionalDiagnosticDataExactly() {
        Fixture().use { f -> val result = f.result(true); assertTrue(f.store.commitPage(f.task.id, f.task.generation, result)); assertEquals(result, f.store.get(f.task.id)!!.pages.single()) }
    }
}
