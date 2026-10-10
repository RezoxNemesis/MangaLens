package com.mangalens.widget

import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.download.DownloadState
import com.mangalens.ui.video.RecentVideoEntry
import com.mangalens.ui.video.RecentVideoKind
import com.mangalens.ui.video.RecentVideoSource
import org.junit.Assert.*
import org.junit.Test

/** Authored controls: no widget/device execution is implied. */
class MangaLensWidgetPolicyTest {
    private val id = "a".repeat(32)
    private fun chapter(position: Int = 0) = SavedChapter(id, "Chapter", "https://example.org/c",
        listOf(ChapterPage(7, "https://example.org/1"), ChapterPage(99, "https://example.org/2")), position = position)
    private fun video(name: String, played: Long, position: Long = 20_000, duration: Long = 100_000) =
        RecentVideoEntry(RecentVideoSource(RecentVideoKind.ONLINE, "https://example.org/$name"), name, position, duration, true, played)
    private fun row(state: DownloadState = DownloadState.DOWNLOADING, done: Long = 17, total: Long = 100) =
        WidgetDownloadRow("native_download", "Transfer", state, done, total)
    @Test fun currentPointerUsesActualOrdinalRatherThanPageSourceIndex() {
        val result = MangaLensWidgetPolicy.reading(WidgetReadingPointer(id, 1), chapter(1))
        assertEquals("Page 2 of 2", result.detail); assertEquals(WidgetNativeEntry.Reader(id), result.entry)
    }
    @Test fun deletedOrReplacedChapterDoesNotKeepAnOldOpenAction() {
        assertNull(MangaLensWidgetPolicy.reading(WidgetReadingPointer(id, 1), null).entry)
        assertNull(MangaLensWidgetPolicy.reading(WidgetReadingPointer(id, 1), chapter().copy(id = "b".repeat(32))).entry)
    }
    @Test fun invalidPointerAndEmptyPagesCannotOpenReader() {
        assertNull(MangaLensWidgetPolicy.reading(WidgetReadingPointer("../private", 1), chapter()).entry)
        assertNull(MangaLensWidgetPolicy.reading(WidgetReadingPointer(id, -1), chapter()).entry)
        assertNull(MangaLensWidgetPolicy.reading(WidgetReadingPointer(id, 1), chapter().copy(pages = emptyList())).entry)
    }
    @Test fun maximumStoredPositionIsClampedBeforeOrdinalAddition() {
        assertEquals("Page 2 of 2", MangaLensWidgetPolicy.reading(WidgetReadingPointer(id, 1), chapter(Int.MAX_VALUE)).detail)
    }
    @Test fun chapterTitleIsBoundedAndContainsNoControlCharacters() {
        val result = MangaLensWidgetPolicy.reading(WidgetReadingPointer(id, 1), chapter().copy(title = "\n" + "x".repeat(1000) + "\u0000"))
        assertEquals(160, result.title.length); assertFalse(result.title.any(Char::isISOControl))
    }
    @Test fun watchingSkipsCompletedAndUnstartedNewerEntries() {
        val continuation = video("continue", 1)
        val result = MangaLensWidgetPolicy.watching(listOf(video("complete", 3, 95_000), video("not-started", 2, 0), continuation))
        assertEquals(WidgetNativeEntry.RecentVideo(continuation.key), result.entry)
        assertEquals("0:20 of 1:40", result.detail); assertFalse(result.detail.contains("https://"))
    }
    @Test fun watchingUsesRecentNativeKeyAndRejectsUnboundedInventory() {
        val newer = video("newer", 2)
        assertEquals(WidgetNativeEntry.RecentVideo(newer.key), MangaLensWidgetPolicy.watching(listOf(video("old", 1), newer)).entry)
        assertThrows(IllegalArgumentException::class.java) { MangaLensWidgetPolicy.watching(List(41) { video("v$it", it.toLong()) }) }
    }
    @Test fun completedStateDoesNotInventReceivedBytesOrForce100Percent() {
        val result = MangaLensWidgetPolicy.download(row(DownloadState.COMPLETED, 17, 100))
        assertEquals(17, result.percent); assertEquals("Completed · 17 of 100 bytes", result.detail)
    }
    @Test fun unknownAndZeroTotalsKeepAnUnknownProgressBar() {
        assertNull(MangaLensWidgetPolicy.download(row(total = -1)).percent)
        assertNull(MangaLensWidgetPolicy.download(row(total = 0)).percent)
        assertTrue(MangaLensWidgetPolicy.download(row(total = -1)).detail.contains("17 bytes received"))
    }
    @Test fun incompleteLongMaximumBytesCannotRoundUpTo100() {
        assertEquals(99, MangaLensWidgetPolicy.download(row(done = Long.MAX_VALUE - 1, total = Long.MAX_VALUE)).percent)
        assertEquals(100, MangaLensWidgetPolicy.download(row(done = Long.MAX_VALUE, total = Long.MAX_VALUE)).percent)
    }
    @Test fun exactIntegerFloorDoesNotOverflowAndReportsEveryNativeState() {
        assertEquals(33, MangaLensWidgetPolicy.download(row(done = 1, total = 3)).percent)
        DownloadState.entries.forEach { state -> assertTrue(MangaLensWidgetPolicy.download(row(state)).detail.isNotBlank()) }
    }
    @Test fun invalidDownloadIdentityOrNegativeByteEvidenceIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { MangaLensWidgetPolicy.download(row().copy(id = "../../private")) }
        assertThrows(IllegalArgumentException::class.java) { MangaLensWidgetPolicy.download(row(done = -1)) }
        assertThrows(IllegalArgumentException::class.java) { MangaLensWidgetPolicy.download(row(total = -2)) }
    }
}
