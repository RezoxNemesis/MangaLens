package com.mangalens.orez.agent

import com.mangalens.orez.research.OrezResearchEvidence
import com.mangalens.orez.research.OrezResearchEvidenceCodec
import com.mangalens.orez.research.OrezResearchRequest

internal object OrezResearchResults {
    /** Only committed typed outputs become visible. This never launches a fetch or chat/model call. */
    fun evidence(plan: OrezTaskPlan): List<OrezResearchEvidence> {
        if (plan.status != OrezTaskStatus.COMPLETED) return emptyList()
        OrezDurablePlanRules.validate(plan)
        return plan.steps.filter { it.status == OrezStepStatus.COMPLETED && it.call.name == "research_web" && it.outputKind == OrezOutputKind.RESEARCH_EVIDENCE }
            .map { step -> OrezResearchEvidenceCodec.receipt(step.outputs, OrezDurablePlanRules.requestId(plan.id, step.index),
                OrezResearchRequest.captured(plan.objective, step.call.arguments)) }
    }
}
