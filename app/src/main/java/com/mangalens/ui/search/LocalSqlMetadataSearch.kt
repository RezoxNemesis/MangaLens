package com.mangalens.ui.search

import com.mangalens.download.DownloadSearchCandidate
import com.mangalens.download.DownloadSearchMetadata
import com.mangalens.orez.OrezMessageSearchCandidate
import com.mangalens.orez.OrezMessageSearchMetadata
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class LocalSqlSearchCoverage(val checked: Int, val matching: Int, val truncated: Int,
    val omitted: Int, val remaining: Boolean, val byteLimited: Boolean)
internal data class LocalSqlMetadataResult(val query: String, val conversationsIncluded: Boolean, val downloads: List<DownloadSearchMetadata>, val messages: List<OrezMessageSearchMetadata>,
    val downloadCoverage: LocalSqlSearchCoverage, val messageCoverage: LocalSqlSearchCoverage?, val receivedBytes: Long)

/** Indexed metadata pages, not a saved source or native playback receipt. All callbacks run on IO. */
internal object LocalSqlMetadataSearch {
    private const val PAGE_ROWS = 32
    private const val DOWNLOAD_ROWS = 1_024
    private const val MESSAGE_ROWS = 200
    private const val BYTES = 8L * 1024 * 1024
    private const val DOWNLOAD_MAX_ROW_BYTES = 8_192
    private const val MESSAGE_MAX_ROW_BYTES = 66_560
    const val TITLE_CODEPOINTS = 768
    const val MESSAGE_CODEPOINTS = 16_000

    suspend fun search(query: String, includeConversations: Boolean,
        downloadPage: suspend (Long?, Int) -> List<DownloadSearchCandidate>, downloadRemaining: suspend (Long?) -> Boolean,
        messagePage: suspend (Long?, Int) -> List<OrezMessageSearchCandidate>, messageRemaining: suspend (Long?) -> Boolean): LocalSqlMetadataResult {
        val accepted = LocalGlobalSearchPolicy.query(query)
        var received = 0L
        val downloads = arrayListOf<DownloadSearchMetadata>(); val messages = arrayListOf<OrezMessageSearchMetadata>()
        val seenDownloads = hashSetOf<String>()
        var cursor: Long? = null; var checked = 0; var matching = 0; var truncated = 0; var omitted = 0; var byteLimited = false
        while (checked < DOWNLOAD_ROWS) {
            currentCoroutineContext().ensureActive()
            val limit = minOf(PAGE_ROWS, DOWNLOAD_ROWS - checked, ((BYTES - received) / DOWNLOAD_MAX_ROW_BYTES).toInt())
            if (limit == 0) { byteLimited = true; break }
            val page = downloadPage(cursor, limit)
            currentCoroutineContext().ensureActive(); require(page.size <= limit)
            if (page.isEmpty()) break
            val actual = page.sumOf { row -> utf8(row.id.orEmpty()) + utf8(row.title) + utf8(row.mimeType) + utf8(row.state) + 24L }
            require(actual <= limit.toLong() * DOWNLOAD_MAX_ROW_BYTES && actual <= BYTES - received)
            received += actual
            for (row in page) {
                currentCoroutineContext().ensureActive()
                require(cursor == null || row.scanRowId < cursor!!); cursor = row.scanRowId; checked++
                val id = row.id
                if (id.isNullOrBlank() || id.codePointCount(0, id.length) > 128 || !seenDownloads.add(id)) { omitted++; continue }
                val clipped = prefix(row.title, TITLE_CODEPOINTS)
                if (clipped.length < row.title.length) truncated++
                if (LocalGlobalSearchPolicy.matches(clipped, accepted)) {
                    matching++
                    if (downloads.size < LocalGlobalSearchPolicy.RESULTS_PER_SOURCE) downloads += DownloadSearchMetadata(id, clipped, row.mimeType, row.state, row.createdAt)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val downloadCoverage = LocalSqlSearchCoverage(checked, matching, truncated, omitted, downloadRemaining(cursor), byteLimited)
        var messageCoverage: LocalSqlSearchCoverage? = null
        if (includeConversations) {
            cursor = null; checked = 0; matching = 0; truncated = 0; byteLimited = false
            while (checked < MESSAGE_ROWS) {
                currentCoroutineContext().ensureActive()
                val limit = minOf(PAGE_ROWS, MESSAGE_ROWS - checked, ((BYTES - received) / MESSAGE_MAX_ROW_BYTES).toInt())
                if (limit == 0) { byteLimited = true; break }
                val page = messagePage(cursor, limit)
                currentCoroutineContext().ensureActive(); require(page.size <= limit)
                if (page.isEmpty()) break
                val actual = page.sumOf { utf8(it.role) + utf8(it.snippet) + 16L }
                require(actual <= limit.toLong() * MESSAGE_MAX_ROW_BYTES && actual <= BYTES - received)
                received += actual
                for (row in page) {
                    currentCoroutineContext().ensureActive()
                    require(cursor == null || row.id < cursor!!); cursor = row.id; checked++
                    val clipped = prefix(row.snippet, MESSAGE_CODEPOINTS)
                    if (clipped.length < row.snippet.length) truncated++
                    if (LocalGlobalSearchPolicy.matches(clipped, accepted)) {
                        matching++
                        if (messages.size < LocalGlobalSearchPolicy.RESULTS_PER_SOURCE) messages += OrezMessageSearchMetadata(row.id, row.role, clipped, row.createdAt)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            messageCoverage = LocalSqlSearchCoverage(checked, matching, truncated, 0, messageRemaining(cursor), byteLimited)
        }
        currentCoroutineContext().ensureActive()
        return LocalSqlMetadataResult(accepted, includeConversations, downloads.toList(), messages.toList(), downloadCoverage, messageCoverage, received)
    }

    /** Current field identity and current query, never a direct URI from a cached search hit. */
    fun currentDownload(row: DownloadSearchMetadata, expected: DownloadSearchMetadata, query: String): Boolean =
        row.id == expected.id && row.createdAt == expected.createdAt && prefix(row.title, TITLE_CODEPOINTS) == expected.title &&
            prefix(row.mimeType, 192) == expected.mimeType && LocalGlobalSearchPolicy.matches(expected.title, query)
    fun currentMessage(row: OrezMessageSearchMetadata, expected: OrezMessageSearchMetadata, query: String): Boolean =
        row.id == expected.id && row.createdAt == expected.createdAt && prefix(row.role, 64) == expected.role &&
            prefix(row.snippet, MESSAGE_CODEPOINTS) == expected.snippet && LocalGlobalSearchPolicy.matches(expected.snippet, query)
    fun prefix(value: String, codepoints: Int): String {
        var end = 0; var count = 0
        while (end < value.length && count < codepoints) { end += Character.charCount(value.codePointAt(end)); count++ }
        return if (end == value.length) value else value.substring(0, end)
    }
    private fun utf8(value: String): Long = value.toByteArray(Charsets.UTF_8).size.toLong()
}
