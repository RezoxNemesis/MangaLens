package com.mangalens.core.reader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Real private directory/files; journal transport injected because Android AtomicFile is device-only. */
class ChapterLibraryTest {
    internal class FileJournal : ChapterLibraryJournalIo {
        var failWrites = false
        override fun read(file: File): InputStream {
            val backup = File(file.path + ".bak")
            if (backup.exists()) { backup.copyTo(file, overwrite = true); backup.delete() }
            return file.inputStream()
        }
        override fun write(file: File, bytes: ByteArray) {
            check(!failWrites) { "Injected disk failure" }
            val pending = File(file.path + ".new")
            pending.writeBytes(bytes)
            Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        override fun delete(file: File) { file.delete(); File(file.path + ".bak").delete(); File(file.path + ".new").delete() }
    }
    private fun root() = Files.createTempDirectory("mangalens-library-").toFile()
    private fun chapter(root: File, key: String = "one"): SavedChapter {
        val image = File(root, "chapters/$key.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        return SavedChapter(ChapterLibrary.id(key), "Chapter $key", "https://example.org/$key",
            listOf(ChapterPage(1, "https://example.org/page", image.path)), position = 4, scrollOffset = 137,
            updatedAt = 333L, bookmarked = true, readingStatus = ReadingStatus.ON_HOLD)
    }
    private inline fun withRoot(block: (File) -> Unit) { val root = root(); try { block(root) } finally { root.deleteRecursively() } }

