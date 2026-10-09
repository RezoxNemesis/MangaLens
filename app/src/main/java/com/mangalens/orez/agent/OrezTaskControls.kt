package com.mangalens.orez.agent

import android.content.Context
import androidx.work.WorkManager
import com.mangalens.download.MediaDownloadManager
import com.mangalens.orez.agent.OrezChapterTools.Companion.outputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Task controls fence Orez first, then control only its stable native request/generation. */
class OrezTaskControls(context: Context, private val store: OrezTaskStore, private val host: OrezChapterHost = OrezNativeChapterHost(context),
    private val enqueueTask: ((OrezTaskPlan) -> Unit)? = null) {
    private val appContext = context.applicationContext
    private var subtitleHostOverride: OrezSubtitleHost? = null
    private fun subtitleHost(): OrezSubtitleHost = subtitleHostOverride ?: OrezNativeSubtitleHost(appContext).also { subtitleHostOverride = it }
    constructor(context: Context, store: OrezTaskStore, subtitleHost: OrezSubtitleHost, enqueueTask: ((OrezTaskPlan) -> Unit)? = null) :
        this(context, store, enqueueTask = enqueueTask) { subtitleHostOverride = subtitleHost }

    suspend fun pause(id: String, restoreOnly: Boolean = false) = stop(id, OrezPendingControl.PAUSE, restoreOnly)
    suspend fun cancel(id: String, restoreOnly: Boolean = false) = stop(id, OrezPendingControl.CANCEL, restoreOnly)

    private suspend fun stop(id: String, control: OrezPendingControl, restoreOnly: Boolean): Unit =
        withContext(Dispatchers.IO + NonCancellable) { OrezTaskControlFence.mutex.withLock {
            var plan = store.beginStop(id, control, restoreOnly) ?: return@withLock
            try {
                WorkManager.getInstance(appContext).cancelUniqueWork("orez-task-$id")
                val step = plan.steps.firstOrNull { it.status != OrezStepStatus.COMPLETED }
                when (step?.call?.name) {
                    "enqueue_download" -> if (control == OrezPendingControl.PAUSE)
                        MediaDownloadManager(appContext).pause(OrezDurablePlanRules.requestId(id, step.index))
                    "translate_saved_chapter" -> owned(plan, step, allowGenerationRecovery = plan.resuming)?.let { receipt ->
                        val stopped = if (control == OrezPendingControl.CANCEL) host.cancel(receipt) else host.pause(receipt)
                        val evidence = stopped ?: receipt
                        plan = plan.copy(steps = plan.steps.map {
                            if (it.index == step.index) it.copy(outputs = evidence.outputs(OrezDurablePlanRules.requestId(id, it.index))) else it
                        })
                    }
                    "generate_subtitles" -> plan = OrezSubtitleControlOperations(subtitleHost()).stop(plan, step, control)
                }
                // Dismiss retains the established behavior of leaving a native download in Downloads.
                check(store.finishStop(plan) != null) { "Task changed while completing its native stop." }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                store.noteControlFailure(plan, failure.message ?: "Native stop is pending. Retry the task control.")
                throw failure
            }
        } }

    suspend fun resume(id: String, restoreOnly: Boolean = false): Unit = withContext(Dispatchers.IO + NonCancellable) { OrezTaskControlFence.mutex.withLock {
        val original = store.load(id) ?: return@withLock
        if (restoreOnly && !original.resuming) return@withLock
        var plan = store.resume(id, dispatchReady = false) ?: return@withLock
        try {
            val step = plan.steps.firstOrNull { it.status != OrezStepStatus.COMPLETED } ?: return@withLock
            when (step.call.name) {
                "enqueue_download" -> MediaDownloadManager(appContext).resume(OrezDurablePlanRules.requestId(id, step.index))
                "translate_saved_chapter" -> {
                    val receipt = owned(plan, step, allowGenerationRecovery = original.resuming)
                    if (receipt != null) {
                        val resolved = OrezDurablePlanRules.resolve(plan, step)
                        val chapter = requireNotNull(host.inspect(resolved.call.arguments.getValue("chapterId")))
                        require(chapter.sourceFingerprint == resolved.call.arguments["sourceFingerprint"]) { "Sources changed. Start a new chapter request." }
                        OrezChapterTools.verify(receipt, chapter, plan.authorization!!.translation!!,
                            OrezDurablePlanRules.requestId(id, step.index))
                        require(receipt.status != OrezNativeChapterStatus.CANCELLED) { "A cancelled native chapter cannot be resumed. Start a new request." }
                        val resumed = if (receipt.status in setOf(OrezNativeChapterStatus.PAUSED, OrezNativeChapterStatus.PARTIAL, OrezNativeChapterStatus.FAILED))
                            requireNotNull(host.resume(receipt)) { "Native generation changed before resume." } else receipt
                        plan = plan.copy(steps = plan.steps.map {
                            if (it.index == step.index) it.copy(outputs = resumed.outputs(OrezDurablePlanRules.requestId(id, it.index))) else it
                        })
                    } else require(step.outputs.isEmpty()) { "The native translation receipt is missing. Start a new request." }
                }
                "generate_subtitles" -> plan = OrezSubtitleControlOperations(subtitleHost()).resume(original, plan, step)
            }
            plan = requireNotNull(store.finishResume(plan)) { "Task changed during resume." }
            if (enqueueTask != null) enqueueTask.invoke(plan) else
                OrezDownloadTaskWorker.enqueue(appContext, id, replace = true, requiresNetwork = OrezDurablePlanRules.requiresNetwork(plan))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // Scheduling can fail after native G2 committed. Retain durable recovery proof;
            // another explicit attempt can reconcile only this owned source/configuration.
            store.noteControlFailure(plan, failure.message?.take(300) ?: "Unable to resume the native task.")
            throw failure
        }
    } }

    private suspend fun owned(plan: OrezTaskPlan, step: OrezPlanStep, allowGenerationRecovery: Boolean = false): OrezChapterReceipt? {
        val id = OrezDurablePlanRules.requestId(plan.id, step.index)
        val current = host.findOwned(id) ?: return null
        require(current.ownerRequestId == id && current.chapter.chapterId in plan.authorization!!.chapterIds &&
            current.options == plan.authorization.translation) { "Native task does not match captured scope." }
        if (!allowGenerationRecovery) step.outputs["generation"]?.let { require(current.generation == it) { "Native translation was replaced. Start a new request." } }
        step.outputs["translationTaskId"]?.let { require(current.taskId == it) { "Native task identity changed." } }
        if (allowGenerationRecovery) {
            val resolved = OrezDurablePlanRules.resolve(plan, step)
            val producer = plan.steps[step.references.getValue("chapterId").stepIndex]
            val scope = OrezChapterSnapshot(resolved.call.arguments.getValue("chapterId"), producer.outputs["title"].orEmpty(),
                producer.outputs.getValue("pageCount").toInt(), resolved.call.arguments.getValue("sourceFingerprint"))
            OrezChapterTools.verify(current, scope, plan.authorization.translation!!, id)
        }
        return current
    }

}
