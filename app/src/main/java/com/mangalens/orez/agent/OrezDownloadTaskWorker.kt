package com.mangalens.orez.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.mangalens.download.DownloadDatabase
import com.mangalens.download.DownloadQuality
import com.mangalens.download.DownloadState
import com.mangalens.download.MediaDownloadManager
import com.mangalens.orez.OrezRoomDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

/** Durable queue-and-evaluate loop. Native download workers own transfer/mux/recovery. */
class OrezDownloadTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("task_id") ?: return@withContext Result.failure()
        val store = OrezTaskStore(OrezRoomDatabase.get(applicationContext).tasks())
        val plan = store.load(id) ?: return@withContext Result.failure()
        if (plan.status in setOf(OrezTaskStatus.COMPLETED, OrezTaskStatus.CANCELLED, OrezTaskStatus.FAILED)) {
            return@withContext Result.success()
        }
        try {
            val call = plan.steps.single().call
            OrezToolRegistry().validate(call)
            require(call.name == "enqueue_download") { "This worker accepts only download tools" }
            require(OrezPolicyEngine().evaluate(call, OrezAgentContext()).allowed)
            setForeground(foreground(id))
            val running = plan.copy(status = OrezTaskStatus.RUNNING,
                steps = plan.steps.map { it.copy(status = OrezStepStatus.RUNNING) })
            store.checkpoint(running)
            val downloadId = "orez-$id"
            val downloads = DownloadDatabase.get(applicationContext).downloads()
            val existing = downloads.get(downloadId)
            if (existing == null || existing.state == DownloadState.QUEUED) {
                // Deterministic ID closes the process-death gap between queuing and journaling.
                val queued = withTimeoutOrNull(120_000L) {
                    MediaDownloadManager(applicationContext).enqueue(call.arguments.getValue("value"),
                        quality = DownloadQuality.BEST, requestId = downloadId)
                }
                if (queued == null) {
                    if (runAttemptCount < 2) return@withContext Result.retry()
                    error("Media resolution timed out. Retry from the source page.")
                }
            }
            val terminal = withTimeoutOrNull(8L * 60L * 1000L) {
                downloads.observe().first { rows -> rows.any { row ->
                    row.id == downloadId && row.state in setOf(DownloadState.COMPLETED, DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.PAUSED)
                } }.first { it.id == downloadId }
            }
            if (terminal == null && runAttemptCount < 12) {
                // Transfer survives this watchdog. A later run observes the same download ID.
                return@withContext Result.retry()
            }
            if (terminal == null || terminal.state == DownloadState.PAUSED) {
                store.checkpoint(running, OrezTaskStatus.DISPATCHED,
                    "Transfer remains in Downloads. Resume or inspect progress there.")
                return@withContext Result.success()
            }
            val status = when (terminal.state) {
                DownloadState.COMPLETED -> OrezTaskStatus.COMPLETED
                DownloadState.CANCELLED -> OrezTaskStatus.CANCELLED
                else -> OrezTaskStatus.FAILED
            }
            store.checkpoint(running.copy(status = status,
                steps = running.steps.map { it.copy(status = if (status == OrezTaskStatus.COMPLETED) OrezStepStatus.COMPLETED else OrezStepStatus.FAILED) }),
                error = terminal.error)
            if (store.load(id)?.status == status) {
                OrezRoomDatabase.get(applicationContext).messages().insert(com.mangalens.orez.OrezMessageEntity(
                    role = "OREZ", text = when(status) {
                        OrezTaskStatus.COMPLETED -> "Download complete: ${terminal.title.take(160)}. Open Downloads to play the saved media."
                        OrezTaskStatus.CANCELLED -> "The download was cancelled."
                        else -> "The download failed. Open Downloads to inspect the source and retry."
                    }))
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            // WorkManager may stop for network/process constraints. Retain the checkpoint.
            throw cancelled
        } catch (failure: Exception) {
            store.checkpoint(plan, OrezTaskStatus.FAILED, failure.message?.take(300))
            Result.failure()
        }
    }

    private fun foreground(id: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("orez_tasks", "Orez tasks", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "orez_tasks")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Orez is managing your download")
            .setContentText("Progress and recovery are available in Downloads")
            .setOnlyAlertOnce(true).setOngoing(true).build()
        return ForegroundInfo(20000 + (id.hashCode() and 0x7fff), notification,
            if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    companion object {
        fun enqueue(context: Context, taskId: String) {
            val request = OneTimeWorkRequestBuilder<OrezDownloadTaskWorker>()
                .setInputData(workDataOf("task_id" to taskId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .addTag("orez-task-$taskId").build()
            WorkManager.getInstance(context).enqueueUniqueWork("orez-task-$taskId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
