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

/** Durable queue-and-evaluate loop. Native providers own transfers, chapter and speech computation. */
class OrezDownloadTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("task_id") ?: return@withContext Result.failure()
        val store = OrezTaskStore(OrezRoomDatabase.get(applicationContext).tasks())
        val plan = store.load(id) ?: return@withContext Result.failure()
        if (plan.pausedByUser || plan.resuming || plan.status in setOf(OrezTaskStatus.COMPLETED, OrezTaskStatus.CANCELLED, OrezTaskStatus.FAILED)) {
            return@withContext Result.success()
        }
        try {
            setForeground(foreground(id, plan))
            val chapterHost = OrezNativeChapterHost(applicationContext)
            val chapterTools = plan.authorization?.let { scope -> OrezChapterTools(chapterHost, scope,
                stopOwned = { receipt -> OrezTaskControlFence.stopIfRequested(store, id, receipt, chapterHost) }, mayStopOwned = {
                    store.load(id)?.let { it.status == OrezTaskStatus.CANCELLED || (it.pausedByUser && !it.resuming) } == true
                }, isExecuting = { store.isExecuting(id, plan.executionEpoch) }) }
            val subtitleTools = if (plan.steps.any { it.call.name in setOf("inspect_selected_media", "inspect_downloaded_media", "generate_subtitles") })
                OrezSubtitleTools.forPlan(store, plan, OrezNativeSubtitleHost(applicationContext)) else null
            val executor = OrezTaskExecutor(store, OrezDurableTools { step, requestId ->
                when (step.call.name) {
                    "enqueue_download" -> executeDownload(step, requestId)
                    "inspect_saved_chapter", "translate_saved_chapter" -> requireNotNull(chapterTools).execute(step, requestId)
                    "inspect_selected_media", "inspect_downloaded_media", "generate_subtitles" -> requireNotNull(subtitleTools).execute(step, requestId)
                    else -> OrezToolResult.Failed("This tool has no durable native executor.")
                }
            })
            when (val result = executor.run(id, expectedEpoch = plan.executionEpoch)) {
                is OrezTaskExecutor.Result.Completed -> {
                    OrezRoomDatabase.get(applicationContext).messages().insert(com.mangalens.orez.OrezMessageEntity(
                        role = "OREZ", text = completionMessage(result.plan)))
                    Result.success()
                }
                is OrezTaskExecutor.Result.Pending -> if (result.needsResume) Result.success() else Result.retry()
                is OrezTaskExecutor.Result.Failed -> {
                    OrezRoomDatabase.get(applicationContext).messages().insert(com.mangalens.orez.OrezMessageEntity(
                        role = "OREZ", text = "Orez stopped at an unfinished step: ${result.reason.take(200)}. Resume the task to retain verified steps."))
                    Result.failure()
                }
                OrezTaskExecutor.Result.Cancelled, OrezTaskExecutor.Result.AlreadyFinished, OrezTaskExecutor.Result.Superseded -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Preserve the newest completed-step checkpoints, not the initial plan snapshot.
            val latest = store.load(id)
            // Chat delivery is secondary to verified task state. A failed message insert
            // must not downgrade a completed batch or make it eligible for replay.
            if (latest?.status == OrezTaskStatus.COMPLETED || latest?.pausedByUser == true ||
                latest?.executionEpoch != plan.executionEpoch) return@withContext Result.success()
            latest?.let { store.checkpoint(it, OrezTaskStatus.FAILED, failure.message?.take(300)) }
            Result.failure()
        }
    }

    private suspend fun executeDownload(step: OrezPlanStep, downloadId: String): OrezToolResult {
        val downloads = DownloadDatabase.get(applicationContext).downloads()
        val existing = downloads.get(downloadId)
        if (existing == null || existing.state == DownloadState.QUEUED) {
            val queued = withTimeoutOrNull(120_000L) {
                MediaDownloadManager(applicationContext).enqueue(step.call.arguments.getValue("value"),
                    quality = step.call.arguments["quality"]?.let(DownloadQuality::valueOf) ?: DownloadQuality.BEST,
                    requestId = downloadId)
            }
            if (queued == null) return if (runAttemptCount < 2) OrezToolResult.Pending("Retrying media source resolution.")
                else OrezToolResult.Failed("Media resolution timed out. Inspect the source in Web.")
        }
        val terminal = withTimeoutOrNull(8L * 60L * 1000L) {
            downloads.observe().first { rows -> rows.any { row ->
                row.id == downloadId && row.state in setOf(DownloadState.COMPLETED, DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.PAUSED)
            } }.first { it.id == downloadId }
        } ?: return OrezToolResult.Pending("Transfer is continuing in Downloads.")
        return when (terminal.state) {
            DownloadState.PAUSED -> OrezToolResult.Pending("Download paused. Resume this task to continue its remaining steps.", needsResume = true)
            DownloadState.CANCELLED -> OrezToolResult.Cancelled("The current transfer was cancelled.")
            DownloadState.FAILED -> OrezToolResult.Failed(terminal.error ?: "Native download verification failed.")
            DownloadState.COMPLETED -> {
                if (terminal.bytesDownloaded <= 0L) return OrezToolResult.Failed("Completed transfer has no downloaded bytes.")
                val destination = terminal.destination?.takeIf { it.isNotBlank() }
                    ?: if (terminal.isAdaptive) "adaptive-cache:$downloadId" else null
                if (destination == null) return OrezToolResult.Failed("Completed transfer has no published destination.")
                if (!terminal.isAdaptive) {
                    val available = runCatching {
                        applicationContext.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(destination), "r")?.use {
                            it.createInputStream().use { stream -> stream.read() >= 0 }
                        } == true
                    }.getOrDefault(false)
                    if (!available) return OrezToolResult.Failed("The published download is missing or unreadable.")
                }
                OrezToolResult.Completed(mapOf("downloadId" to downloadId, "destination" to destination,
                    "title" to terminal.title.take(250), "bytes" to terminal.bytesDownloaded.toString(),
                    "storage" to if (terminal.isAdaptive) "adaptive-cache" else "published-file"))
            }
            else -> OrezToolResult.Pending("Waiting for native download completion.")
        }
    }

    private fun completionMessage(plan: OrezTaskPlan): String {
        val subtitles = plan.steps.lastOrNull { it.outputKind == OrezOutputKind.SUBTITLE_TRACK }
        if (subtitles != null) return "Generated and saved verified English subtitles for “${subtitles.outputs["title"]}” (${subtitles.outputs["cueCount"]} cues)."
        val translated = plan.steps.lastOrNull { it.outputKind == OrezOutputKind.CHAPTER_TRANSLATION }
        if (translated != null) return "Saved and verified ${translated.outputs["pageCount"]} pages of “${translated.outputs["title"]}” in " +
            "${translated.outputs["targetLanguage"]}. Open this chapter from Library to read its saved translation."
        return "Completed and verified ${plan.steps.size} download(s). Open Downloads to play the saved media."
    }

    private fun foreground(id: String, plan: OrezTaskPlan): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("orez_tasks", "Orez tasks", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "orez_tasks")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Orez is executing your task")
            .setContentText(when {
                plan.steps.any { it.call.name == "enqueue_download" } -> "Transfer progress is available in Downloads"
                plan.steps.any { it.call.name == "generate_subtitles" } -> "Generating subtitles • progress is available in Orez"
                else -> "Saving chapter translation • progress is available in Orez"
            })
            .setOnlyAlertOnce(true).setOngoing(true).build()
        return ForegroundInfo(20000 + (id.hashCode() and 0x7fff), notification,
            if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    companion object {
        fun enqueue(context: Context, taskId: String, replace: Boolean = false, requiresNetwork: Boolean = true) {
            val request = OneTimeWorkRequestBuilder<OrezDownloadTaskWorker>()
                .setInputData(workDataOf("task_id" to taskId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .addTag("orez-task-$taskId").build()
            WorkManager.getInstance(context).enqueueUniqueWork("orez-task-$taskId", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}

