package com.mangalens.ui.library

import com.mangalens.core.reader.*
import com.mangalens.core.model.ContentType
import com.mangalens.ui.MangaLensUiState
import com.mangalens.ui.capturedVideoSelection
import com.mangalens.ui.withLibraryChapter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibraryQueryTest {
    private fun chapter(name: String, added: Long = 10, read: Long = 0, bookmark: Boolean = false) = SavedChapter(
        ChapterLibrary.id(name), name, "https://manga.example.org/read/$name", listOf(ChapterPage(1, "one"), ChapterPage(2, "two")),
        bookmarked = bookmark, addedAt = added, lastReadAt = read)
    private val a = chapter("Zulu", added = 10, read = 90, bookmark = true).copy(seriesTitle = "École de manga", notes = "Hindi practice: संवाद", collections = listOf("Weekend", "Friends"))
    private val b = chapter("Alpha", added = 20, read = 60).copy(readingStatus = ReadingStatus.PLAN_TO_READ, sourceUrl = "content://documents/qa", collections = listOf("Weekend"))
    private val facts = mapOf(a.id to LibraryOfflineFacts(2, 2), b.id to LibraryOfflineFacts(1, 2))
    @Test fun searchesRealSeriesNotesCollectionsAndSourceWithAllWords() {
        assertEquals(listOf(a), LibraryQuery.select(listOf(a,b), "ÉCOLE संवाद weekend", LibraryOptions(), facts))
        assertEquals(listOf(b), LibraryQuery.select(listOf(a,b), "documents", LibraryOptions(), facts))
        assertTrue(LibraryQuery.select(listOf(a,b), "École nonexistent", LibraryOptions(), facts).isEmpty())
    }
    @Test fun compatibilityWidthAndCaseDoNotDependOnDeviceLocale() {
        assertEquals(listOf(a), LibraryQuery.select(listOf(a,b), "ＺＵＬＵ", LibraryOptions(), facts))
        val before = java.util.Locale.getDefault()
        try { java.util.Locale.setDefault(java.util.Locale("tr")); assertEquals(listOf(a), LibraryQuery.select(listOf(a,b), "HINDI", LibraryOptions(), facts)) }
        finally { java.util.Locale.setDefault(before) }
    }
    @Test fun combinesBookmarksStatusSourceCollectionAndActualOfflineState() {
        val options = LibraryOptions(status = ReadingStatus.READING, bookmarksOnly = true, sourceKey = "host:manga.example.org", collection = "weekend", offline = LibraryOfflineFilter.READY)
        assertEquals(listOf(a), LibraryQuery.select(listOf(a,b), "", options, facts))
        assertTrue(LibraryQuery.select(listOf(a,b), "", options.copy(offline = LibraryOfflineFilter.INCOMPLETE), facts).isEmpty())
        assertTrue(LibraryQuery.select(listOf(a,b), "", options.copy(sourceKey = "local"), facts).isEmpty())
        assertTrue(LibraryQuery.select(listOf(a,b), "", options.copy(collection = "not saved"), facts).isEmpty())
    }
    @Test fun unknownDownloadFactsNeverPretendFullyOfflineOrIncomplete() {
        assertTrue(LibraryQuery.select(listOf(a,b), "", LibraryOptions(offline = LibraryOfflineFilter.READY), emptyMap()).isEmpty())
        assertTrue(LibraryQuery.select(listOf(a,b), "", LibraryOptions(offline = LibraryOfflineFilter.INCOMPLETE), emptyMap()).isEmpty())
        assertEquals(2, LibraryQuery.select(listOf(a,b), "", LibraryOptions(), emptyMap()).size)
    }
    @Test fun savedDateAndLastReadSortRemainDistinctFromMetadataUpdateTime() {
        val updated = a.copy(updatedAt = 1_000)
        assertEquals(listOf(b,updated), LibraryQuery.select(listOf(updated,b), "", LibraryOptions(), facts))
        assertEquals(listOf(updated,b), LibraryQuery.select(listOf(updated,b), "", LibraryOptions(sort = LibrarySort.LAST_READ), facts))
        val legacy = b.copy(addedAt = 0, lastReadAt = 0, updatedAt = Long.MAX_VALUE)
        assertEquals(listOf(a,legacy), LibraryQuery.select(listOf(legacy,a), "", LibraryOptions(), facts))
    }
    @Test fun titlePagesAndProgressSortAreDeterministicAcrossInputOrders() {
        assertEquals(listOf(b,a), LibraryQuery.select(listOf(a,b), "", LibraryOptions(sort = LibrarySort.TITLE_ASC), facts))
        assertEquals(listOf(a,b), LibraryQuery.select(listOf(b,a), "", LibraryOptions(sort = LibrarySort.TITLE_DESC), facts))
        val longer = a.copy(pages = a.pages + ChapterPage(3,"three"), position = 1)
        val advanced = b.copy(position = 1)
        assertEquals(listOf(longer,advanced), LibraryQuery.select(listOf(advanced,longer), "", LibraryOptions(sort = LibrarySort.PAGE_COUNT), facts))
        assertEquals(listOf(advanced,longer), LibraryQuery.select(listOf(longer,advanced), "", LibraryOptions(sort = LibrarySort.PROGRESS), facts))
        val ties = a.copy(id = ChapterLibrary.id("tie"))
        assertEquals(LibraryQuery.select(listOf(a,ties), "", LibraryOptions(), facts), LibraryQuery.select(listOf(ties,a), "", LibraryOptions(), facts))
    }
    @Test fun availabilityChangesWhenPrivatePageIsDeletedAndRejectsOutsideOrSymlinkFiles() {
        val root = Files.createTempDirectory("library-availability-").toFile()
        try {
            val managed = File(root, "chapters").apply { mkdirs() }
            val good = File(managed,"good").apply { writeText("actual bytes") }
            val empty = File(managed,"empty").apply { writeBytes(byteArrayOf()) }
            val outside = File(root,"private").apply { writeText("private bytes") }
            val link = File(managed,"link"); Files.createSymbolicLink(link.toPath(),outside.toPath())
            val row = a.copy(pages = listOf(good,empty,outside,link).mapIndexed { i,f -> ChapterPage(i+1,"$i",f.path) })
            assertEquals(LibraryOfflineFacts(1,4), LibraryAvailability.capture(listOf(row),managed)[row.id])
            good.delete()
            assertEquals(LibraryOfflineFacts(0,4), LibraryAvailability.capture(listOf(row),managed)[row.id])
        } finally { root.deleteRecursively() }
    }
    @Test fun sourceAndCollectionFacetsUseOnlySavedFacts() {
        assertEquals(listOf(LibrarySource("local","Local imports"),LibrarySource("host:manga.example.org","manga.example.org")), LibraryQuery.sources(listOf(a,b)))
        assertEquals(listOf("Friends","Weekend"),LibraryQuery.collections(listOf(a,b,a.copy(collections=listOf("weekend")))))
    }
    @Test fun actualLocalImportShapeUsesItsOriginalPageUrisWhenChapterSourceIsEmpty() {
        val imported = b.copy(sourceUrl = "", pages = listOf(ChapterPage(1,"file:///private/imported/page.png"),ChapterPage(2,"content://documents/second")))
        assertEquals(LibrarySource("local","Local imports"),LibraryQuery.source(imported))
        assertEquals(listOf(imported),LibraryQuery.select(listOf(a,imported),"",LibraryOptions(sourceKey="local"),facts))
        assertEquals(LibrarySource("other","Other saved sources"),LibraryQuery.source(imported.copy(pages=listOf(ChapterPage(1,"https://cdn.example.org/page")))))
    }

    @Test fun openingLibraryChapterClearsTheActualVideoPairBeforeAnyLaterModeChange() {
        val paired = MangaLensUiState(
            library = listOf(a, b), mode = ContentType.VIDEO_STREAM,
            videoUrl = "https://video.example.org/picture.mp4", videoPageUrl = "https://video.example.org/watch",
            videoHeaders = mapOf("Referer" to "https://video.example.org/watch"),
            videoAudioUrl = "https://audio.example.org/speech.m4a", videoAudioHeaders = mapOf("Authorization" to "accepted-fixture"),
            videoResolutionId = "0123456789abcdef0123456789abcdef", videoAudioResolutionId = "0123456789abcdef0123456789abcdef",
            videoExpectedDurationUs = 11_000_000
        )
        assertNotNull(paired.capturedVideoSelection())
        val opened = paired.withLibraryChapter(a.visitedAt(120))
        assertNull(opened.videoUrl)
        assertNull(opened.videoPageUrl)
        assertTrue(opened.videoHeaders.isEmpty())
        assertNull(opened.videoAudioUrl)
        assertTrue(opened.videoAudioHeaders.isEmpty())
        assertNull(opened.videoResolutionId)
        assertNull(opened.videoAudioResolutionId)
        assertNull(opened.videoExpectedDurationUs)
        assertNull(opened.copy(mode = ContentType.VIDEO_STREAM).capturedVideoSelection())
    }

    @Test fun openingLibraryChapterPublishesItsOwnPagesAndRetainsOtherSavedHistory() {
        val prior = MangaLensUiState(library = listOf(a, b), activeChapter = b, pages = b.pages.reversed(),
            loading = true, targetLanguage = "hi-latn", translationStyle = "formal", customTranslationStyle = "Keep the speaker respectful")
        val visited = a.visitedAt(140)
        val opened = prior.withLibraryChapter(visited)
        assertEquals(visited, opened.activeChapter)
        assertEquals(a.pages, opened.pages)
        assertEquals(a.sourceUrl, opened.url)
        assertEquals(ContentType.IMAGE_CHAPTER, opened.mode)
        assertFalse(opened.loading)
        assertEquals(listOf(a.copy(lastReadAt = 140), b), opened.library)
        assertEquals("hi-latn", opened.targetLanguage)
        assertEquals("formal", opened.translationStyle)
        assertEquals(prior.customTranslationStyle, opened.customTranslationStyle)
    }

}
