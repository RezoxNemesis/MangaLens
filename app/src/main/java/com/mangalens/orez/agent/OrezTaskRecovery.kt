package com.mangalens.orez.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.core.translation.ChapterTranslationTask
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.agent.OrezChapterTools.Companion.outputs
import com.mangalens.ui.video.SubtitleGenerationStore
import com.mangalens.ui.video.SubtitleGenerationTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bounded headless restoration; accepted control rights come exclusively from the task journal. */
object OrezTaskRecovery {
    /** Application startup only schedules IO. It never hashes pages or waits on the main thread. */
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("orez-control-recovery", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OrezControlRecoveryWorker>().build())
    }

    suspend fun recoverPendingControls(context: Context) = withContext(Dispatchers.IO) {
        val store = OrezTaskStore(OrezRoomDatabase.get(context).tasks())
        val controls = OrezTaskControls(context, store)
        val pending = OrezRoomDatabase.get(context).tasks().observeActive().first().mapNotNull {
            store.load(it.id)?.takeIf { task -> task.pendingControl != null || task.resuming }?.id
        }
        // Capture IDs once and visit each at most once. An unsuccessful first chunk must
        // not starve later controls or create a repeated retry loop on startup.
        OrezControlRecoveryBatch.run(pending) { id ->
            val task = store.load(id) ?: return@run
            when (task.pendingControl) {
                OrezPendingControl.CANCEL -> controls.cancel(id, restoreOnly = true)
                OrezPendingControl.PAUSE -> controls.pause(id, restoreOnly = true)
                null -> if (task.resuming) controls.resume(id, restoreOnly = true)
            }
        }
    }

    /**
     * A native worker checks before refresh/claim/OCR, including a WorkManager-only process
     * restart. Saved metadata identifies the scope; this never reports translation completion.
     * Native store generation guards make replacement between this check and stop harmless.
     */
    suspend fun allowChapterWork(context: Context, nativeStore: ChapterTranslationStore, task: ChapterTranslationTask,
        store: OrezTaskStore = OrezTaskStore(OrezRoomDatabase.get(context).tasks())): Boolean {
        val owner = task.ownerRequestId ?: return true
        if (!owner.startsWith("orez-")) return true
        val candidates = OrezDownloadTaskLink.candidates(owner)
        val ownerPlan = candidates.firstNotNullOfOrNull { id -> store.load(id)?.takeIf { plan ->
            plan.steps.any { it.call.name == "translate_saved_chapter" && OrezDurablePlanRules.requestId(id, it.index) == owner }
        } } ?: return true
        return OrezTaskControlFence.mutex.withLock {
            val plan = store.load(ownerPlan.id) ?: return@withLock true
            if (plan.pendingControl == null) return@withLock true
            val step = plan.steps.firstOrNull { it.status != OrezStepStatus.COMPLETED } ?: return@withLock true
            if (step.call.name != "translate_saved_chapter" || OrezDurablePlanRules.requestId(plan.id, step.index) != owner) return@withLock true
            val receipt = try {
                OrezDurablePlanRules.validate(plan)
                val resolved = OrezDurablePlanRules.resolve(plan, step)
                val producer = plan.steps[step.references.getValue("chapterId").stepIndex]
                val chapter = OrezChapterSourceEvidence.snapshot(task.chapterId, task.title,
                    task.pages.map { OrezChapterSource(it.index, it.sourcePath, it.sourceSha256) })
                val options = task.config.orezChapterOptions()
                require(task.requestedPages == null && chapter.chapterId == resolved.call.arguments["chapterId"] &&
                    chapter.sourceFingerprint == resolved.call.arguments["sourceFingerprint"] && chapter.pageCount.toString() == producer.outputs["pageCount"] &&
                    options == plan.authorization!!.translation) { "Native stop does not match captured chapter/configuration/source scope." }
                require(step.outputs["translationTaskId"]?.let { it == task.id } != false &&
                    (plan.resuming || step.outputs["generation"]?.let { it == task.generation } != false)) { "Native stop belongs to another generation." }
                OrezChapterReceipt(task.id, task.generation, owner, chapter, options,
                    OrezNativeChapterStatus.valueOf(task.status.name), task.completedPages, task.pages.sumOf { it.lettering.size }, task.error)
            } catch (invalidScope: Exception) {
                // A stale/malformed intent cannot fail or stop a newer native user's generation.
                store.noteControlFailure(plan, invalidScope.message ?: "Native stop scope changed. Retry the task control.")
                return@withLock true
            }
            val stopped = if (plan.pendingControl == OrezPendingControl.CANCEL) nativeStore.cancel(task.id, task.generation)
                else nativeStore.pause(task.id, task.generation)
            val acknowledgedTask = stopped ?: nativeStore.get(task.id)?.takeIf { it.generation == task.generation &&
                it.status.name in setOf("PAUSED", "CANCELLED", "COMPLETED", "PARTIAL", "FAILED") }
                ?: return@withLock true // Replacement won the native store lock; this intent has no authority over it.
            val evidence = receipt.copy(status = OrezNativeChapterStatus.valueOf(acknowledgedTask.status.name))
            val acknowledged = plan.copy(steps = plan.steps.map {
                if (it.index == step.index) it.copy(outputs = evidence.outputs(owner)) else it
            })
            store.finishStop(acknowledged)
            false
        }
    }

    /** Native subtitle worker calls before claim, per window and before final publication. */
    suspend fun allowSubtitleWork(context: Context, nativeStore: SubtitleGenerationStore, task: SubtitleGenerationTask,
        store: OrezTaskStore = OrezTaskStore(OrezRoomDatabase.get(context).tasks())): Boolean {
        val owner = task.ownerRequestId ?: return true
        if (!owner.startsWith("orez-")) return true
        val plan = OrezDownloadTaskLink.candidates(owner).firstNotNullOfOrNull { id -> store.load(id)?.takeIf { captured ->
            captured.steps.any { step -> step.call.name == "generate_subtitles" && OrezDurablePlanRules.requestId(id, step.index) == owner }
        } } ?: return true
        if (plan.pendingControl == null) return true
        val expected = try {
            val step = plan.steps.first { it.call.name == "generate_subtitles" && OrezDurablePlanRules.requestId(plan.id, it.index) == owner }
            OrezSubtitlePlanScope.expected(plan, step)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (invalidScope: Exception) {
            store.noteControlFailure(plan, invalidScope.message ?: "Subtitle stop scope changed. Retry the task control.")
            return true
        }
        val receipt = try { OrezSubtitleNativeEvidence.metadataReceipt(task, expected.sourceId, expected.descriptor) }
        catch (invalidScope: Exception) {
            store.noteControlFailure(plan, invalidScope.message ?: "Native subtitle stop identity is unavailable.")
            return true
        }
        return OrezSubtitleWorkGate.allow(store, plan.id, receipt,
            stop = { captured, control ->
                // Direct generation-fenced store mutation avoids cancelling this worker's own WM operation.
                val stopped = if (control == OrezPendingControl.CANCEL) nativeStore.cancel(captured.taskId, captured.generation)
                    else nativeStore.pause(captured.taskId, captured.generation)
                stopped?.let { OrezSubtitleNativeEvidence.metadataReceipt(it, expected.sourceId, expected.descriptor) }
            }, current = { id -> nativeStore.get(id)?.let { OrezSubtitleNativeEvidence.metadataReceipt(it, expected.sourceId, expected.descriptor) } })
    }
}

class OrezControlRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        OrezTaskRecovery.recoverPendingControls(applicationContext)
        return Result.success()
    }
}
