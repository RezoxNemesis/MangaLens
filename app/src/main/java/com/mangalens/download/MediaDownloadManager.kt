package com.mangalens.download

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class MediaDownloadManager(private val context: Context) {
    private val resolver = MediaLinkResolver()
    private val dao = DownloadDatabase.get(context).downloads()
    val downloads: Flow<List<DownloadEntity>> = dao.observe()

    suspend fun enqueue(
        url: String,
        title: String? = null,
        mimeType: String? = null,
        quality: DownloadQuality = DownloadQuality.P2160
    ): String = withContext(Dispatchers.IO) {
        val clean = url.trim()
        require(clean.startsWith("http://") || clean.startsWith("https://")) { "Only HTTP(S) media links can be downloaded." }
        val resolved = resolver.resolve(clean, quality)
            ?: throw IllegalArgumentException("The page did not expose an accessible media source.")
        val mediaUrl = resolved.url
        val id = UUID.randomUUID().toString()
        val finalTitle = title?.takeIf(String::isNotBlank)
            ?: resolved.title?.takeIf(String::isNotBlank)
            ?: mediaUrl.substringAfterLast('/').substringBefore('?').ifBlank { "MangaLens media" }
        val mime = mimeType ?: resolved.mimeType ?: "application/octet-stream"
        dao.upsert(DownloadEntity(id, mediaUrl, finalTitle, mime, state = DownloadState.QUEUED))
        start(id, mediaUrl, finalTitle, mime)
        id
    }

    suspend fun pause(id: String) = withContext(Dispatchers.IO) {
        val item = dao.get(id) ?: return@withContext
        if (isAdaptive(item.sourceUrl)) MangaLensDownloadService.pauseAdaptive(context, id)
        else WorkManager.getInstance(context).cancelAllWorkByTag(id)
        dao.upsert(item.copy(state = DownloadState.PAUSED, error = null))
    }

    suspend fun resume(id: String) = withContext(Dispatchers.IO) {
        val item = dao.get(id) ?: return@withContext
        if (item.state != DownloadState.PAUSED && item.state != DownloadState.FAILED) return@withContext
        if (isAdaptive(item.sourceUrl)) {
            MangaLensDownloadService.resumeAdaptive(context, id)
            dao.upsert(item.copy(state = DownloadState.DOWNLOADING, error = null))
        } else {
            start(id, item.sourceUrl, item.title, item.mimeType)
        }
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelAllWorkByTag(id)
        MangaLensDownloadService.remove(context, id)
        dao.get(id)?.let { dao.upsert(it.copy(state = DownloadState.CANCELLED, error = "Cancelled by user")) }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        cancel(id)
        dao.delete(id)
    }

    private fun start(id: String, url: String, title: String, mime: String) {
        if (isAdaptive(url)) {
            MangaLensDownloadService.addAdaptive(context, id, android.net.Uri.parse(url), mime)
            return
        }
        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(workDataOf(
                MediaDownloadWorker.KEY_ID to id,
                MediaDownloadWorker.KEY_URL to url,
                MediaDownloadWorker.KEY_TITLE to title,
                MediaDownloadWorker.KEY_MIME to mime
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(10))
            .addTag(id)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "media-download-$id",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun isAdaptive(url: String): Boolean {
        val x = url.substringBefore("?").lowercase()
        return x.endsWith(".m3u8") || x.endsWith(".mpd") || x.contains("/manifest/")
    }
}
