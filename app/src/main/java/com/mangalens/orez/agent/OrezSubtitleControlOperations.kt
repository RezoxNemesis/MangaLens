package com.mangalens.orez.agent

import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs

/** Native phase only. The caller owns the shared fence and durable begin/finish markers. */
internal class OrezSubtitleControlOperations(private val host: OrezSubtitleHost) {
    suspend fun stop(plan: OrezTaskPlan, step: OrezPlanStep, control: OrezPendingControl): OrezTaskPlan {
        val id = OrezDurablePlanRules.requestId(plan.id, step.index)
        val receipt = host.findOwned(id) ?: return plan
        OrezSubtitlePlanScope.verifyOwned(plan, step, receipt, allowGenerationRecovery = plan.resuming)
        val stopped = if (control == OrezPendingControl.CANCEL) host.cancel(receipt) else host.pause(receipt)
        // A generation can change outside the Orez fence through Watch controls.
        // A lost native stop is not an acknowledgement of the old generation.
        val acknowledged = stopped ?: requireNotNull(host.findOwned(id)) { "The owned subtitle generation changed before stopping." }
        require(acknowledged.taskId == receipt.taskId && acknowledged.generation == receipt.generation) {
            "Native subtitle generation changed before the stop was acknowledged."
        }
        OrezSubtitlePlanScope.verifyOwned(plan, step, acknowledged, allowGenerationRecovery = plan.resuming)
        val inactive = setOf(OrezNativeSubtitleStatus.COMPLETED, OrezNativeSubtitleStatus.PARTIAL, OrezNativeSubtitleStatus.FAILED)
        require(acknowledged.status == OrezNativeSubtitleStatus.CANCELLED ||
            control == OrezPendingControl.PAUSE && acknowledged.status == OrezNativeSubtitleStatus.PAUSED ||
            stopped == null && receipt.status in inactive && acknowledged.status in inactive) {
            "Native subtitle stop is pending; the running generation has not been stopped."
        }
        return withReceipt(plan, step, acknowledged)
    }

    suspend fun resume(original: OrezTaskPlan, plan: OrezTaskPlan, step: OrezPlanStep): OrezTaskPlan {
        val id = OrezDurablePlanRules.requestId(plan.id, step.index)
        val receipt = host.findOwned(id)
        if (receipt == null) {
            require(step.outputs.isEmpty()) { "The owned native subtitle receipt is missing. Start a new request." }
            return plan
        }
        OrezSubtitlePlanScope.verifyOwned(plan, step, receipt, allowGenerationRecovery = original.resuming)
        val expected = OrezSubtitlePlanScope.expected(plan, step)
        val producer = plan.steps[step.references.getValue("sourceId").stepIndex]
        val verified = if (producer.call.name == "inspect_selected_media") host.inspectSelection(expected.descriptor, expected.speechModelSha256)
            else host.inspectDownload(requireNotNull(OrezSubtitlePlanScope.download(plan, expected.sourceId.removePrefix("download-"))), expected.speechModelSha256)
        require(verified == expected) { "The captured media source changed. Start a new subtitle request." }
        require(receipt.status != OrezNativeSubtitleStatus.CANCELLED) { "Cancelled native subtitles need a new explicit request." }
        val resumed = if (receipt.status in setOf(OrezNativeSubtitleStatus.PAUSED, OrezNativeSubtitleStatus.PARTIAL, OrezNativeSubtitleStatus.FAILED))
            requireNotNull(host.resume(receipt)) { "Native subtitle generation changed before resume." } else receipt
        require(resumed.taskId == receipt.taskId) { "Native subtitle task identity changed during resume." }
        OrezSubtitleTools.verify(resumed, expected, requireNotNull(plan.authorization?.subtitle), id)
        return withReceipt(plan, step, resumed)
    }

    private fun withReceipt(plan: OrezTaskPlan, step: OrezPlanStep, receipt: OrezSubtitleReceipt) = plan.copy(steps = plan.steps.map {
        if (it.index == step.index) it.copy(outputs = receipt.outputs(OrezDurablePlanRules.requestId(plan.id, it.index))) else it
    })
}
