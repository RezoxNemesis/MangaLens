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
    private val resolver = MediaLinkResolver(siteExtractor = YtDlpSiteMediaExtractor(context))
    private val contexts = DownloadRequestContextStore(context)
    private val dao = DownloadDatabase.get(context).downloads()
    val downloads: Flow<List<DownloadEntity>> = dao.observe()

    suspend fun enqueue(
        url: String,
        title: String? = null,
        mimeType: String? = null,
        quality: DownloadQuality = DownloadQuality.P2160,
        sourcePageUrl: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val clean = url.trim()
        require(clean.startsWith("http://") || clean.startsWith("https://")) {
            "Only HTTP(S) media links can be downloaded."
        }
        val resolved = resolver.resolveCancellable(clean, quality)
            ?: throw IllegalArgumentException("The page did not expose an accessible media source.")
        val mediaUrl = resolved.url
        val id = UUID.randomUUID().toString()
        val finalTitle = title?.takeIf(String::isNotBlank)
            ?: resolved.title?.takeIf(String::isNotBlank)
            ?: mediaUrl.substringAfterLast('/').substringBefore('?').ifBlank { "MangaLens media" }
        val mime = mimeType ?: resolved.mimeType ?: "application/octet-stream"
        contexts.write(id, resolved.copy(
            sourcePageUrl = sourcePageUrl ?: resolved.sourcePageUrl,
            headers = resolved.headers + headers
        ))
        dao.upsert(DownloadEntity(id, mediaUrl, finalTitle, mime, state = DownloadState.QUEUED))
        start(id, mediaUrl, finalTitle, mime)
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
                val quality = DownloadQuality.selectable.firstOrNull { it.height == saved?.detectedHeight }
                    ?: DownloadQuality.P2160
                resolver.resolveCancellable(page, quality)?.let { refreshed ->
                    val mime = refreshed.mimeType ?: item.mimeType
                    if (dao.refreshFailedSource(id, refreshed.url, mime) > 0) {
                        contexts.write(id, refreshed)
                        item = item.copy(sourceUrl = refreshed.url, mimeType = mime)
                        // A partial file/validator belongs to the previous signed representation.
                        java.io.File(context.filesDir, "downloads/$id.part").delete()
                        java.io.File(context.filesDir, "downloads/$id.validator").delete()
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
        dao.stopIfActive(id, DownloadState.CANCELLED, "Cancelled by user")
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        adaptive.remove(id)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        cancel(id)
        dao.delete(id)
        contexts.remove(id)
    }

    private fun start(id: String, url: String, title: String, mime: String) {
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
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun workName(id: String) = "mangalens-download-$id"

}
