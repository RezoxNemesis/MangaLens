package com.mangalens.orez

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class OrezModelDownloadWorker @JvmOverloads constructor(
    appContext: Context,
    params: WorkerParameters,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : CoroutineWorker(appContext, params) {
    private val transfers = OrezModelTransferPreferences(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tier = runCatching {
            OrezModelTier.valueOf(inputData.getString(KEY_TIER) ?: OrezModelTier.LITE.name)
        }.getOrDefault(OrezModelTier.LITE)
        val descriptor = OrezModelCatalog.descriptor(tier)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Requested OREZ model tier is not available."))
        val transferId = inputData.getString(KEY_ID)?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Model transfer identity is missing. Resume the saved download."))
        if (!transfers.claim(transferId, id.toString(), tier)) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Model transfer was paused or replaced."))
        }
        val modelManager = OrezModelManager.forWorker(applicationContext)
        val finalFile = modelManager.fileFor(descriptor).apply { parentFile?.mkdirs() }
        val part = File(finalFile.parentFile, finalFile.name + ".part")
        val operation = currentCoroutineContext()
        val checkpoint = { operation.ensureActive(); transfers.checkpoint(transferId) }

        // Hold this lane through blocking HTTP completion, file cleanup, and guarded final status.
        OrezModelTransferIO.withWriter(part, checkpoint) {
            try {
                checkpoint()
                transfers.started(transferId, tier, descriptor.bytes)
                foreground(transferId, "OREZ " + descriptor.label, part.length(), descriptor.bytes, checkpoint)
                modelManager.verifyExistingModels()
                checkpoint()
                val saved = modelManager.savedCandidate(descriptor)
                val remaining = if (saved != null) 0L else (descriptor.bytes - part.length()).coerceAtLeast(0L)
                val storageReserve = if (saved != null) 1024L * 1024L else 64L * 1024L * 1024L
                if (android.os.StatFs(finalFile.parentFile!!.absolutePath).availableBytes < remaining + storageReserve) {
                    throw IOException("Not enough storage for this Orez model. Free space and resume the download; the working model was kept.")
                }
                val candidate = saved ?: part.also { download(it, descriptor, transferId, checkpoint) }
                checkpoint()
                if (candidate.length() < descriptor.bytes) throw IOException("Model transfer stopped early. Download again to resume the saved partial.")
                foreground(transferId, "Verifying OREZ " + descriptor.label, candidate.length(), descriptor.bytes, checkpoint)
                val activated = modelManager.activateVerifiedCandidate(candidate, descriptor, checkpoint)
                checkpoint()
                transfers.finished(transferId, activated.length(), descriptor.bytes)
                Result.success(workDataOf(KEY_TIER to tier.name, KEY_BYTES to activated.length()))
            } catch (cancelled: CancellationException) {
                // Constraint stops may retry this same request. Explicit pause/resume has already
                // invalidated its ID, so an older catch must not clear the new worker's state.
                transfers.waiting(transferId, "Waiting to resume the saved model partial.", part.length())
                throw cancelled
            } catch (failure: Throwable) {
                checkpoint()
                if (failure is OrezModelCompatibilityException && part.length() >= descriptor.bytes) {
                    transfers.mutate(transferId) {
                        runCatching {
                            java.nio.file.Files.move(part.toPath(), File(part.parentFile, part.name + ".rejected-" + java.util.UUID.randomUUID()).toPath(),
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                        }
                    }
                }
                val message = failure.message ?: "OREZ model download failed"
                if (failure !is OrezModelCompatibilityException && runAttemptCount < MAX_ATTEMPTS) {
                    transfers.waiting(transferId, message, part.length())
                    Result.retry()
                } else {
                    transfers.failed(transferId, message)
                    Result.failure(workDataOf(KEY_ERROR to message))
                }
            }
        }
    }

    private suspend fun download(part: File, descriptor: OrezModelDescriptor, transferId: String, checkpoint: () -> Unit) {
        checkpoint()
        var offset = part.length()
        if (offset == descriptor.bytes) return
        if (offset > descriptor.bytes) {
            if (!transfers.mutate(transferId) { check(part.delete()) { "Could not reset the oversized model partial." } }) checkpoint()
            offset = 0L
        }
        val connection = openConnection(URL(descriptor.url)).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MangaLens/Next")
            setRequestProperty("Accept", "application/octet-stream,*/*")
            if (offset > 0L) setRequestProperty("Range", "bytes=" + offset + "-")
        }
        OrezModelTransferIO.withConnection(connection::disconnect) {
            checkpoint()
            connection.connect()
            checkpoint()
            offset = OrezModelTransferPolicy.responseOffset(connection.responseCode, offset,
                connection.getHeaderField("Content-Range"), connection.contentLengthLong, descriptor.bytes)
            foreground(transferId, "OREZ " + descriptor.label, offset, descriptor.bytes, checkpoint)
            connection.inputStream.use { input ->
                OrezModelTransferIO.copy(part, input, offset, descriptor.bytes, checkpoint, android.os.SystemClock::elapsedRealtime) {
                    publish(transferId, it, descriptor.bytes, descriptor.label, checkpoint)
                }
            }
        }
    }

    private suspend fun publish(transferId: String, done: Long, total: Long, label: String, checkpoint: () -> Unit) {
        checkpoint()
        if (!transfers.progress(transferId, done, total)) checkpoint()
        setProgress(workDataOf(KEY_BYTES to done, KEY_TOTAL to total))
        foreground(transferId, "OREZ " + label, done, total, checkpoint)
    }

    private suspend fun foreground(transferId: String, title: String, done: Long, total: Long, checkpoint: () -> Unit) {
        checkpoint()
        setForeground(notification(transferId, title, done, total))
        checkpoint()
    }

    private fun notification(transferId: String, title: String, done: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "OREZ model", NotificationManager.IMPORTANCE_LOW))
        }
        val percent = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(percent.toString() + "% • " + format(done) + " / " + format(total))
            .setProgress(100, percent, false).setOngoing(true).setOnlyAlertOnce(true).build()
        val foregroundType = if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        // An old worker's notification cleanup cannot remove the resumed worker's notification.
        return ForegroundInfo((transferId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1), notification, foregroundType)
    }

    private fun format(bytes: Long): String = "%.0f MB".format(bytes / 1_000_000.0)

    companion object {
        const val KEY_ID = "id"
        const val KEY_TIER = "tier"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "orez_model"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_ATTEMPTS = 3
    }
}
