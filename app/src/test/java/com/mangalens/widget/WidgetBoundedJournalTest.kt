package com.mangalens.widget

import com.mangalens.core.reader.ChapterCbzResources
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.ChapterLibraryJournalIo
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.ui.downloads.OwnedSavedVideoProbe
import com.mangalens.ui.downloads.SavedVideoProbeBusyException
import com.mangalens.ui.downloads.SavedVideoProbeCleanupException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Real files/stream closes; Android AtomicFile and widget host acceptance remain separate. */
class WidgetBoundedJournalTest {
    private inline fun fixture(body: (File) -> Unit) {
        val root = Files.createTempDirectory("widget-owned-journal-").toFile()
        try { body(root) } finally { root.deleteRecursively() }
    }
    private fun held(file: File, closes: AtomicInteger, failClose: Boolean = false): InputStream = object : FilterInputStream(file.inputStream()) {
        override fun close() { closes.incrementAndGet(); super.close(); if (failClose) throw IOException("injected unproven close") }
    }
    private fun resources() = ChapterCbzResources().also { it.beginPrivateWork() }
    private fun finish(resources: ChapterCbzResources) { resources.finishPrivateWork(); resources.close() }
    @Test fun ExactBoundReadsRealFileAndClosesBeforeReturningBytes() = fixture { root ->
        val bytes = ByteArray(8192) { (it % 251).toByte() }; val file = File(root, "journal").apply { writeBytes(bytes) }
        val closes = AtomicInteger(); val resources = resources()
        assertArrayEquals(bytes, widgetReadBounded(held(file, closes), bytes.size, resources) {})
        assertEquals(1, closes.get()); assertTrue(resources.privateReleaseProven()); finish(resources); assertEquals(1, closes.get())
    }
    @Test fun LimitPlusOneIsRejectedAndActualStreamStillClosesOnce() = fixture { root ->
        val file = File(root, "journal").apply { writeBytes(ByteArray(8193)) }; val closes = AtomicInteger(); val resources = resources()
        assertThrows(IOException::class.java) { widgetReadBounded(held(file, closes), 8192, resources) {} }
        assertTrue(resources.privateReleaseProven()); finish(resources); assertEquals(1, closes.get())
    }
    @Test fun RetiredReadDoesNotPublishAndStillProvesActualClose() = fixture { root ->
        val file = File(root, "journal").apply { writeBytes(ByteArray(9000)) }; val closes = AtomicInteger(); val resources = resources(); var checks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            widgetReadBounded(held(file, closes), 9000, resources) { if (++checks == 2) throw kotlinx.coroutines.CancellationException("retired") }
        }
        assertEquals(1, closes.get()); assertTrue(resources.privateReleaseProven()); finish(resources)
    }
    @Test fun InvalidBoundCannotLeakAnAlreadyOpenedStream() = fixture { root ->
        val file = File(root, "journal").apply { writeText("owned") }; val closes = AtomicInteger(); val resources = resources()
        assertThrows(IllegalArgumentException::class.java) { widgetReadBounded(held(file, closes), 0, resources) {} }
        assertEquals(1, closes.get()); assertTrue(resources.privateReleaseProven()); finish(resources)
    }
    @Test fun NoProgressStreamIsBoundedAndClosesRatherThanSpinningForever() {
        val closes = AtomicInteger(); val input = object : InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
            override fun close() { closes.incrementAndGet() }
        }
        val resources = resources(); assertThrows(IOException::class.java) { widgetReadBounded(input, 20, resources) {} }
        assertEquals(1, closes.get()); assertTrue(resources.privateReleaseProven()); finish(resources)
    }
    @Test fun FailedActualCloseRetainsCompositeFailureAndIsNeverRetried() = fixture { root ->
        val file = File(root, "journal").apply { writeText("owned") }; val closes = AtomicInteger(); val resources = resources()
        assertThrows(IOException::class.java) { widgetReadBounded(held(file, closes, true), 20, resources) {} }
        assertFalse(resources.privateReleaseProven()); resources.finishPrivateWork()
        assertThrows(IOException::class.java) { resources.close() }; assertEquals(1, closes.get())
    }
    @Test fun LibraryMetadataClosesManifestWithoutOpeningOriginalImageBytes() = fixture { root ->
        val original = File(root, "chapters/original.png").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100)) }
        val chapter = SavedChapter(ChapterLibrary.id("widget"), "Title", "https://example.org/c", listOf(ChapterPage(1, "https://example.org/p", original.path)))
        ChapterLibrary(root, object : ChapterLibraryJournalIo {
            override fun read(file: File) = file.inputStream()
            override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
            override fun delete(file: File) { file.delete() }
        }).save(chapter)
        val resources = resources(); val closes = AtomicInteger(); val opened = mutableListOf<File>()
        val io = WidgetContinuationFiles.WidgetChapterJournalIo(resources, {}, { file ->
            opened += file; assertEquals("chapter_library", file.parentFile!!.name); held(file, closes)
        })
        assertEquals(chapter.id, ChapterLibrary(root, io).findMetadata(chapter.id)?.id)
        assertEquals(1, opened.size); assertEquals(1, closes.get()); assertTrue(resources.privateReleaseProven()); finish(resources)
    }
    @Test fun LibrarySwallowingCloseFailureCannotBeMistakenForSafeWidgetEvidence() = fixture { root ->
        val source = File(root, "chapters/page.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val chapter = SavedChapter(ChapterLibrary.id("failed-close"), "Title", "https://example.org/c", listOf(ChapterPage(1, "https://example.org/p", source.path)))
        ChapterLibrary(root, object : ChapterLibraryJournalIo {
            override fun read(file: File) = file.inputStream()
            override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
            override fun delete(file: File) { file.delete() }
        }).save(chapter)
        val resources = resources(); val closes = AtomicInteger()
        val io = WidgetContinuationFiles.WidgetChapterJournalIo(resources, {}, { held(it, closes, true) })
        assertNull(ChapterLibrary(root, io).findMetadata(chapter.id))
        assertFalse(resources.privateReleaseProven()); resources.finishPrivateWork()
        assertThrows(IOException::class.java) { resources.close() }; assertEquals(1, closes.get())
    }
    @Test fun CompositeFailedStreamCloseRetainsTheApplicationProbeAdmission() = runBlocking {
        val root = Files.createTempDirectory("widget-probe-close-").toFile(); val file = File(root, "journal").apply { writeText("owned") }
        val closes = AtomicInteger(); val probe = OwnedSavedVideoProbe()
        try {
            try {
                probe.run { owner ->
                    val resources = resources(); owner.own(resources)
                    try { widgetReadBounded(held(file, closes, true), 20, resources, owner::checkActive) }
                    finally { resources.finishPrivateWork() }
                }
                fail("Unproven stream close published a widget result")
            } catch (_: SavedVideoProbeCleanupException) { }
            try { probe.run { "second producer" }; fail("Unproven close released capacity") }
            catch (_: SavedVideoProbeBusyException) { }
            assertEquals(1, closes.get())
        } finally { root.deleteRecursively() }
    }
}
