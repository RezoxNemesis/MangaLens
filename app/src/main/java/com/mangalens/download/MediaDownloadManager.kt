package com.mangalens.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.farimarwat.library.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.Duration
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
        require(clean.startsWith("http://") || clean.startsWith("https://")) {
            "Only HTTP(S) media links can be downloaded."
        }

        val id = UUID.randomUUID().toString()
        if (isSocialPage(clean)) {
            val finalTitle = title?.takeIf(String::isNotBlank) ?: socialProvider(clean) + " video"
            val mime = mimeType ?: "video/mp4"
            dao.upsert(
                DownloadEntity(
                    id = id,
                    sourceUrl = clean,
                    title = finalTitle,
                    mimeType = mime,
                    progressPercent = 0f,
                    state = DownloadState.QUEUED
                )
            )
            startSocial(id, clean, finalTitle, quality)
            return@withContext id
        }

        val resolved = resolver.resolve(clean, quality)
            ?: throw IllegalArgumentException("The page did not expose an accessible media source.")
        val mediaUrl = resolved.url
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
        when {
            isSocialPage(item.sourceUrl) -> {
                YoutubeDL.destroyProcessById(id)
                WorkManager.getInstance(context).cancelUniqueWork(workName(id))
            }
            isAdaptive(item.sourceUrl) -> MangaLensDownloadService.pauseAdaptive(context, id)
            else -> WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        }
        dao.upsert(item.copy(state = DownloadState.PAUSED, error = null))
    }

    suspend fun resume(id: String) = withContext(Dispatchers.IO) {
        val item = dao.get(id) ?: return@withContext
        if (item.state != DownloadState.PAUSED && item.state != DownloadState.FAILED) return@withContext

        when {
            isSocialPage(item.sourceUrl) -> {
                dao.upsert(item.copy(state = DownloadState.QUEUED, progressPercent = 0f, error = null))
                startSocial(id, item.sourceUrl, item.title, DownloadQuality.P2160)
            }
            isAdaptive(item.sourceUrl) -> {
                MangaLensDownloadService.resumeAdaptive(context, id)
                dao.upsert(item.copy(state = DownloadState.DOWNLOADING, error = null))
            }
            else -> {
                dao.upsert(item.copy(state = DownloadState.QUEUED, error = null))
                start(id, item.sourceUrl, item.title, item.mimeType)
            }
        }
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        val item = dao.get(id)
        YoutubeDL.destroyProcessById(id)
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        MangaLensDownloadService.remove(context, id)
        if (item != null && isSocialPage(item.sourceUrl)) {
            java.io.File(context.cacheDir, "social_downloads/$id").deleteRecursively()
        }
        item?.let {
            dao.upsert(it.copy(state = DownloadState.CANCELLED, error = "Cancelled by user"))
        }
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
            .setInputData(
                workDataOf(
                    MediaDownloadWorker.KEY_ID to id,
                    MediaDownloadWorker.KEY_URL to url,
                    MediaDownloadWorker.KEY_TITLE to title,
                    MediaDownloadWorker.KEY_MIME to mime
                )
            )
            .setConstraints(downloadConstraints())
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

    private fun startSocial(
        id: String,
        url: String,
        title: String,
        quality: DownloadQuality
    ) {
        val request = OneTimeWorkRequestBuilder<SocialMediaDownloadWorker>()
            .setInputData(
                workDataOf(
                    SocialMediaDownloadWorker.KEY_ID to id,
                    SocialMediaDownloadWorker.KEY_URL to url,
                    SocialMediaDownloadWorker.KEY_TITLE to title,
                    SocialMediaDownloadWorker.KEY_QUALITY_HEIGHT to quality.height
                )
            )
            .setConstraints(downloadConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(15))
            .addTag("mangalens_social_download")
            .addTag(id)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun downloadConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

    private fun workName(id: String) = "mangalens-download-$id"

    private fun isAdaptive(url: String): Boolean {
        val x = url.substringBefore("?").lowercase()
        return x.endsWith(".m3u8") || x.endsWith(".mpd") || x.contains("/manifest/")
    }

    private fun isSocialPage(url: String): Boolean = socialProvider(url) in setOf("YouTube", "Instagram")

    private fun socialProvider(url: String): String {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host == "youtu.be" || host.endsWith(".youtube.com") || host == "youtube.com" -> "YouTube"
            host == "instagram.com" || host.endsWith(".instagram.com") -> "Instagram"
            else -> "Social"
        }
    }
}
