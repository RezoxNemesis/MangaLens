package com.mangalens.orez.agent

import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/** Native worker boundary; only persisted, captured Orez control intent can gate native work. */
internal object OrezSubtitleWorkGate {
    suspend fun allow(store: OrezTaskStore, planId: String, receipt: OrezSubtitleReceipt,
        stop: suspend (OrezSubtitleReceipt, OrezPendingControl) -> OrezSubtitleReceipt?,
        current: suspend (String) -> OrezSubtitleReceipt?): Boolean = OrezTaskControlFence.mutex.withLock {
        // Reload under the same fence as UI controls and delayed old-worker cleanup.
        val plan = store.load(planId) ?: return@withLock true
        val control = plan.pendingControl ?: return@withLock true
        val step = plan.steps.firstOrNull { it.status != OrezStepStatus.COMPLETED } ?: return@withLock true
        if (step.call.name != "generate_subtitles") return@withLock true
        try {
            OrezSubtitlePlanScope.verifyOwned(plan, step, receipt, allowGenerationRecovery = plan.resuming)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalidScope: Exception) {
            store.noteControlFailure(plan, invalidScope.message ?: "Subtitle control scope changed. Retry the task control.")
            return@withLock true // This intent has no stop rights over the presented native generation.
        }
        try {
            val stopped = stop(receipt, control)
            val acknowledged = stopped ?: current(receipt.taskId)
            if (acknowledged == null || acknowledged.taskId != receipt.taskId || acknowledged.generation != receipt.generation) {
                store.noteControlFailure(plan, "Native subtitle generation changed before stopping. Retry the task control.")
                return@withLock true // Replacement won the native generation lock; it must remain untouched.
            }
            OrezSubtitlePlanScope.verifyOwned(plan, step, acknowledged, allowGenerationRecovery = plan.resuming)
            val inactive = setOf(OrezNativeSubtitleStatus.COMPLETED, OrezNativeSubtitleStatus.PARTIAL, OrezNativeSubtitleStatus.FAILED)
            require(acknowledged.status == OrezNativeSubtitleStatus.CANCELLED ||
                control == OrezPendingControl.PAUSE && acknowledged.status == OrezNativeSubtitleStatus.PAUSED ||
                stopped == null && receipt.status in inactive && acknowledged.status in inactive) {
                "Native subtitle stop is pending; the running generation has not been stopped."
            }
            val evidence = plan.copy(steps = plan.steps.map {
                if (it.index == step.index) it.copy(outputs = acknowledged.outputs(OrezDurablePlanRules.requestId(plan.id, it.index))) else it
            })
            store.finishStop(evidence)
            false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            store.noteControlFailure(plan, failure.message ?: "Native subtitle stop is still pending. Retry the task control.")
            // A valid accepted stop remains a work gate even if journal IO failed.
            false
        }
    }
}
