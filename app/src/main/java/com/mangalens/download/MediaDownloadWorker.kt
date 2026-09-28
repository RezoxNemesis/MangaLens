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
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class MediaDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val dao = DownloadDatabase.get(appContext).downloads()

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "MangaLens download"
        val mime = inputData.getString(KEY_MIME) ?: guessMime(url)

        return try {
            setForeground(createForegroundInfo(title, 0L, -1L))
            download(id, url, title, mime)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: IOException) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                markFailed(id, e.message ?: "Network failure")
                Result.failure()
            }
        } catch (e: Throwable) {
            markFailed(id, e.message ?: "Download failed")
            Result.failure()
        }
    }

    private suspend fun download(id: String, url: String, title: String, mime: String) {
        val old = dao.get(id) ?: DownloadEntity(id, url, title, mime)
        val temp = File(applicationContext.cacheDir, "downloads/$id.part").apply { parentFile?.mkdirs() }

        var offset = temp.length()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
            .build()

        HTTP.newCall(request).execute().use { response ->
            if (offset > 0L && response.code == 200) {
                temp.delete()
                offset = 0L
            }
            check(response.isSuccessful) { "HTTP ${response.code}" }

            val body = response.body ?: error("Empty response")
            val announced = body.contentLength()
            val total = if (announced >= 0L) offset + announced else -1L
            var done = offset
            var lastPersistAt = 0L
            var lastPersistBytes = done
            var lastNotificationAt = 0L

            persistProgress(id, old, done, total)

            body.byteStream().buffered(BUFFER_SIZE).use { input ->
                FileOutputStream(temp, offset > 0L).buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read

                        val now = SystemClock.elapsedRealtime()
                        val shouldPersist = done - lastPersistBytes >= PROGRESS_BYTES || now - lastPersistAt >= PROGRESS_INTERVAL_MS
                        if (shouldPersist) {
                            persistProgress(id, old, done, total)
                            lastPersistBytes = done
                            lastPersistAt = now
                        }

                        if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS) {
                            setForeground(createForegroundInfo(title, done, total))
                            lastNotificationAt = now
                        }
                    }
                    output.flush()
                }
            }

            persistProgress(id, old, done, total)
        }

        val uri = publish(temp, title, mime)
        dao.upsert((dao.get(id) ?: old).copy(
            destination = uri.toString(),
            bytesDownloaded = temp.length(),
            totalBytes = temp.length(),
            state = DownloadState.COMPLETED,
            error = null
        ))
        temp.delete()
    }

    private suspend fun persistProgress(id: String, old: DownloadEntity, done: Long, total: Long) {
        dao.upsert((dao.get(id) ?: old).copy(
            bytesDownloaded = done,
            totalBytes = total,
            state = DownloadState.DOWNLOADING,
            error = null
        ))
    }

    private suspend fun markFailed(id: String, message: String) {
        dao.get(id)?.let { dao.upsert(it.copy(state = DownloadState.FAILED, error = message)) }
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
                resolver.openOutputStream(uri)!!.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
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
            ?: applicationContext.filesDir
        val file = File(dir, safeName(title, mime))
        temp.copyTo(file, true)
        return android.net.Uri.fromFile(file)
    }

    private fun safeName(title: String, mime: String): String {
        val clean = title.filter { it.isLetterOrDigit() || it in " _-.()" }.trim().ifBlank { "mangalens" }
        val ext = when {
            mime.contains("jpeg") -> ".jpg"
            mime.contains("png") -> ".png"
            mime.contains("webp") -> ".webp"
            mime.contains("mp4") -> ".mp4"
            mime.contains("webm") -> ".webm"
            mime.contains("mkv") -> ".mkv"
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
        return ForegroundInfo(NOTIFICATION_ID, notification)
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
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 MangaLens/13"

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
