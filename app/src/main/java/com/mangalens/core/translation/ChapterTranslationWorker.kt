package com.mangalens.core.translation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mangalens.MainActivity
import com.mangalens.core.reader.SavedChapter
import com.mangalens.orez.agent.OrezTaskRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Foreground, page-checkpointed work survives reader/activity recreation and process restart. */
class ChapterTranslationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(TASK_ID) ?: return@withContext Result.failure()
        val generation = inputData.getString(GENERATION) ?: return@withContext Result.failure()
        val store = ChapterTranslationStore.shared(applicationContext)
        try {
            // WorkManager may restore this job without opening Orez or any Activity.
            // Honor a durable owner-scoped pause/cancel before claiming page computation.
            val initial = store.get(taskId)?.takeIf { it.generation == generation } ?: return@withContext Result.success()
            if (!OrezTaskRecovery.allowChapterWork(applicationContext, store, initial)) return@withContext Result.success()
            val snapshot = store.refresh(taskId, generation)?.takeIf { it.generation == generation }
                ?: return@withContext Result.success()
            if (!store.isCurrent(taskId, generation)) return@withContext Result.success()
            setForeground(foreground(snapshot, waiting = true))
            ChapterTranslationComputeLane.withLock {
                val beforeClaim = store.get(taskId)?.takeIf { it.generation == generation } ?: return@withLock Result.success()
                if (!OrezTaskRecovery.allowChapterWork(applicationContext, store, beforeClaim)) return@withLock Result.success()
                val task = store.markRunning(taskId, generation) ?: return@withLock Result.success()
                ChapterPageTranslator(applicationContext, store, task).use { translator ->
                    publishProgress(task)
                    for (index in task.requestedPages ?: task.pages.map { it.index }) {
                        currentCoroutineContext().ensureActive()
                        if (!store.isCurrent(taskId, generation)) return@withLock Result.success()
                        val beforePage = store.get(taskId)?.takeIf { it.generation == generation } ?: return@withLock Result.success()
                        if (!OrezTaskRecovery.allowChapterWork(applicationContext, store, beforePage)) return@withLock Result.success()
                        if (!com.mangalens.core.compute.ResourceGovernorRuntime.shared.awaitBoundary(
                                com.mangalens.core.compute.ResourceWorkKind.BACKGROUND) { store.isCurrent(taskId, generation) })
                            return@withLock Result.success()
                        // Resource throttling can suspend; owner control remains authoritative before OCR.
                        val readyPage = store.get(taskId)?.takeIf { it.generation == generation } ?: return@withLock Result.success()
                        if (!OrezTaskRecovery.allowChapterWork(applicationContext, store, readyPage)) return@withLock Result.success()
                        val page = store.beginPage(taskId, generation, index) ?: continue
                        val contextText = store.get(taskId)?.pages.orEmpty().takeWhile { it.index != index }
                            .flatMap { it.lettering }.takeLast(12).joinToString("\n") { it.translated }.take(4500)
                        val result = try {
                            val nativeGuard = ChapterNativeEntryGuard(store, task, page, allowOwner = { captured ->
                                OrezTaskRecovery.allowChapterWork(applicationContext, store, captured)
                            })
                            withContext(Dispatchers.Default + nativeGuard.precondition) { translator.translate(page, contextText) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (deferred: com.mangalens.core.compute.ResourcePausedException) {
                            throw deferred
                        } catch (failure: Exception) {
                            // A source/model error is local to this page; verified neighbours and
                            // any earlier successful bubbles remain available for another attempt.
                            page.copy(status = if (page.lettering.isEmpty()) ChapterTranslationPageStatus.FAILED else ChapterTranslationPageStatus.PARTIAL,
                                error = (failure.message ?: "This page could not be translated.").take(ChapterTranslationStore.MAX_ERROR_CHARS))
                        }
                        var committed = false
                        try {
                            committed = store.commitPage(taskId, generation, result)
                        } finally {
                            if (!committed) result.cleanedPath?.let(store::discardUnreferenced)
                        }
                        val latest = store.get(taskId)?.takeIf { it.generation == generation }
                            ?: return@withLock Result.success()
                        if (store.isCurrent(taskId, generation)) publishProgress(latest)
                    }
                }
                store.finish(taskId, generation)?.let { completed ->
                    setProgress(workDataOf(PROCESSED to completed.processedPages, TOTAL to completed.totalPages,
                        "translation_status" to completed.status.name))
                }
                Result.success() // Partial quality failures remain explicit in the durable journal.
            }
        } catch (deferred: com.mangalens.core.compute.ResourcePausedException) {
            if (store.deferForResources(taskId, generation, deferred.message ?: "Waiting for device resources; saved progress will resume automatically."))
                Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                // Explicit controls already changed status/generation. Only an OS interruption
                // of the still-current run becomes QUEUED, eligible for WorkManager replay.
                runCatching { store.interrupted(taskId, generation) }
            }
            throw cancelled
        } catch (failure: Exception) {
            store.fail(taskId, generation, failure.message ?: "Chapter translation could not start or finish.")
            Result.failure()
        }
    }

    private suspend fun publishProgress(task: ChapterTranslationTask) {
        setProgress(workDataOf(PROCESSED to task.processedPages, TOTAL to task.totalPages,
            "translation_status" to task.status.name))
        setForeground(foreground(task))
    }

    private fun foreground(task: ChapterTranslationTask, waiting: Boolean = false): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Chapter translation", NotificationManager.IMPORTANCE_LOW))
        // Generation-specific IDs prevent an old worker's stop from removing a replacement's
        // foreground notification, even when they target the same chapter/configuration.
        val notificationId = 100_000 + ((task.id + task.generation).hashCode() and 0x0fffffff)
        val open = PendingIntent.getActivity(applicationContext, notificationId,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Translating " + task.title.take(70))
            .setContentText(if (waiting) "Waiting for the translation lane" else
                "${task.processedPages} of ${task.totalPages} pages processed · ${task.config.targetLanguage.uppercase()}")
            .setContentIntent(open).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(task.totalPages, task.processedPages, waiting)
            .setOnlyAlertOnce(true).setOngoing(true).build()
        return ForegroundInfo(notificationId, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    companion object {
        internal const val TASK_ID = "chapter_translation_id"
        internal const val GENERATION = "chapter_translation_generation"
        const val PROCESSED = "chapter_translation_processed"
        const val TOTAL = "chapter_translation_total"
        private const val CHANNEL = "chapter_translations"
    }
}

/** All controls use the task's captured generation. Resume returns the new, fenced generation. */
object ChapterTranslationJobs {
    suspend fun start(context: Context, chapter: SavedChapter, config: ChapterTranslationConfig,
        requestedPages: List<Int>? = null, ownerRequestId: String? = null,
        allowOwnerReplacement: Boolean = true, forceReprocess: Boolean = false): ChapterTranslationTask =
        withContext(Dispatchers.IO) {
            val store = ChapterTranslationStore.shared(context)
            val oldGenerations = store.states.value.associate { it.id to it.generation }
            val captured = ChapterRefinementCapturePolicy.forStart(config, chapter.id, ownerRequestId, forceReprocess,
                store.states.value) { style ->
                TranslationOrezRefiner(context).let { refiner ->
                    try { refiner.captureRequest(true, style) } finally { refiner.close() }
                }
            }
            val task = store.start(chapter, captured, requestedPages, ownerRequestId, allowOwnerReplacement, forceReprocess)
            try {
                oldGenerations[task.id]?.takeIf { it != task.generation }?.let { old ->
                    // Cancellation is scoped to the old generation, never a global chapter tag.
                    WorkManager.getInstance(context).cancelUniqueWork(workName(task.id, old)).awaitCompletion()
                }
                enqueueOrFail(context, store, task)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                store.fail(task.id, task.generation, "Unable to start chapter translation: " + (failure.message ?: "Scheduling failed").take(200))
                throw failure
            }
            store.commandSnapshot(task)
        }

    suspend fun pause(context: Context, taskId: String, generation: String): ChapterTranslationTask? = withContext(Dispatchers.IO) {
        val store = ChapterTranslationStore.shared(context)
        val paused = store.pause(taskId, generation) ?: return@withContext null
        WorkManager.getInstance(context).cancelUniqueWork(workName(taskId, generation)).awaitCompletion()
        store.commandSnapshot(paused)
    }

    suspend fun resume(context: Context, taskId: String, generation: String): ChapterTranslationTask? = withContext(Dispatchers.IO) {
        val store = ChapterTranslationStore.shared(context)
        val resumed = store.resume(taskId, generation) ?: return@withContext null
        try {
            WorkManager.getInstance(context).cancelUniqueWork(workName(taskId, generation)).awaitCompletion()
            enqueueOrFail(context, store, resumed)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            store.fail(resumed.id, resumed.generation, "Unable to resume chapter translation: " + (failure.message ?: "Scheduling failed").take(200))
            throw failure
        }
        store.commandSnapshot(resumed)
    }

    suspend fun cancel(context: Context, taskId: String, generation: String): ChapterTranslationTask? = withContext(Dispatchers.IO) {
        val store = ChapterTranslationStore.shared(context)
        val cancelled = store.cancel(taskId, generation) ?: return@withContext null
        WorkManager.getInstance(context).cancelUniqueWork(workName(taskId, generation)).awaitCompletion()
        store.commandSnapshot(cancelled)
    }

    /** Explicit library deletion only. Shared original pages remain owned by ChapterLibrary. */
    suspend fun removeChapter(context: Context, chapterId: String): Unit = withContext(Dispatchers.IO) {
        val store = ChapterTranslationStore.shared(context)
        val removal = store.beginChapterRemoval(chapterId)
        withContext(NonCancellable) {
            val manager = WorkManager.getInstance(context)
            manager.cancelAllWorkByTag(chapterTag(chapterId)).awaitCompletion()
            // Also stop generations created before chapter tags were introduced. Only
            // work tagged with one of this chapter's stable task IDs is eligible.
            val taskIds = removal.tasks.map { it.id }.toSet()
            val prefix = "chapter-translation-"
            manager.getWorkInfosByTag("chapter-translation").get().filter { info ->
                info.tags.any { tag -> tag.startsWith(prefix) && tag.removePrefix(prefix).substringBefore('-') in taskIds }
            }.forEach { info -> manager.cancelWorkById(info.id).awaitCompletion() }
            removal.tasks.forEach { task -> manager.cancelUniqueWork(workName(task.id, task.generation)).awaitCompletion() }
            store.finishChapterRemoval(removal)
        }
    }

    /** Repairs a process death between journal commit and enqueue; native pending work uses KEEP. */
    suspend fun recoverPending(context: Context): Unit = withContext(Dispatchers.IO) {
        val store = ChapterTranslationStore.shared(context)
        for (visible in store.states.value) {
            try {
                val task = store.refresh(visible.id) ?: continue
                if (task.status in setOf(ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING)) enqueueOrFail(context, store, task)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* One failed scheduling/validation does not stop other queued chapters. */ }
        }
        store.cleanupOrphans()
    }

    private suspend fun enqueueOrFail(context: Context, store: ChapterTranslationStore, task: ChapterTranslationTask) {
        if (!store.isCurrent(task.id, task.generation)) return
        try {
            val request = OneTimeWorkRequestBuilder<ChapterTranslationWorker>()
                .setInputData(workDataOf(ChapterTranslationWorker.TASK_ID to task.id, ChapterTranslationWorker.GENERATION to task.generation))
                .setBackoffCriteria(BackoffPolicy.LINEAR, Duration.ofSeconds(10))
                .addTag("chapter-translation").addTag(chapterTag(task.chapterId)).addTag(workName(task.id, task.generation)).build()
            // Offline installed models work without a network constraint. Missing model downloads
            // are handled by the bounded translation phase with a visible resumable error.
            WorkManager.getInstance(context).enqueueUniqueWork(workName(task.id, task.generation), ExistingWorkPolicy.KEEP, request).awaitCompletion()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            store.fail(task.id, task.generation, "Unable to schedule chapter translation: " + (failure.message ?: "WorkManager failed").take(200))
            throw failure
        }
    }

    internal fun workName(taskId: String, generation: String): String = "chapter-translation-$taskId-$generation"
    private fun chapterTag(chapterId: String): String = "chapter-translation-chapter-$chapterId"

    private suspend fun Operation.awaitCompletion() = suspendCancellableCoroutine<Unit> { continuation ->
        val future = result
        future.addListener({
            try {
                future.get()
                if (continuation.isActive) continuation.resume(Unit)
            } catch (failure: Exception) {
                val reason = if (failure is ExecutionException) failure.cause ?: failure else failure
                if (continuation.isActive) continuation.resumeWithException(reason)
            }
        }, Executor { runnable -> runnable.run() })
    }
}
