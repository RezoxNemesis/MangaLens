package com.mangalens.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class MediaDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val dao = DownloadDatabase.get(appContext).downloads()
    private var currentOwnership: DownloadPrivateFileOwners.Lease? = null

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val admitted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            DownloadIdMutationFences.withId(id) {
                val item = dao.get(id) ?: return@withId null
                if (item.state !in setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED)) return@withId null
                val owner = DownloadPrivateFileOwner(File(applicationContext.filesDir, "downloads"), id)
                if (DownloadPrivateFileOwners.hasOwners(owner)) return@withId null
                item to DownloadPrivateFileOwners.acquire(owner)
            }
        } ?: return Result.failure()
        val item = admitted.first
        val ownership = admitted.second
        currentOwnership = ownership
        val url = item.sourceUrl
        val title = item.title
        val mime = item.mimeType.ifBlank { guessMime(url) }

        return try {
            setForeground(createForegroundInfo(title, 0L, -1L))
            download(id, url, title, mime, item.requiresBoundMediaReceipt())
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: IOException) {
            if (ownership.isUnproven) {
                markFailed(id, "A private transfer file did not close safely. Partial files were retained.")
                Result.failure()
            } else {
            val refreshed = if (e !is FragmentCheckpointIOException && runAttemptCount < MAX_RETRIES) {
                runCatching { refreshSource(id, e.message.orEmpty(), ownership) }.getOrDefault(false)
            } else false
            if (runAttemptCount < MAX_RETRIES) {
                dao.stageIfActive(
                    id,
                    if (refreshed) "Source refreshed • retrying transfer"
                    else if (e is FragmentCheckpointIOException) "Waiting to retry saving fragment progress (${runAttemptCount + 1}/$MAX_RETRIES)"
                    else "Waiting to retry network transfer (${runAttemptCount + 1}/$MAX_RETRIES)"
                )
                Result.retry()
            } else {
                markFailed(id, e.message ?: "Network failure")
                Result.failure()
            }
            }
        } catch (e: Throwable) {
            markFailed(id, e.message ?: "Download failed")
            Result.failure()
        } finally {
            // This worker receipt only returns after actual coroutine IO/resource cleanup.
            ownership.close()
            currentOwnership = null
        }
    }

    private suspend fun download(id: String, url: String, title: String, mime: String, receiptRequired: Boolean) {
        check(!requireNotNull(currentOwnership).isUnproven) { "Private file ownership is unproven; transfer stopped." }
        if (dao.get(id)?.state !in setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED))
            throw CancellationException("Download was stopped or removed")
        val temp = File(applicationContext.filesDir, "downloads/$id.part").apply { parentFile?.mkdirs() }

        val metadata = DownloadRequestContextStore(applicationContext).readForTransfer(id, url, receiptRequired)
        dao.stageIfActive(id, "Downloading video")
        val validatorFile = File(temp.parentFile, "$id.validator")
        var lastPersistAt = 0L
        var lastPersistBytes = -PROGRESS_BYTES
        var lastNotificationAt = 0L
        val client = metadata?.let { saved ->
            val context = com.mangalens.ui.video.MediaRequestContext(saved.url, saved.sourcePageUrl, saved.headers)
            HTTP.newBuilder().addNetworkInterceptor(scopedDownloadHeaders(context) { actual -> android.webkit.CookieManager.getInstance().getCookie(actual) }).build()
        } ?: HTTP
        val videoProgress: suspend (Long, Long) -> Unit = { done, total ->
            val now = SystemClock.elapsedRealtime()
            if (done - lastPersistBytes >= PROGRESS_BYTES || now - lastPersistAt >= PROGRESS_INTERVAL_MS) {
                persistProgress(id, done, total)
                lastPersistBytes = done
                lastPersistAt = now
            }
            if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS) {
                setForeground(createForegroundInfo(title, done, total))
                lastNotificationAt = now
            }
        }
        if (metadata?.videoFragments != null) {
            OriginalFragmentTransfer({ _ -> fragmentClient(metadata.url, metadata.sourcePageUrl, metadata.headers) },
                requireNotNull(currentOwnership)::retain).download(metadata.videoFragments, temp, validatorFile, videoProgress)
        } else ResumableMediaTransfer(client, requireNotNull(currentOwnership)::retain).download(url, temp, validatorFile, mime, videoProgress)

        currentCoroutineContext().ensureActive()
        check(temp.length() > 0L) { "The downloaded file is empty." }
        val audio = File(temp.parentFile, "$id.audio.part")
        val audioValidator = File(temp.parentFile, "$id.audio.validator")
        metadata?.audioUrl?.let { audioUrl ->
            dao.stageIfActive(id, "Downloading audio")
            val audioClient = HTTP.newBuilder().addNetworkInterceptor(scopedDownloadHeaders(
                com.mangalens.ui.video.MediaRequestContext(audioUrl, metadata.sourcePageUrl, metadata.audioHeaders)
            ) { actual -> android.webkit.CookieManager.getInstance().getCookie(actual) }).build()
            val audioProgress: suspend (Long, Long) -> Unit = { done, total ->
                persistProgress(id, temp.length() + done, if (total > 0) temp.length() + total else -1)
            }
            if (metadata.audioFragments != null) {
                OriginalFragmentTransfer({ _ -> fragmentClient(audioUrl, metadata.sourcePageUrl, metadata.audioHeaders) },
                    requireNotNull(currentOwnership)::retain).download(metadata.audioFragments, audio, audioValidator, audioProgress)
            } else ResumableMediaTransfer(audioClient, requireNotNull(currentOwnership)::retain).download(audioUrl, audio, audioValidator, "audio/", audioProgress)
            currentCoroutineContext().ensureActive()
        }
        val remuxAttempt = OriginalMediaRemuxer.newAttemptBase(requireNotNull(temp.parentFile), id)
        try {
            dao.stageIfActive(id, "Verifying and preserving original source streams")
            val original = if (mime.startsWith("video/")) OriginalMediaRemuxer.assemble(
                applicationContext, temp, audio.takeIf { metadata?.audioUrl != null }, mime,
                remuxAttempt, metadata?.expectedDurationUs, metadata?.detectedHeight, metadata?.originalSelection,
                requireCombinedAudio = metadata == null
            ) else null
            currentCoroutineContext().ensureActive()
            val publication = original?.file ?: temp
            val publicationMime = original?.mime ?: mime
            val actualHeight = original?.media?.video?.height
            metadata?.requestedHeight?.takeIf { it < 9_000 }?.let { ceiling ->
                check(actualHeight == null || actualHeight <= ceiling) { "Source output is ${actualHeight}p, above the selected ${ceiling}p ceiling." }
            }
            val qualityNote = if (actualHeight != null) OriginalMediaCompletionPolicy.note(
                actualHeight, metadata?.requestedHeight, metadata?.originalSelection, original.codecPlaybackSupported,
                hasAudio = original.media.audio != null
            ) else "Completed and media checked"
            val uri = publish(publication, title, publicationMime)
            try {
                currentCoroutineContext().ensureActive()
                if (dao.completeIfActive(id, uri.toString(), publication.length(), publicationMime) == 0) {
                    throw CancellationException("Download stopped before publication")
                }
            } catch (failure: Throwable) {
                applicationContext.contentResolver.delete(uri, null, null)
                throw failure
            }
            if (dao.completedDetails(id, actualHeight, qualityNote) > 0) {
                MediaDownloadManager.notifyCommittedState(applicationContext, id)
            }
            temp.delete(); audio.delete(); audioValidator.delete()
            validatorFile.delete()
            File(temp.parentFile, "$id.validator.new").delete(); File(temp.parentFile, "$id.audio.validator.new").delete()
        } finally {
            // Known before the suspend call, so cancellation also removes a rejected result.
            val owner = DownloadPrivateFileOwner(requireNotNull(temp.parentFile), id)
            if (!requireNotNull(currentOwnership).isUnproven &&
                !DownloadPrivateFileOwners.hasOtherOwners(owner, requireNotNull(currentOwnership))) {
                OriginalMediaRemuxer.removeAttemptOutputs(remuxAttempt)
            }
        }
    }

    private fun fragmentClient(trackUrl: String, page: String?, headers: Map<String, String>) =
        HTTP.newBuilder().addNetworkInterceptor(scopedDownloadHeaders(
            com.mangalens.ui.video.MediaRequestContext(trackUrl, page, headers)
        ) { actual -> android.webkit.CookieManager.getInstance().getCookie(actual) }).build()

    private suspend fun refreshSource(id: String, failure: String, ownership: DownloadPrivateFileOwners.Lease): Boolean {
        val item = dao.get(id) ?: return false
        val store = DownloadRequestContextStore(applicationContext)
        val saved = store.readMedia(id) ?: return false
        val page = saved.sourcePageUrl ?: item.sourcePageUrl ?: return false
        if (!page.startsWith("http://") && !page.startsWith("https://")) return false

        val quality = DownloadQuality.selectable.firstOrNull {
            it.height == (saved.requestedHeight ?: item.requestedHeight)
        } ?: DownloadQuality.BEST
        dao.stageIfActive(id, "Refreshing media source after " + failure.ifBlank { "network rejection" }.take(80))
        val resolver = MediaLinkResolver(
            siteExtractor = YtDlpSiteMediaExtractor(applicationContext, allowSeparateStreams = true)
        )
        val expected = dao.get(id) ?: return false
        val refreshed = resolver.resolveCancellable(page, quality) ?: return false
        val mime = refreshed.mimeType ?: item.mimeType
        val title = refreshed.title?.takeIf(String::isNotBlank) ?: item.title
        val normalized = refreshed.copy(
            sourcePageUrl = refreshed.sourcePageUrl ?: page,
            requestedHeight = refreshed.requestedHeight ?: quality.height
        )
        return DownloadIdMutationFences.withId(id) {
            if (dao.get(id) != expected || expected.state !in setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED)) return@withId false
            val owner = DownloadPrivateFileOwner(File(applicationContext.filesDir, "downloads"), id)
            if (ownership.isUnproven || DownloadPrivateFileOwners.hasOtherOwners(owner, ownership)) return@withId false
            val updated = dao.refreshSource(
                id = id,
                url = normalized.url,
                mime = mime,
                title = title,
                provider = normalized.provider,
                pageUrl = normalized.sourcePageUrl,
                requestedHeight = normalized.requestedHeight,
                stage = "Resolved fresh " + normalized.provider + " media source"
            ) > 0
            if (!updated) return@withId false
            store.write(id, normalized)
            val root = File(applicationContext.filesDir, "downloads")
            listOf(
                "$id.part", "$id.validator", "$id.validator.new", "$id.audio.part", "$id.audio.validator", "$id.audio.validator.new", "$id.muxed.mp4"
            ).forEach { File(root, it).delete() }
            root.listFiles().orEmpty().filter { it.name.startsWith("$id.muxed-") &&
                it.extension in setOf("mp4", "webm", "mkv") }.forEach(File::delete)
            true
        }
    }

    private suspend fun persistProgress(id: String, done: Long, total: Long) {
        if (dao.progressIfActive(id, done, total) == 0) throw CancellationException("Download stopped")
    }

    private suspend fun markFailed(id: String, message: String) {
        if (dao.failIfActive(id, message) > 0) {
            MediaDownloadManager.notifyCommittedState(applicationContext, id)
        }
    }

    private fun publish(temp: File, title: String, mime: String): android.net.Uri {
        val resolver = applicationContext.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName(title, mime))
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create download destination")
            try {
                val destination = resolver.openOutputStream(uri)
                    ?: throw IOException("Unable to open download destination for writing.")
                destination.use { output ->
                    temp.inputStream().useOwnedPrivateFile(requireNotNull(currentOwnership)::retain) { input -> input.copyTo(output, BUFFER_SIZE) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }

        val dir = applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: File(applicationContext.filesDir, "downloads").apply { mkdirs() }
        val file = File(dir, safeName(title, mime))
        temp.inputStream().useOwnedPrivateFile(requireNotNull(currentOwnership)::retain) { input ->
            file.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
        }
        return androidx.core.content.FileProvider.getUriForFile(applicationContext, "${applicationContext.packageName}.downloads", file)
    }

    private fun safeName(title: String, mime: String): String {
        val clean = title.filter { it.isLetterOrDigit() || it in " _-.()" }.trim().ifBlank { "mangalens" }
        val ext = when {
            mime.contains("jpeg") -> ".jpg"
            mime.contains("png") -> ".png"
            mime.contains("webp") -> ".webp"
            mime.contains("mp4") -> ".mp4"
            mime.contains("webm") -> ".webm"
            mime.contains("mkv") || mime == "video/x-matroska" -> ".mkv"
            mime == "video/quicktime" -> ".mov"
            else -> ""
        }
        return if (ext.isNotEmpty() && clean.lowercase().endsWith(ext)) clean else clean + ext
    }

    private fun guessMime(url: String): String {
        val x = url.substringBefore('?').lowercase()
        return when {
            x.endsWith(".jpg") || x.endsWith(".jpeg") -> "image/jpeg"
            x.endsWith(".png") -> "image/png"
            x.endsWith(".webp") -> "image/webp"
            x.endsWith(".gif") -> "image/gif"
            x.endsWith(".mp4") -> "video/mp4"
            x.endsWith(".webm") -> "video/webm"
            x.endsWith(".mkv") -> "video/x-matroska"
            else -> "application/octet-stream"
        }
    }

    private fun createForegroundInfo(title: String, done: Long, total: Long): ForegroundInfo {
        val channelId = "mangalens_downloads"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "MangaLens downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val determinate = total > 0L
        val percent = if (determinate) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (determinate) "$percent%" else "Downloading…")
            .setProgress(if (determinate) 100 else 0, percent, !determinate)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    companion object {
        const val KEY_ID = "download_id"
        const val KEY_URL = "download_url"
        const val KEY_TITLE = "download_title"
        const val KEY_MIME = "download_mime"

        private const val NOTIFICATION_ID = 10001
        private const val BUFFER_SIZE = 128 * 1024
        private const val PROGRESS_BYTES = 1024L * 1024L
        private const val PROGRESS_INTERVAL_MS = 750L
        private const val NOTIFICATION_INTERVAL_MS = 1500L
        private const val MAX_RETRIES = 3

        private val HTTP: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

