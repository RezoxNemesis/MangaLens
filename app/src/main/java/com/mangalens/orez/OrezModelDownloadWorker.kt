package com.mangalens.orez

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class OrezModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val prefs = appContext.getSharedPreferences("orez_model", Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        val tier = runCatching {
            OrezModelTier.valueOf(
                inputData.getString(KEY_TIER) ?: OrezModelTier.LITE.name
            )
        }.getOrDefault(OrezModelTier.LITE)

        val descriptor = OrezModelCatalog.descriptor(tier)
            ?: return Result.failure(
                workDataOf(KEY_ERROR to "Requested OREZ model tier is not available.")
            )

        val modelManager = OrezModelManager(applicationContext)
        val finalFile = modelManager.fileFor(descriptor).apply {
            parentFile?.mkdirs()
        }
        val part = File(finalFile.parentFile, finalFile.name + ".part")

        return try {
            prefs.edit()
                .putBoolean("downloading", true)
                .putString("downloading_tier", tier.name)
                .putLong("total", descriptor.bytes)
                .putString("error", null)
                .apply()

            setForeground(
                notification(
                    "OREZ " + descriptor.label,
                    part.takeIf { it.exists() }?.length() ?: 0L,
                    descriptor.bytes
                )
            )

            download(part, descriptor)

            check(part.length() == descriptor.bytes) {
                "OREZ model size mismatch. Expected " + descriptor.bytes +
                    " bytes, got " + part.length()
            }
            check(sha256(part).equals(descriptor.sha256, ignoreCase = true)) {
                "OREZ model integrity check failed"
            }

            if (finalFile.exists()) check(finalFile.delete()) {
                "Unable to replace the previous OREZ model"
            }
            check(part.renameTo(finalFile)) {
                "Unable to finalize OREZ model"
            }

            if (tier == OrezModelTier.LITE) {
                // The verified Q4 Lite pack supersedes the old Q6 legacy file.
                modelManager.legacyModelFile.takeIf { it.exists() }?.delete()
            }

            prefs.edit()
                .putBoolean("downloading", false)
                .putLong("bytes", finalFile.length())
                .putLong("total", descriptor.bytes)
                .putString("error", null)
                .apply()

            Result.success(
                workDataOf(
                    KEY_TIER to tier.name,
                    KEY_BYTES to finalFile.length()
                )
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            prefs.edit().putBoolean("downloading", false).apply()
            throw cancelled
        } catch (failure: Throwable) {
            if (part.length() >= descriptor.bytes) {
                part.delete()
            }
            prefs.edit()
                .putBoolean("downloading", false)
                .putString("error", failure.message ?: "OREZ model download failed")
                .apply()

            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        KEY_ERROR to (failure.message ?: "OREZ model download failed")
                    )
                )
            }
        }
    }

    private suspend fun download(
        part: File,
        descriptor: OrezModelDescriptor
    ) {
        var offset = part.takeIf { it.exists() }?.length() ?: 0L

        if (offset > descriptor.bytes) {
            part.delete()
            offset = 0L
        }

        val connection = (URL(descriptor.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MangaLens/Next")
            setRequestProperty("Accept", "application/octet-stream,*/*")
            if (offset > 0L) {
                setRequestProperty("Range", "bytes=" + offset + "-")
            }
        }

        try {
            connection.connect()

            if (offset > 0L && connection.responseCode == HttpURLConnection.HTTP_OK) {
                // Origin ignored Range. Restart rather than corrupting the partial.
                part.delete()
                offset = 0L
            }

            if (offset > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                check(
                    connection.getHeaderField("Content-Range")
                        ?.startsWith("bytes " + offset + "-") == true
                ) {
                    "Model server returned an invalid resume range"
                }
            }

            check(connection.responseCode in 200..299) {
                "Model server HTTP " + connection.responseCode
            }

            val announced = connection.contentLengthLong
            val total = if (announced > 0L) offset + announced else descriptor.bytes

            check(total == descriptor.bytes) {
                "Model server announced an unexpected size. Expected " +
                    descriptor.bytes + " bytes, got " + total
            }

            var done = offset
            var lastPublishBytes = done
            var lastPublishAt = android.os.SystemClock.elapsedRealtime()

            setForeground(notification("OREZ " + descriptor.label, done, total))

            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, offset > 0L).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break

                        check(done + count <= descriptor.bytes) {
                            "Model transfer exceeded expected size"
                        }

                        output.write(buffer, 0, count)
                        done += count

                        val now = android.os.SystemClock.elapsedRealtime()
                        if (
                            done - lastPublishBytes >= PROGRESS_BYTES ||
                            now - lastPublishAt >= PROGRESS_INTERVAL_MS ||
                            done >= total
                        ) {
                            publish(done, total, descriptor.label)
                            lastPublishBytes = done
                            lastPublishAt = now
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun publish(
        done: Long,
        total: Long,
        label: String
    ) {
        prefs.edit()
            .putLong("bytes", done)
            .putLong("total", total)
            .apply()
        setProgress(
            workDataOf(
                KEY_BYTES to done,
                KEY_TOTAL to total
            )
        )
        setForeground(notification("OREZ " + label, done, total))
    }

    private fun notification(
        title: String,
        done: Long,
        total: Long
    ): ForegroundInfo {
        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "OREZ model",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val percent = if (total > 0L) {
            ((done * 100L) / total).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(
                percent.toString() + "% • " + format(done) + " / " + format(total)
            )
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        val foregroundType = if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, foregroundType)
    }

    private fun format(bytes: Long): String =
        "%.0f MB".format(bytes / 1_000_000.0)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TIER = "tier"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        private const val NOTIFICATION_ID = 10002
        private const val CHANNEL_ID = "orez_model"
        private const val BUFFER_BYTES = 1024 * 1024
        private const val PROGRESS_BYTES = 8L * 1024L * 1024L
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_ATTEMPTS = 3
    }
}
