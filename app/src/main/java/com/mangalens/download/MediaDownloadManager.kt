package com.mangalens.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.UUID

internal interface AdaptiveDownloadCommands {
    fun add(id: String, url: String, mime: String)
    fun pause(id: String)
    fun resume(id: String)
    fun remove(id: String)
}

class MediaDownloadManager internal constructor(private val context: Context, private val adaptive: AdaptiveDownloadCommands) {
    constructor(context: Context) : this(context, object : AdaptiveDownloadCommands {
        override fun add(id: String, url: String, mime: String) = MangaLensDownloadService.addAdaptive(context, id, android.net.Uri.parse(url), mime)
        override fun pause(id: String) = MangaLensDownloadService.pauseAdaptive(context, id)
        override fun resume(id: String) = MangaLensDownloadService.resumeAdaptive(context, id)
        override fun remove(id: String) = MangaLensDownloadService.remove(context, id)
    })
    private val resolver = MediaLinkResolver(siteExtractor = YtDlpSiteMediaExtractor(context, allowSeparateStreams = true))
    private val contexts = DownloadRequestContextStore(context)
    private val dao = DownloadDatabase.get(context).downloads()
    val downloads: Flow<List<DownloadEntity>> = dao.observe()

    suspend fun enqueue(
        url: String,
        title: String? = null,
        mimeType: String? = null,
        quality: DownloadQuality = DownloadQuality.BEST,
        sourcePageUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
        requestId: String? = null
    ): String = withContext(Dispatchers.IO) {
        require(requestId == null || requestId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid download request ID" }
        // Durable Orez retries reuse the same request rather than creating another transfer.
        if (requestId != null) dao.get(requestId)?.let { existing ->
            if (existing.state == DownloadState.QUEUED) {
                start(existing.id, existing.sourceUrl, existing.title, existing.mimeType, ExistingWorkPolicy.KEEP)
            }
            return@withContext requestId
        }
        val clean = url.trim()
        require(clean.startsWith("http://") || clean.startsWith("https://")) {
            "Only HTTP(S) media links can be downloaded."
        }
        val resolved = resolver.resolveCancellable(clean, quality)
            ?: throw IllegalArgumentException("The page did not expose an accessible media source.")
        val mediaUrl = resolved.url
        val id = requestId ?: UUID.randomUUID().toString()
        val explicitTitle = title?.takeIf(String::isNotBlank)
        val resolvedTitle = resolved.title?.takeIf(String::isNotBlank)
        val finalTitle = when {
            explicitTitle != null && resolved.provider.lowercase() in setOf("direct", "generic", "web-sniff") -> explicitTitle
            resolvedTitle != null -> resolvedTitle
            explicitTitle != null -> explicitTitle
            else -> mediaUrl.substringAfterLast('/').substringBefore('?').ifBlank { "MangaLens media" }
        }
        val mime = mimeType ?: resolved.mimeType ?: "application/octet-stream"
        contexts.write(id, resolved.copy(
            sourcePageUrl = sourcePageUrl ?: resolved.sourcePageUrl,
            headers = resolvedDownloadHeaders(clean, resolved.url, resolved.headers, headers)
        ))
        val pageUrl = sourcePageUrl ?: resolved.sourcePageUrl ?: clean
        dao.upsert(
            DownloadEntity(
                id = id,
                sourceUrl = mediaUrl,
                title = finalTitle,
                mimeType = mime,
                state = DownloadState.QUEUED,
                provider = resolved.provider,
                sourcePageUrl = pageUrl,
                requestedHeight = quality.height
            )
        )
        start(id, mediaUrl, finalTitle, mime)
        id
    }

    /**
     * Queues a concrete media request already observed by the WebView/native resolver.
     * This deliberately skips page re-extraction so signed CDN URLs keep the exact
     * Cookie/Referer/User-Agent context that made them playable in the browser.
     */
    suspend fun enqueueResolvedMedia(
        url: String,
        title: String? = null,
        mimeType: String = "video/mp4",
        quality: DownloadQuality = DownloadQuality.BEST,
        sourcePageUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
        provider: String = "web-sniff"
    ): String = withContext(Dispatchers.IO) {
        val clean = url.trim()
        require(clean.startsWith("http://") || clean.startsWith("https://")) {
            "Only HTTP(S) media links can be downloaded."
        }
        val page = sourcePageUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val safeHeaders = headers.filter { (name, value) ->
            name.lowercase() in setOf("accept", "accept-language", "cookie", "origin", "referer", "user-agent") &&
                value.length <= 16_384 &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }
        }
        val resolved = ResolvedMediaLink(
            url = clean,
            mimeType = mimeType,
            provider = provider,
            title = title?.takeIf(String::isNotBlank),
            sourcePageUrl = page,
            headers = safeHeaders,
            requestedHeight = quality.height
        )
        val id = UUID.randomUUID().toString()
        val finalTitle = title?.takeIf(String::isNotBlank)
            ?: clean.substringAfterLast('/').substringBefore('?').ifBlank { "MangaLens media" }
        contexts.write(id, resolved)
        dao.upsert(
            DownloadEntity(
                id = id,
                sourceUrl = clean,
                title = finalTitle,
                mimeType = mimeType,
                state = DownloadState.QUEUED,
                provider = provider,
                sourcePageUrl = page ?: clean,
                requestedHeight = quality.height
            )
        )
        start(id, clean, finalTitle, mimeType)
        id
    }

