package com.mangalens.orez.agent

import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs

/** Navigation carries opaque receipts only. Private media descriptors are reloaded from this plan. */
data class OrezSubtitleResultReference(val planId: String, val stepIndex: Int, val taskId: String, val generation: String)

internal object OrezSubtitleResult {
    fun references(plan: OrezTaskPlan): List<OrezSubtitleResultReference> {
        OrezDurablePlanRules.validate(plan)
        if (plan.status != OrezTaskStatus.COMPLETED || plan.pendingControl != null || plan.resuming || plan.pausedByUser) return emptyList()
        return plan.steps.filter { it.call.name == "generate_subtitles" && it.status == OrezStepStatus.COMPLETED &&
            it.outputKind == OrezOutputKind.SUBTITLE_TRACK }.map { step ->
            OrezSubtitleResultReference(plan.id, step.index, step.outputs.getValue("subtitleTaskId"), step.outputs.getValue("generation"))
        }
    }

    fun step(plan: OrezTaskPlan, reference: OrezSubtitleResultReference): OrezPlanStep {
        require(reference in references(plan)) { "This completed subtitle receipt is no longer available." }
        return plan.steps[reference.stepIndex]
    }

    fun verify(plan: OrezTaskPlan, reference: OrezSubtitleResultReference, receipt: OrezSubtitleReceipt) {
        val step = step(plan, reference)
        OrezSubtitlePlanScope.verifyOwned(plan, step, receipt)
        OrezSubtitleTools.verifyCompleted(receipt)
        val current = receipt.outputs(OrezDurablePlanRules.requestId(plan.id, step.index)) +
            ("destination" to "subtitle-track:${receipt.taskId}")
        require(step.outputs.all { (field, value) -> current[field] == value }) { "The saved subtitle export changed after completion." }
    }
}
