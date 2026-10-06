package com.mangalens.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.farimarwat.commons.YoutubeDLRequest
import com.farimarwat.library.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Handles provider pages which do not expose a stable direct media URL.
 *
 * yt-dlp performs the provider extraction locally on the phone and its bundled FFmpeg joins
 * split video/audio streams. This is deliberately isolated from the generic direct-file worker so
 * the existing downloader remains small and predictable for normal MP4/images/HLS/DASH.
 */
class SocialMediaDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val dao = DownloadDatabase.get(appContext).downloads()
    @Volatile private var latestProgress = 0f

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val requestedTitle = inputData.getString(KEY_TITLE) ?: "MangaLens media"
        val qualityHeight = inputData.getInt(KEY_QUALITY_HEIGHT, 2160).coerceIn(144, 4320)
        val workDir = File(applicationContext.cacheDir, "social_downloads/$id").apply { mkdirs() }

        return try {
            setForeground(foregroundInfo(requestedTitle, 0f))
            val old = dao.get(id) ?: DownloadEntity(
                id = id,
                sourceUrl = url,
                title = requestedTitle,
                mimeType = "video/mp4"
            )
            dao.upsert(old.copy(state = DownloadState.DOWNLOADING, progressPercent = 0f, error = null))

            ensureYoutubeDl()
            download(id, url, qualityHeight, requestedTitle, workDir)

            val output = findFinalMedia(workDir)
                ?: throw IOException("The provider finished without producing a playable media file.")
            val mime = mimeFor(output)
            val destination = publish(output, mime)
            dao.upsert(
                (dao.get(id) ?: old).copy(
                    title = output.nameWithoutExtension.ifBlank { requestedTitle },
                    mimeType = mime,
                    destination = destination.toString(),
                    bytesDownloaded = output.length(),
                    totalBytes = output.length(),
                    progressPercent = 100f,
                    state = DownloadState.COMPLETED,
                    error = null
                )
            )
            workDir.deleteRecursively()
            Result.success()
        } catch (cancelled: CancellationException) {
            YoutubeDL.destroyProcessById(id)
            throw cancelled
        } catch (failure: Throwable) {
            YoutubeDL.destroyProcessById(id)
            val message = friendlyError(failure)
            val retryable = runAttemptCount < 2 && isRetryable(message)
            dao.get(id)?.let { item ->
                dao.upsert(
                    item.copy(
                        state = if (retryable) DownloadState.QUEUED else DownloadState.FAILED,
                        progressPercent = if (retryable) 0f else item.progressPercent,
                        error = if (retryable) "Temporary provider/network error. Retrying…" else message
                    )
                )
            }
            if (retryable) Result.retry() else Result.failure()
        }
    }

    private suspend fun ensureYoutubeDl() {
        val result = CompletableDeferred<Throwable?>()
        val initJob = YoutubeDL.init(
            appContext = applicationContext,
            withFfmpeg = true,
            withAria2c = false,
            onSuccess = {
                if (!result.isCompleted) result.complete(null)
            },
            onError = { failure ->
                if (!result.isCompleted) result.complete(failure)
            }
        )
        initJob.invokeOnCompletion { failure ->
            if (failure != null && !result.isCompleted) result.complete(failure)
        }
        val failure = result.await()
        if (failure != null) throw failure
    }

    private suspend fun download(
        id: String,
        url: String,
        height: Int,
        title: String,
        workDir: File
    ) = coroutineScope {
        val completed = CompletableDeferred<Throwable?>()
        val outputTemplate = File(workDir, "%(title)s [%(id)s].%(ext)s").absolutePath
        val request = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("--newline")
            .addOption("--retries", 5)
            .addOption("--fragment-retries", 5)
            .addOption("--concurrent-fragments", 4)
            .addOption(
                "-f",
                "bestvideo[height<=$height][ext=mp4]+bestaudio[ext=m4a]/" +
                    "bestvideo[height<=$height]+bestaudio/" +
                    "best[height<=$height][ext=mp4]/best[height<=$height]/best"
            )
            .addOption("--merge-output-format", "mp4")
            .addOption("-o", outputTemplate)

        val downloadJob = YoutubeDL.download(
            request = request,
            pId = id,
            progressCallBack = { progress, _, _ ->
                if (progress.isFinite() && progress >= 0f) {
                    latestProgress = progress.coerceIn(0f, 100f)
                }
            },
            onEndProcess = { response ->
                if (!completed.isCompleted) {
                    completed.complete(
                        if (response.exitCode == 0) null
                        else IOException(response.err.ifBlank { "yt-dlp exited with code " + response.exitCode })
                    )
                }
            },
            onError = { failure ->
                if (!completed.isCompleted) completed.complete(failure)
            }
        )

        val progressJob = launch {
            while (isActive && !completed.isCompleted) {
                val item = dao.get(id)
                if (item != null) {
                    dao.upsert(
                        item.copy(
                            state = DownloadState.DOWNLOADING,
                            progressPercent = latestProgress,
                            error = null
                        )
                    )
                }
                setForeground(foregroundInfo(title, latestProgress))
                delay(700L)
            }
        }

        try {
            val failure = completed.await()
            if (failure != null) throw failure
            latestProgress = 100f
            dao.get(id)?.let {
                dao.upsert(it.copy(state = DownloadState.DOWNLOADING, progressPercent = 100f, error = null))
            }
        } catch (cancelled: CancellationException) {
            YoutubeDL.destroyProcessById(id)
            downloadJob.cancel()
            throw cancelled
        } finally {
            progressJob.cancelAndJoin()
        }
    }

    private fun findFinalMedia(directory: File): File? {
        val ignored = setOf("part", "ytdl", "json", "description", "vtt", "srt", "ass", "jpg", "jpeg", "png", "webp")
        return directory.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) !in ignored }
            .maxByOrNull { it.length() }
    }

    private fun publish(source: File, mime: String): android.net.Uri {
        val resolver = applicationContext.contentResolver
        val displayName = safeName(source.name)

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MangaLens")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create the download destination.")
            try {
                resolver.openOutputStream(uri, "w")!!.use { output ->
                    source.inputStream().buffered(BUFFER_SIZE).use { input ->
                        input.copyTo(output, BUFFER_SIZE)
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (failure: Throwable) {
                resolver.delete(uri, null, null)
                throw failure
            }
        }

        val directory = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: applicationContext.filesDir
        val output = File(directory, displayName)
        source.copyTo(output, overwrite = true)
        return android.net.Uri.fromFile(output)
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        else -> "video/*"
    }

    private fun safeName(value: String): String {
        val forbidden = "/\\\\:*?\\\"<>|"
        val name = value
            .map { ch -> if (ch.code < 32 || ch.code == 127 || ch in forbidden) '_' else ch }
            .joinToString("")
            .trim()
            .take(180)
        return name.ifBlank { "MangaLens-media.mp4" }
    }

    private fun friendlyError(failure: Throwable): String {
        val raw = (failure.message ?: failure::class.java.simpleName).replace(Regex("\\s+"), " ").trim()
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            "login" in lower || "cookies" in lower || "private" in lower || "authentication" in lower ->
                "This video requires a signed-in session or is private/restricted. Public YouTube and Instagram links can be downloaded directly; restricted media needs an authorised session."
            "unsupported url" in lower ->
                "This link is not supported by the social-media extractor."
            "requested format is not available" in lower ->
                "That quality is not available for this video. Choose a lower quality and retry."
            raw.isBlank() -> "Social media download failed."
            else -> raw.take(500)
        }
    }

    private fun isRetryable(message: String): Boolean {
        val lower = message.lowercase(Locale.ROOT)
        return listOf("timeout", "timed out", "network", "connection", "http error 5", "temporarily")
            .any(lower::contains)
    }

    private fun foregroundInfo(title: String, progress: Float): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MangaLens social downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val percent = progress.roundToInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (percent > 0) "$percent%" else "Preparing best available quality…")
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_ID = "social_download_id"
        const val KEY_URL = "social_download_url"
        const val KEY_TITLE = "social_download_title"
        const val KEY_QUALITY_HEIGHT = "social_download_quality_height"

        private const val CHANNEL_ID = "mangalens_social_downloads"
        private const val NOTIFICATION_ID = 10005
        private const val BUFFER_SIZE = 128 * 1024
    }
}