    suspend fun pause(id: String) = withContext(Dispatchers.IO) {
        val item = dao.get(id) ?: return@withContext
        if (dao.stopIfActive(id, DownloadState.PAUSED, null) == 0) return@withContext
        if (item.isAdaptive) {
            adaptive.pause(id)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        }
    }

    suspend fun resume(id: String) = withContext(Dispatchers.IO) {
        var item = dao.get(id) ?: return@withContext
        if (item.state != DownloadState.PAUSED && item.state != DownloadState.FAILED) return@withContext
        if (item.state == DownloadState.FAILED) {
            // Signed CDN URLs expire. Re-extract the original page rather than retrying an obsolete token.
            val saved = contexts.readMedia(id)
            val page = saved?.sourcePageUrl
            if (!page.isNullOrBlank() && page != item.sourceUrl) {
                val quality = DownloadQuality.selectable.firstOrNull { it.height == saved?.requestedHeight }
                    ?: DownloadQuality.BEST
                resolver.resolveCancellable(page, quality)?.let { refreshed ->
                    val mime = refreshed.mimeType ?: item.mimeType
                    val refreshedTitle = refreshed.title?.takeIf(String::isNotBlank) ?: item.title
                    if (dao.refreshSource(
                            id,
                            refreshed.url,
                            mime,
                            refreshedTitle,
                            refreshed.provider,
                            refreshed.sourcePageUrl ?: page,
                            refreshed.requestedHeight ?: quality.height,
                            "Refreshed expired source"
                        ) > 0) {
                        contexts.write(id, refreshed.copy(requestedHeight = refreshed.requestedHeight ?: quality.height))
                        item = item.copy(
                            sourceUrl = refreshed.url,
                            mimeType = mime,
                            title = refreshedTitle,
                            provider = refreshed.provider,
                            sourcePageUrl = refreshed.sourcePageUrl ?: page,
                            requestedHeight = refreshed.requestedHeight ?: quality.height
                        )
                        // A partial file/validator belongs to the previous signed representation.
                        java.io.File(context.filesDir, "downloads/$id.part").delete()
                        java.io.File(context.filesDir, "downloads/$id.validator").delete()
                        java.io.File(context.filesDir, "downloads/$id.audio.part").delete()
                        java.io.File(context.filesDir, "downloads/$id.audio.validator").delete()
                    }
                }
            }
        }
        if (item.isAdaptive) {
            if (dao.resumeIfStopped(id, DownloadState.DOWNLOADING) == 0) return@withContext
            if (item.state == DownloadState.FAILED) {
                // Setting a stop reason alone does not enqueue a failed Media3 download again.
                adaptive.add(id, item.sourceUrl, item.mimeType)
            } else {
                adaptive.resume(id)
            }
        } else {
            if (dao.resumeIfStopped(id, DownloadState.QUEUED) == 0) return@withContext
            start(id, item.sourceUrl, item.title, item.mimeType)
        }
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        com.mangalens.orez.agent.OrezDownloadTaskLink.cancelOwningTask(context, id)
        dao.stopIfActive(id, DownloadState.CANCELLED, "Cancelled by user")
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        adaptive.remove(id)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        cancel(id)
        dao.delete(id)
        contexts.remove(id)
    }

    private fun start(id: String, url: String, title: String, mime: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        if (isAdaptiveMediaSource(url, mime)) {
            adaptive.add(id, url, mime)
            return
        }
        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(
                workDataOf(
                    MediaDownloadWorker.KEY_ID to id,
                    MediaDownloadWorker.KEY_URL to url,
                    MediaDownloadWorker.KEY_TITLE to title,
                    MediaDownloadWorker.KEY_MIME to mime
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
            .addTag("mangalens_download")
            .addTag(id)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            policy,
            request
        )
    }

    private fun workName(id: String) = "mangalens-download-$id"

}

