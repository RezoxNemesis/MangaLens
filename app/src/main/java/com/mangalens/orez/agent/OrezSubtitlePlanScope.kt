package com.mangalens.orez.agent

import com.mangalens.orez.agent.OrezSubtitleTools.Companion.descriptor

/** Scope comes from captured selection or this plan's verified native transfer prefix. */
internal object OrezSubtitlePlanScope {
    fun download(plan: OrezTaskPlan, id: String): OrezSubtitleDownload? {
        val step = plan.steps.firstOrNull { it.call.name == "enqueue_download" && OrezDurablePlanRules.requestId(plan.id, it.index) == id } ?: return null
        if (step.status != OrezStepStatus.COMPLETED || step.outputKind != OrezOutputKind.DOWNLOAD_RECEIPT ||
            step.outputs["downloadId"] != id || step.outputs["storage"] != "published-file") return null
        val source = step.call.arguments["value"]?.takeIf { it in plan.authorization!!.urls } ?: return null
        val destination = step.outputs["destination"]?.takeIf { value ->
            value.length in 1..8192 && runCatching { java.net.URI(value).scheme in setOf("content", "file") }.getOrDefault(false)
        } ?: return null
        val bytes = step.outputs["bytes"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return OrezSubtitleDownload(id, source, destination, bytes, step.outputs["title"].orEmpty().take(250))
    }

    fun expected(plan: OrezTaskPlan, step: OrezPlanStep): OrezSubtitleSnapshot {
        OrezDurablePlanRules.validate(plan)
        require(step.call.name == "generate_subtitles") { "This is not an owned subtitle step." }
        val resolved = OrezDurablePlanRules.resolve(plan, step)
        val producer = plan.steps[step.references.getValue("sourceId").stepIndex]
        val descriptor = when (producer.call.name) {
            "inspect_selected_media" -> requireNotNull(plan.authorization?.selectedMedia)
            "inspect_downloaded_media" -> {
                val nativeId = OrezDurablePlanRules.resolve(plan, producer).call.arguments.getValue("downloadId")
                requireNotNull(download(plan, nativeId)) { "The verified download source is no longer available." }.descriptor()
            }
            else -> error("The subtitle source has another producer type.")
        }
        return OrezSubtitleSnapshot(resolved.call.arguments.getValue("sourceId"), descriptor,
            resolved.call.arguments.getValue("sourceFingerprint"), resolved.call.arguments.getValue("speechModelSha256"))
    }

    fun verifyOwned(plan: OrezTaskPlan, step: OrezPlanStep, receipt: OrezSubtitleReceipt, allowGenerationRecovery: Boolean = false) {
        OrezSubtitleTools.verify(receipt, expected(plan, step), requireNotNull(plan.authorization?.subtitle),
            OrezDurablePlanRules.requestId(plan.id, step.index))
        step.outputs["subtitleTaskId"]?.let { require(receipt.taskId == it) { "Native subtitle task identity changed." } }
        if (!allowGenerationRecovery) step.outputs["generation"]?.let { require(receipt.generation == it) { "Native subtitle generation was replaced. Start a new request." } }
    }
}
