package com.mangalens.orez.agent

internal object OrezChapterPlanScope {
    fun expected(plan: OrezTaskPlan, step: OrezPlanStep): OrezChapterSnapshot {
        OrezDurablePlanRules.validate(plan)
        require(step.call.name == "translate_saved_chapter") { "This is not an owned chapter step." }
        val resolved = OrezDurablePlanRules.resolve(plan, step)
        val producer = plan.steps[step.references.getValue("chapterId").stepIndex]
        return OrezChapterSnapshot(resolved.call.arguments.getValue("chapterId"), producer.outputs["title"].orEmpty(),
            producer.outputs.getValue("pageCount").toInt(), resolved.call.arguments.getValue("sourceFingerprint"))
    }
}
