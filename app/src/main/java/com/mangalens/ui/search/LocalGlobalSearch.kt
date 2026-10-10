package com.mangalens.ui.search

import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.memory.*
import com.mangalens.ui.video.*
import com.mangalens.download.DownloadSearchMetadata
import com.mangalens.orez.OrezMessageSearchMetadata
import com.mangalens.ui.web.BrowserWorkspaceSnapshot
import com.mangalens.core.router.UrlEngineRouter
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class LocalSearchKind(val label: String) {
    CHAPTER("Chapters"), BOOKMARK("Bookmarks"), DOWNLOAD("Downloads"), VIDEO("Video"),
    BROWSER("Browser"), CONVERSATION("Orez conversations"), GLOSSARY("Glossary")
}

internal data class LocalSearchHit(val key: String, val kind: LocalSearchKind, val title: String,
    val snippet: String, val chapterId: String? = null, val downloadId: String? = null,
    val browserUrl: String? = null, val messageId: Long? = null,
    val recentVideoKey: String? = null, val recentVideoSource: RecentVideoSource? = null,
    val glossary: MemoryGlossaryMetadataHit? = null,
    val downloadMetadata: DownloadSearchMetadata? = null, val messageMetadata: OrezMessageSearchMetadata? = null)
internal data class LocalSearchResult(val query: String, val hits: List<LocalSearchHit>,
    val libraryIncomplete: Boolean, val conversationsIncluded: Boolean, val browserRestoring: Boolean,
    val glossaryIncomplete: Boolean = false, val recentVideosLoading: Boolean = false, val recentVideosStorageError: Boolean = false, val sqlCoverage: LocalSqlMetadataResult? = null) {
    /** Only synchronous metadata acceptance runs while exact current profile leases are held. */
    fun tryDeliver(accept: (LocalSearchResult) -> Boolean): Boolean {
        val profiles = hits.mapNotNull { it.glossary?.read }.distinctBy { it.profile.id }
        fun consume(index: Int): Boolean = if (index == profiles.size) accept(this)
            else profiles[index].tryDeliver { consume(index + 1) }
        return consume(0)
    }
}

