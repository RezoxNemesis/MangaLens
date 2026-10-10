package com.mangalens.widget

import com.mangalens.core.reader.SavedChapter
import com.mangalens.download.DownloadState
import com.mangalens.ui.video.RecentVideoEntry
import java.math.BigInteger
import org.json.JSONObject

internal enum class ContinuationWidgetKind { READING, WATCHING, DOWNLOAD }
internal data class ContinuationWidgetSnapshot(val title: String, val detail: String, val entry: WidgetNativeEntry? = null, val percent: Int? = null)
internal sealed interface WidgetNativeEntry {
    data class Reader(val id: String) : WidgetNativeEntry
    data class RecentVideo(val key: String) : WidgetNativeEntry
    data class Download(val id: String) : WidgetNativeEntry
}
internal data class WidgetReadingPointer(val chapterId: String, val visitedAt: Long)
internal data class WidgetDownloadRow(val id: String, val title: String, val state: DownloadState, val done: Long, val total: Long)

internal object MangaLensWidgetPolicy {
    private val chapterId = Regex("[a-f0-9]{32}")
    private val videoKey = Regex("[a-f0-9]{64}")
    private val downloadId = Regex("[A-Za-z0-9_-]{1,100}")
    fun reading(pointer: WidgetReadingPointer?, chapter: SavedChapter?): ContinuationWidgetSnapshot {
        if (pointer == null || !chapterId.matches(pointer.chapterId) || pointer.visitedAt < 0 || chapter?.id != pointer.chapterId || chapter.pages.isEmpty())
            return ContinuationWidgetSnapshot("Continue Reading", "Open a saved chapter to choose your reading shortcut.")
        val page = chapter.position.coerceIn(0, chapter.pages.lastIndex) + 1
        return ContinuationWidgetSnapshot(label(chapter.title, "Saved chapter"), "Page $page of ${chapter.pages.size}", WidgetNativeEntry.Reader(chapter.id))
    }
    fun watching(entries: List<RecentVideoEntry>): ContinuationWidgetSnapshot {
        require(entries.size <= 40)
        val entry = entries.sortedWith(compareByDescending<RecentVideoEntry> { it.playedAt }.thenBy { it.key }).firstOrNull { it.canContinue }
            ?: return ContinuationWidgetSnapshot("Continue Watching", "Watch a video to save a continuation point.")
        entry.validate(); require(videoKey.matches(entry.key))
        return ContinuationWidgetSnapshot(label(entry.title, "Saved video"), "${time(entry.resumePositionMs)} of ${time(entry.durationMs)}", WidgetNativeEntry.RecentVideo(entry.key))
    }
    fun download(row: WidgetDownloadRow?): ContinuationWidgetSnapshot {
        if (row == null) return ContinuationWidgetSnapshot("Download Progress", "No saved transfer. Open Downloads to start one.")
        require(downloadId.matches(row.id) && row.done >= 0 && row.total >= -1)
        val status = when (row.state) {
            DownloadState.QUEUED -> "Queued"; DownloadState.DOWNLOADING -> "Downloading"; DownloadState.PAUSED -> "Paused"
            DownloadState.COMPLETED -> "Completed"; DownloadState.FAILED -> "Failed"; DownloadState.CANCELLED -> "Cancelled"
        }
        val percent = if (row.total > 0) BigInteger.valueOf(row.done).multiply(BigInteger.valueOf(100))
            .divide(BigInteger.valueOf(row.total)).min(BigInteger.valueOf(100)).toInt() else null
        val bytes = if (row.total > 0) "${row.done} of ${row.total} bytes" else "${row.done} bytes received · total unknown"
        return ContinuationWidgetSnapshot(label(row.title, "Saved transfer"), "$status · $bytes", WidgetNativeEntry.Download(row.id), percent)
    }
    fun unavailable(kind: ContinuationWidgetKind) = ContinuationWidgetSnapshot(when (kind) {
        ContinuationWidgetKind.READING -> "Continue Reading"; ContinuationWidgetKind.WATCHING -> "Continue Watching"; ContinuationWidgetKind.DOWNLOAD -> "Download Progress"
    }, "Saved state could not be read safely. Open the app or retry Refresh.")
    private fun label(value: String, fallback: String) = value.filterNot(Char::isISOControl).trim().take(160).ifBlank { fallback }
    private fun time(ms: Long): String { val seconds = ms.coerceAtLeast(0) / 1000; return "${seconds / 60}:" + (seconds % 60).toString().padStart(2, '0') }
}
internal object WidgetReadingPointerCodec {
    const val MAX_BYTES = 4_096
    fun encode(pointer: WidgetReadingPointer): ByteArray {
        require(pointer.chapterId.matches(Regex("[a-f0-9]{32}")) && pointer.visitedAt >= 0)
        return JSONObject().put("version", 1).put("chapter", pointer.chapterId).put("visitedAt", pointer.visitedAt).toString().toByteArray(Charsets.UTF_8)
    }
    fun decode(bytes: ByteArray): WidgetReadingPointer {
        require(bytes.size in 1..MAX_BYTES)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.keys().asSequence().toSet() == setOf("version", "chapter", "visitedAt") && json.getInt("version") == 1)
        return WidgetReadingPointer(json.getString("chapter"), json.getLong("visitedAt")).also { encode(it) }
    }
}
