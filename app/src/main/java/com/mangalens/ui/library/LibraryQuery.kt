package com.mangalens.ui.library

import com.mangalens.core.reader.*
import java.io.File
import java.net.URI

enum class LibrarySort(val label: String) {
    ADDED_NEWEST("Recently saved"), LAST_READ("Last read"), TITLE_ASC("Title A–Z"), TITLE_DESC("Title Z–A"), PAGE_COUNT("Most pages"), PROGRESS("Reading progress")
}
enum class LibraryOfflineFilter(val label: String) { ANY("Any download state"), READY("Fully offline"), INCOMPLETE("Needs download") }
data class LibraryOptions(
    val status: ReadingStatus? = null,
    val bookmarksOnly: Boolean = false,
    val sourceKey: String? = null,
    val collection: String? = null,
    val offline: LibraryOfflineFilter = LibraryOfflineFilter.ANY,
    val sort: LibrarySort = LibrarySort.ADDED_NEWEST
)
data class LibraryOfflineFacts(val downloaded: Int, val total: Int) { val fullyOffline: Boolean get() = total > 0 && downloaded == total }
data class LibrarySource(val key: String, val label: String)
data class LibraryQueryResult(val chapters: List<SavedChapter>, val statusCounts: Map<ReadingStatus, Int>, val allStatusCount: Int)

object LibraryAvailability {
    /** Filesystem checks only: no bitmap decode and no broad storage access. Call from IO. */
    fun capture(chapters: List<SavedChapter>, managedImages: File): Map<String, LibraryOfflineFacts> = chapters.associate { chapter ->
        chapter.id to LibraryOfflineFacts(chapter.pages.count { page ->
            page.localPath?.let { path -> runCatching {
                val file = File(path)
                file.canonicalFile.parentFile == managedImages.canonicalFile && file.isFile && file.length() > 0
            }.getOrDefault(false) } == true
        }, chapter.pages.size)
    }
}

object LibraryQuery {
    fun source(chapter: SavedChapter): LibrarySource {
        val uri = runCatching { URI(chapter.sourceUrl) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(java.util.Locale.ROOT)
        return when {
            scheme in listOf("http", "https") && !uri?.host.isNullOrBlank() -> LibrarySource("host:${libraryTextKey(uri!!.host)}", libraryTextKey(uri.host))
            scheme in listOf("content", "file", "local") || (chapter.sourceUrl.isBlank() && chapter.pages.isNotEmpty() && chapter.pages.all { page ->
                runCatching { URI(page.sourceUrl).scheme?.lowercase(java.util.Locale.ROOT) in listOf("content", "file", "local") }.getOrDefault(false)
            }) -> LibrarySource("local", "Local imports")
            else -> LibrarySource("other", "Other saved sources")
        }
    }
    fun sources(chapters: List<SavedChapter>): List<LibrarySource> = chapters.map(::source).distinctBy { it.key }.sortedBy { it.label }
    fun collections(chapters: List<SavedChapter>): List<String> = chapters.flatMap { it.collections }.distinctBy(::libraryTextKey).sortedBy(::libraryTextKey)
    fun evaluate(chapters: List<SavedChapter>, query: String, options: LibraryOptions, availability: Map<String, LibraryOfflineFacts>): LibraryQueryResult {
        val base = select(chapters, query, options.copy(status = null), availability)
        return LibraryQueryResult(base.filter { options.status == null || it.readingStatus == options.status }, base.groupingBy { it.readingStatus }.eachCount(), base.size)
    }
    fun select(chapters: List<SavedChapter>, query: String, options: LibraryOptions, availability: Map<String, LibraryOfflineFacts>): List<SavedChapter> {
        val words = libraryTextKey(query).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val matching = chapters.filter { chapter ->
            (options.status == null || chapter.readingStatus == options.status) &&
                (!options.bookmarksOnly || chapter.bookmarked) &&
                (options.sourceKey == null || source(chapter).key == options.sourceKey) &&
                (options.collection == null || chapter.collections.any { libraryTextKey(it) == libraryTextKey(options.collection) }) &&
                when (options.offline) {
                    LibraryOfflineFilter.ANY -> true
                    LibraryOfflineFilter.READY -> availability[chapter.id]?.fullyOffline == true
                    LibraryOfflineFilter.INCOMPLETE -> availability[chapter.id]?.let { !it.fullyOffline } == true
                } && (words.isEmpty() || libraryTextKey(listOf(chapter.title, chapter.seriesTitle, chapter.sourceUrl, chapter.notes, chapter.readingStatus.label)
                    .plus(chapter.collections).joinToString("\n")).let { text -> words.all { text.contains(it) } })
        }
        val order: Comparator<SavedChapter> = when (options.sort) {
            LibrarySort.ADDED_NEWEST -> compareByDescending<SavedChapter> { it.addedAt }.thenByDescending { if (it.addedAt == 0L) it.updatedAt else 0L }
            LibrarySort.LAST_READ -> compareByDescending { it.lastReadAt }
            LibrarySort.TITLE_ASC -> compareBy { libraryTextKey(it.title) }
            LibrarySort.TITLE_DESC -> compareByDescending { libraryTextKey(it.title) }
            LibrarySort.PAGE_COUNT -> compareByDescending { it.pages.size }
            LibrarySort.PROGRESS -> compareByDescending { progress(it) }
        }
        return matching.sortedWith(order.thenBy { libraryTextKey(it.title) }.thenBy { it.id })
    }
    /** Reader positions are zero-based; reaching the final page is full progress. */
    fun progress(chapter: SavedChapter): Double = when {
        chapter.pages.isEmpty() -> 0.0
        chapter.pages.size == 1 -> if (chapter.lastReadAt > 0) 1.0 else 0.0
        else -> chapter.position.coerceIn(0, chapter.pages.lastIndex).toDouble() / chapter.pages.lastIndex
    }
}
