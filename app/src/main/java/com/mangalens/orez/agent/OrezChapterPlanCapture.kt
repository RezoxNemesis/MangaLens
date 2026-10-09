package com.mangalens.orez.agent

import com.mangalens.core.translation.TranslationRefinementRequest
import com.mangalens.core.translation.TranslationStyleProfile

/** Invoked only for a newly accepted plan, never by replay, native lookup, controls or recovery. */
internal object OrezChapterPlanCapture {
    suspend fun capture(plan: OrezTaskPlan, captureRequest: suspend (TranslationStyleProfile) -> TranslationRefinementRequest): OrezTaskPlan {
        if (plan.steps.none { it.call.name == "translate_saved_chapter" }) return plan
        val authorization = requireNotNull(plan.authorization) { "Explicit chapter scope was not captured." }
        require(authorization.origin == OrezTrustOrigin.USER && authorization.explicitUserRequest) {
            "Only an explicit new user request may capture a refinement model."
        }
        if (plan.status != OrezTaskStatus.RUNNING) return plan
        val options = requireNotNull(authorization.translation)
        if (!options.localRefinement || options.refinementRequest != null) return plan
        val selected = options.nativeChapterConfig().style()
        val request = captureRequest(selected)
        require(request.enabled && request.style == selected) { "Captured refinement differs from the selected choice or style." }
        val captured = options.copy(refinementRequest = request)
        captured.nativeChapterConfig()
        return plan.copy(authorization = authorization.copy(translation = captured))
    }
}