/** Bounded metadata search. OCR results use the separate source-proof search route. */
internal object LocalGlobalSearch {
    suspend fun search(query: String, library: List<SavedChapter>, browser: BrowserWorkspaceSnapshot?,
        downloads: suspend (String, Int) -> List<DownloadSearchMetadata>,
        messages: suspend (String, String, Int) -> List<OrezMessageSearchMetadata>,
        includeConversations: Boolean, recentVideos: RecentVideoHistoryState? = null,
        glossary: MemoryGlossaryMetadataBatch? = null, sqlCandidates: LocalSqlMetadataResult? = null): LocalSearchResult {
        val accepted = LocalGlobalSearchPolicy.query(query)
        if (sqlCandidates != null) require(sqlCandidates.query == accepted && sqlCandidates.conversationsIncluded == includeConversations)
        val hits = mutableListOf<LocalSearchHit>()
        val chapters = mutableListOf<LocalSearchHit>()
        val bookmarks = mutableListOf<LocalSearchHit>()
        for (chapter in library.take(LocalGlobalSearchPolicy.LIBRARY_SCAN_LIMIT)) {
            currentCoroutineContext().ensureActive()
            val values = listOf(chapter.title.take(2_048), chapter.seriesTitle.take(2_048), chapter.notes.take(8_192))
            val match = values.firstOrNull { LocalGlobalSearchPolicy.matches(it, accepted) } ?: continue
            if (chapters.size < LocalGlobalSearchPolicy.RESULTS_PER_SOURCE) chapters += LocalSearchHit(
                "chapter:${chapter.id}", LocalSearchKind.CHAPTER, chapter.title.take(512).ifBlank { "Saved chapter" },
                LocalGlobalSearchPolicy.snippet(match, accepted), chapterId = chapter.id)
            if (chapter.bookmarked && bookmarks.size < LocalGlobalSearchPolicy.RESULTS_PER_SOURCE) bookmarks += LocalSearchHit(
                "bookmark:${chapter.id}", LocalSearchKind.BOOKMARK, chapter.title.take(512).ifBlank { "Bookmarked chapter" },
                LocalGlobalSearchPolicy.snippet(match, accepted), chapterId = chapter.id)
        }
        hits += chapters; hits += bookmarks
        val recentHits = recentVideos?.entries.orEmpty().take(RecentVideoHistoryPolicy.MAX_ENTRIES)
            .filter { LocalGlobalSearchPolicy.matches(it.title, accepted) || LocalGlobalSearchPolicy.matches(displayUrl(it.source.uri), accepted) }
            .take(LocalGlobalSearchPolicy.RESULTS_PER_SOURCE).map { entry ->
                LocalSearchHit("recent-video:${entry.key}", LocalSearchKind.VIDEO, entry.title,
                    when { entry.watched -> "Watched video"; entry.canContinue -> "Continue at ${entry.positionMs / 1000} seconds";
                        entry.source.kind == RecentVideoKind.ONLINE -> "Saved online video source"; else -> "Saved local video" },
                    recentVideoKey = entry.key, recentVideoSource = entry.source)
            }
        hits += recentHits
        if (glossary != null) {
            require(glossary.query == accepted)
            glossary.hits.take(LocalGlobalSearchPolicy.RESULTS_PER_SOURCE).forEach { match ->
                currentCoroutineContext().ensureActive()
                hits += LocalSearchHit("glossary:${match.read.profile.id}:${match.term.id}", LocalSearchKind.GLOSSARY,
                    "${match.term.source} → ${match.term.preferred}",
                    "${match.read.profile.title} · ${match.term.targetLanguage}".take(LocalGlobalSearchPolicy.SNIPPET_CHARS), glossary = match)
            }
        }
        val pattern = LocalGlobalSearchPolicy.likePattern(accepted)
        (sqlCandidates?.downloads ?: downloads(pattern, LocalGlobalSearchPolicy.RESULTS_PER_SOURCE)).forEach { row ->
            currentCoroutineContext().ensureActive()
            hits += LocalSearchHit("download:${row.id}", if (row.mimeType.startsWith("video/") || row.mimeType.contains("mpegurl") || row.mimeType.contains("dash+xml")) LocalSearchKind.VIDEO else LocalSearchKind.DOWNLOAD,
                LocalGlobalSearchPolicy.preview(row.title).take(512).ifBlank { "Saved download" }, row.state, downloadId = row.id, downloadMetadata = row)
        }
        val browserHits = mutableListOf<LocalSearchHit>()
        browser?.bookmarks?.forEach { item ->
            if (browserHits.size < LocalGlobalSearchPolicy.RESULTS_PER_SOURCE &&
                UrlEngineRouter.isSafeWebUrl(item.url) &&
                (LocalGlobalSearchPolicy.matches(item.title, accepted) || LocalGlobalSearchPolicy.matches(item.url, accepted))) {
                browserHits += LocalSearchHit("browser-bookmark:${item.id}", LocalSearchKind.BOOKMARK,
                    item.title.ifBlank { "Web bookmark" }, displayUrl(item.url), browserUrl = item.url)
            }
        }
        browser?.history?.forEach { item ->
            if (browserHits.size < LocalGlobalSearchPolicy.RESULTS_PER_SOURCE &&
                browserHits.none { it.browserUrl == item.url } && UrlEngineRouter.isSafeWebUrl(item.url) &&
                (LocalGlobalSearchPolicy.matches(item.title, accepted) || LocalGlobalSearchPolicy.matches(item.url, accepted))) {
                browserHits += LocalSearchHit("browser-history:${item.url}", LocalSearchKind.BROWSER,
                    item.title.ifBlank { "Browser history" }, displayUrl(item.url), browserUrl = item.url)
            }
        }
        hits += browserHits
        if (includeConversations) (sqlCandidates?.messages ?: messages(pattern, accepted, LocalGlobalSearchPolicy.RESULTS_PER_SOURCE)).forEach { row ->
            currentCoroutineContext().ensureActive()
            hits += LocalSearchHit("message:${row.id}", LocalSearchKind.CONVERSATION,
                if (row.role == "YOU") "Your message" else "Orez message", LocalGlobalSearchPolicy.snippet(row.snippet, accepted), messageId = row.id, messageMetadata = row)
        }
        currentCoroutineContext().ensureActive()
        return LocalSearchResult(accepted, hits.toList(), library.size > LocalGlobalSearchPolicy.LIBRARY_SCAN_LIMIT,
            includeConversations, browser == null, glossary?.incomplete == true, recentVideos?.loading != false, recentVideos?.error != null, sqlCandidates)
    }

    private fun displayUrl(value: String): String = runCatching {
        val uri = URI(value)
        URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString().take(512)
    }.getOrDefault("Saved web address")
}
