package com.mangalens.orez.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native stop eligibility is checked under the same lock as Pause/Resume/Cancel controls. */
internal object OrezTaskControlFence {
    val mutex = Mutex()

    suspend fun stopIfRequested(store: OrezTaskStore, id: String, receipt: OrezChapterReceipt,
        host: OrezChapterHost): OrezChapterReceipt? = mutex.withLock {
        val plan = store.load(id) ?: return@withLock null
        if (plan.resuming || (plan.status != OrezTaskStatus.CANCELLED && !plan.pausedByUser)) return@withLock null
        val step = plan.steps.firstOrNull { it.status != OrezStepStatus.COMPLETED } ?: return@withLock null
        val authorization = plan.authorization ?: return@withLock null
        if (step.call.name != "translate_saved_chapter" || receipt.ownerRequestId != OrezDurablePlanRules.requestId(id, step.index) ||
            receipt.chapter.chapterId !in authorization.chapterIds || receipt.options != authorization.translation ||
            (step.outputs["generation"]?.let { it != receipt.generation } == true) ||
            (step.outputs["translationTaskId"]?.let { it != receipt.taskId } == true)) return@withLock null
        if (plan.status == OrezTaskStatus.CANCELLED || plan.pendingControl == OrezPendingControl.CANCEL) host.cancel(receipt) else host.pause(receipt)
    }

    suspend fun stopIfRequested(store: OrezTaskStore, id: String, receipt: OrezSubtitleReceipt,
        host: OrezSubtitleHost): OrezSubtitleReceipt? = mutex.withLock {
        val plan = store.load(id) ?: return@withLock null
        if (plan.resuming || (plan.status != OrezTaskStatus.CANCELLED && !plan.pausedByUser)) return@withLock null
        val step = plan.steps.firstOrNull { it.status != OrezStepStatus.COMPLETED } ?: return@withLock null
        if (step.call.name != "generate_subtitles" || runCatching { OrezSubtitlePlanScope.verifyOwned(plan, step, receipt) }.isFailure) return@withLock null
        if (plan.status == OrezTaskStatus.CANCELLED || plan.pendingControl == OrezPendingControl.CANCEL) host.cancel(receipt) else host.pause(receipt)
    }
}
