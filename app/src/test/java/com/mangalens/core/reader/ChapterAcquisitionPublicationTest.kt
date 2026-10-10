package com.mangalens.core.reader

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class ChapterAcquisitionPublicationTest {
    private class Journal : ChapterLibraryJournalIo {
        override fun read(file: File): InputStream = file.inputStream()
        override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
        override fun delete(file: File) { file.delete() }
    }
    private inline fun inDirectory(body: (File) -> Unit) {
        val directory = Files.createTempDirectory("native-acquisition-").toFile()
        try { body(directory) } finally { directory.deleteRecursively() }
    }
    private fun chapter(directory: File, number: Int): SavedChapter {
        val url = "https://example.org/manga/series/chapter-$number/"
        val file = File(directory, "chapters/$number.img").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(number.toByte())) }
        return SavedChapter(ChapterLibrary.id(url), "Chapter $number", url, listOf(ChapterPage(1, "$url/page.png", file.path)))
    }
    @Test fun existingSuccessfulRecordAndOriginalCannotBeReplacedByAcquisition() = inDirectory { directory ->
        val library = ChapterLibrary(directory, Journal()); val existing = chapter(directory, 2).copy(bookmarked = true, notes = "Keep this")
        library.save(existing)
        assertTrue(runCatching { library.saveNewAcquisition(existing.copy(title = "Different acquisition")) }.isFailure)
        assertEquals(existing, library.findMetadata(existing.id))
        assertArrayEquals(byteArrayOf(2), File(existing.pages.single().localPath!!).readBytes())
    }
    @Test fun removedOrChangedPredecessorCannotAuthorizeLibraryPublication() = inDirectory { directory ->
        val library = ChapterLibrary(directory, Journal()); val selected = chapter(directory, 1); val next = chapter(directory, 2)
        library.save(selected); library.remove(selected.id)
        assertTrue(runCatching { library.saveNewAcquisition(next, selected) }.isFailure)
        assertNull(library.findMetadata(next.id))
        assertTrue(File(next.pages.single().localPath!!).isFile)
    }
    @Test fun receiptCallbackCannotBindToAReplacedManifest() = inDirectory { directory ->
        val library = ChapterLibrary(directory, Journal()); val selected = chapter(directory, 1); val next = chapter(directory, 2)
        library.save(selected); library.saveNewAcquisition(next, selected)
        var committed = false
        library.confirmAcquisition(next) { committed = true }; assertTrue(committed)
        val replacement = chapter(directory, 3).pages
        library.save(next.copy(pages = replacement)); committed = false
        assertTrue(runCatching { library.confirmAcquisition(next) { committed = true } }.isFailure)
        assertFalse(committed)
    }
}