    @Test fun fiveRequiredStatusesRetainLegacyNames() {
        assertEquals(setOf("READING", "PLAN_TO_READ", "COMPLETED", "ON_HOLD", "DROPPED"), ReadingStatus.entries.map { it.name }.toSet())
        assertEquals(setOf("Reading", "Plan to Read", "Completed", "On Hold", "Dropped"), ReadingStatus.entries.map { it.label }.toSet())
    }
    @Test fun savedMetadataAndDistinctHistoryRoundTripWithoutMovingPosition() = withRoot { root ->
        val expected = chapter(root).copy(seriesTitle = "One Piece", notes = "Read with Mira\nFavourite scene: 航海", collections = listOf("Weekend", "Hindi study"), addedAt = 11L, lastReadAt = 22L)
        ChapterLibrary(root, FileJournal()).save(expected)
        assertEquals(expected, ChapterLibrary(root, FileJournal()).list().single())
    }
    @Test fun legacyJournalsHaveUnknownHistoryAndEmptyUserMetadata() = withRoot { root ->
        val library = ChapterLibrary(root, FileJournal())
        val expected = chapter(root)
        library.save(expected)
        val file = File(root, "chapter_library/${expected.id}.json")
        val json = JSONObject(file.readText()).put("version", 1)
        listOf("seriesTitle", "notes", "collections", "addedAt", "lastReadAt").forEach { json.remove(it) }
        file.writeText(json.toString())
        val legacy = library.list().single()
        assertEquals(0L, legacy.addedAt)
        assertEquals(0L, legacy.lastReadAt)
        assertEquals("", legacy.seriesTitle)
        assertEquals("", legacy.notes)
        assertTrue(legacy.collections.isEmpty())
        assertEquals(expected.position, legacy.position)
        assertEquals(expected.scrollOffset, legacy.scrollOffset)
        assertEquals(expected.readingStatus, legacy.readingStatus)
        assertTrue(legacy.bookmarked)
    }
    @Test fun backupOnlyJournalIsRecoveredWithoutPromotingUncommittedOrUnknownFiles() = withRoot { root ->
        val library = ChapterLibrary(root, FileJournal())
        val expected = chapter(root)
        library.save(expected)
        val file = File(root, "chapter_library/${expected.id}.json")
        file.renameTo(File(file.path + ".bak"))
        val unknown = File(file.parent, "personal.json").apply { writeText("personal note") }
        val pending = File(file.parent, "${ChapterLibrary.id("pending")}.json.new").apply { writeText("uncommitted") }
        assertEquals(expected, library.list().single())
        assertEquals("personal note", unknown.readText())
        assertEquals("uncommitted", pending.readText())
    }
    @Test fun escapedPagePathKeepsItsPositionAndNeverExposesOutsideFile() = withRoot { root ->
        val library = ChapterLibrary(root, FileJournal())
        val expected = chapter(root)
        library.save(expected)
        val file = File(root, "chapter_library/${expected.id}.json")
        val json = JSONObject(file.readText())
        json.getJSONArray("pages").getJSONObject(0).put("file", "../private.png")
        File(root, "private.png").writeBytes(byteArrayOf(9))
        file.writeText(json.toString())
        val result = library.list().single()
        assertEquals(1, result.pages.single().index)
        assertNull(result.pages.single().localPath)
        assertNotNull(result.pages.single().error)
        assertEquals(137, result.scrollOffset)
    }
    @Test fun deletionRetainsSharedOriginalAndDoesNotDeleteUnknownFiles() = withRoot { root ->
        val library = ChapterLibrary(root, FileJournal())
        val first = chapter(root)
        val second = first.copy(id = ChapterLibrary.id("two"), title = "Second")
        val unknown = File(root, "chapter_library/keep.txt").apply { writeText("keep") }
        library.save(first); library.save(second)
        library.remove(first.id)
        assertTrue(File(first.pages.single().localPath!!).exists())
        assertEquals(listOf(second), library.list())
        library.remove(second.id)
        assertFalse(File(first.pages.single().localPath!!).exists())
        assertEquals("keep", unknown.readText())
    }
    @Test fun failedReplacementLeavesPreviouslySavedRecordAndSources() = withRoot { root ->
        val io = FileJournal(); val library = ChapterLibrary(root, io); val first = chapter(root)
        library.save(first); io.failWrites = true
        assertThrows(IllegalStateException::class.java) { library.save(first.copy(notes = "changed")) }
        assertEquals(first, library.list().single())
        assertTrue(File(first.pages.single().localPath!!).isFile)
    }
    @Test fun metadataNormalizationIsBoundedAndRejectsBeforeReplacingSavedBytes() = withRoot { root ->
        val library = ChapterLibrary(root, FileJournal()); val original = chapter(root)
        library.save(original)
        val changed = library.updateMetadata(original.id, LibraryChapterMetadata("  Series  ", "  My notes  ", listOf("Weekend", "weekend", "  ＷＥＥＫＥＮＤ  ", "Friends")), 555L)
        assertEquals("Series", changed.seriesTitle)
        assertEquals("  My notes  ", changed.notes)
        assertEquals(listOf("Weekend", "Friends"), changed.collections)
        assertEquals(original.position, changed.position)
        assertEquals(original.scrollOffset, changed.scrollOffset)
        assertEquals(original.addedAt, changed.addedAt)
        assertEquals(original.lastReadAt, changed.lastReadAt)
        val file = File(root, "chapter_library/${original.id}.json"); val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { library.updateMetadata(original.id, LibraryChapterMetadata(notes = "x".repeat(8_193))) }
        assertArrayEquals(before, file.readBytes())
        assertThrows(IllegalArgumentException::class.java) { library.updateMetadata(original.id, LibraryChapterMetadata(collections = List(33) { "$it" })) }
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun deletedChapterCannotBeRecreatedByLateMetadataSave() = withRoot { root ->
        val library = ChapterLibrary(root, FileJournal()); val first = chapter(root)
        library.save(first); library.remove(first.id)
        assertThrows(IllegalStateException::class.java) { library.updateMetadata(first.id, LibraryChapterMetadata(notes = "late")) }
        assertTrue(library.list().isEmpty())
        assertFalse(File(root, "chapter_library/${first.id}.json").exists())
    }
    @Test fun futureAndOversizedBackupJournalsRemainUntouchedAndNeverLoaded() = withRoot { root ->
        val library = ChapterLibrary(root, FileJournal()); val first = chapter(root); library.save(first)
        val file = File(root, "chapter_library/${first.id}.json")
        file.writeText(JSONObject(file.readText()).put("version", 99).toString()); val before = file.readText()
        assertTrue(library.list().isEmpty()); assertEquals(before, file.readText())
        file.delete(); val backup = File(file.path + ".bak").apply { writeBytes(ByteArray(2_000_001) { 32 }) }
        assertTrue(library.list().isEmpty())
        assertEquals(2_000_001L, file.length()) // adapter mirrors AtomicFile backup recovery; size applies after openRead
    }
    @Test fun newStatusesAndVisitHistorySurviveColdReadWithoutMetadataCountingAsReading() = withRoot { root ->
        val library = ChapterLibrary(root, FileJournal()); val first = chapter(root)
        for (status in listOf(ReadingStatus.PLAN_TO_READ, ReadingStatus.DROPPED)) {
            library.save(first.copy(readingStatus = status).visitedAt(900L).visitedAt(800L))
            val loaded = ChapterLibrary(root, FileJournal()).list().single()
            assertEquals(status, loaded.readingStatus); assertEquals(900L, loaded.lastReadAt)
            assertEquals(first.addedAt, loaded.addedAt)
            assertEquals(900L, library.updateMetadata(first.id, LibraryChapterMetadata(notes = "edited"), 2_000L).lastReadAt)
        }
    }
    @Test fun maliciousOrSymlinkPageCannotBePersistedAsAnotherManagedPage() = withRoot { root ->
        val library = ChapterLibrary(root, FileJournal()); val first = chapter(root); library.save(first)
        val outside = File(root, "private").apply { writeText("private") }
        val link = File(root,"chapters/link"); Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { library.save(first.copy(pages = listOf(ChapterPage(1,"private",link.path)))) }
        assertEquals(first, library.list().single())
        assertTrue(outside.isFile)
    }

    @Test fun promotionHintReopensOnlyWithMatchingActualOriginalAndRetainsItsFile() = withRoot { root ->
        val original = chapter(root)
        val page = original.pages.single()
        val image = File(requireNotNull(page.localPath))
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(image.readBytes()).joinToString("") { "%02x".format(it) }
        val hinted = page.copy(contentRevision = hash + ":incarnation", promotionHint = ChapterPromotionHint(
            com.mangalens.core.acquisition.ChapterImagePromotion.COMMERCIAL_LINK, hash))
        val library = ChapterLibrary(root, FileJournal())
        library.save(original.copy(pages = listOf(hinted)))
        val reopened = ChapterLibrary(root, FileJournal()).list().single().pages.single()
        assertEquals(hinted.promotionHint, reopened.promotionHint)
        assertTrue(reopened.promotionHint!!.matches(reopened))
        assertTrue(image.isFile)
        image.writeBytes(byteArrayOf(3, 2, 1)) // Same byte count must not preserve an obsolete hint.
        val changed = ChapterLibrary(root, FileJournal()).list().single().pages.single()
        assertNull(changed.promotionHint)
        assertNotNull(changed.localPath)
        assertArrayEquals(byteArrayOf(3, 2, 1), image.readBytes())
    }
    @Test fun unboundPresentationHintIsNotPersistedAsVerifiedEvidence() = withRoot { root ->
        val original = chapter(root)
        val page = original.pages.single().copy(promotionHint = ChapterPromotionHint(
            com.mangalens.core.acquisition.ChapterImagePromotion.AD_CONTAINER, "a".repeat(64)))
        val library = ChapterLibrary(root, FileJournal())
        library.save(original.copy(pages = listOf(page)))
        assertNull(ChapterLibrary(root, FileJournal()).list().single().pages.single().promotionHint)
        assertTrue(File(requireNotNull(page.localPath)).isFile)
    }
    @Test fun metadataLookupKeepsChapterFieldsWithoutVerifiedPresentationHints() = withRoot { root ->
        val original = chapter(root)
        val page = original.pages.single()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(File(requireNotNull(page.localPath)).readBytes())
            .joinToString("") { "%02x".format(it) }
        val hinted = page.copy(contentRevision = hash, promotionHint = ChapterPromotionHint(
            com.mangalens.core.acquisition.ChapterImagePromotion.AD_CONTAINER, hash))
        val library = ChapterLibrary(root, FileJournal())
        library.save(original.copy(pages = listOf(hinted)))
        val metadata = requireNotNull(library.findMetadata(original.id))
        assertEquals(original.title, metadata.title)
        assertEquals(original.lastReadAt, metadata.lastReadAt)
        assertEquals(page.localPath, metadata.pages.single().localPath)
        assertNull(metadata.pages.single().promotionHint)
        assertNull(metadata.pages.single().contentRevision)
    }
}
