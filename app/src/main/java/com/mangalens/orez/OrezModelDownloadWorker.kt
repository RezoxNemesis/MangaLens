package com.mangalens.orez

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class OrezModelDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val prefs = appContext.getSharedPreferences("orez_model", Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        val modelManager = OrezModelManager(applicationContext)
        val finalFile = modelManager.optimizedModelFile.apply { parentFile?.mkdirs() }
        val part = File(finalFile.parentFile, finalFile.name + ".part")
        return try {
            prefs.edit().putBoolean("downloading", true).putString("error", null).apply()
            setForeground(notification("OREZ local model", part.length(), OrezModelManager.MODEL_BYTES))
            download(part)
            check(part.length() == OrezModelManager.MODEL_BYTES) { "OREZ model size mismatch" }
            check(sha256(part) == OrezModelManager.MODEL_SHA256) { "OREZ model integrity check failed" }
            if (finalFile.exists()) finalFile.delete()
            check(part.renameTo(finalFile)) { "Unable to finalize OREZ model" }
            // The optimized Q4 pack replaces the older 650 MB Q6 pack after integrity
            // verification, avoiding nearly 1.2 GB of duplicate local model storage.
            modelManager.legacyModelFile.takeIf { it.exists() }?.delete()
            prefs.edit().putBoolean("downloading", false)
                .putLong("bytes", finalFile.length())
                .putLong("total", OrezModelManager.MODEL_BYTES)
                .apply()
            Result.success()
        } catch (t: kotlinx.coroutines.CancellationException) {
            prefs.edit().putBoolean("downloading", false).apply()
            throw t
        } catch (t: Throwable) {
            if (part.length() >= OrezModelManager.MODEL_BYTES) part.delete()
            prefs.edit().putBoolean("downloading", false).putString("error", t.message ?: "OREZ model download failed").apply()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun download(part: File) {
        var offset = part.length()
        val connection = (URL(OrezModelManager.MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MangaLens/2.2")
            if (offset > 0L) setRequestProperty("Range", "bytes=" + offset + "-")
        }
        try {
        connection.connect()
        if (offset > 0L && connection.responseCode == HttpURLConnection.HTTP_OK) {
            part.delete()
            offset = 0L
        }
        if (offset > 0L && connection.responseCode == 206) {
            check(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) { "Model server returned an invalid resume range" }
        }
        check(connection.responseCode in 200..299) { "Model server HTTP " + connection.responseCode }
        val announced = connection.contentLengthLong
        val total = if (announced > 0L) offset + announced else OrezModelManager.MODEL_BYTES
        var done = offset
        var lastPublishBytes = done
        var lastPublishAt = android.os.SystemClock.elapsedRealtime()
        setForeground(notification("OREZ local model", done, total))
        connection.inputStream.use { input ->
            java.io.FileOutputStream(part, offset > 0L).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(done + n <= OrezModelManager.MODEL_BYTES) { "Model transfer exceeded expected size" }
                    output.write(buffer, 0, n)
                    done += n
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (
                        done - lastPublishBytes >= PROGRESS_BYTES ||
                        now - lastPublishAt >= PROGRESS_INTERVAL_MS ||
                        done >= total
                    ) {
                        publish(done, total)
                        lastPublishBytes = done
                        lastPublishAt = now
                    }
                }
            }
        }
        } finally { connection.disconnect() }
    }

    private suspend fun publish(done: Long, total: Long) {
        prefs.edit().putLong("bytes", done).putLong("total", total).apply()
        setProgress(workDataOf("bytes" to done, "total" to total))
        setForeground(notification("OREZ local model", done, total))
    }

    private fun notification(title: String, done: Long, total: Long): ForegroundInfo {
        val channel = "orez_model"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channel, "OREZ model", NotificationManager.IMPORTANCE_LOW))
        }
        val percent = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
        val n = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(percent.toString() + "% • " + format(done) + " / " + format(total))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
        return ForegroundInfo(10002, n, if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    private fun format(v: Long): String = "%.0f MB".format(v / 1_000_000.0)

    private fun sha256(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                d.update(buffer, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_ID = "id"
        private const val PROGRESS_BYTES = 8L * 1024L * 1024L
        private const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
