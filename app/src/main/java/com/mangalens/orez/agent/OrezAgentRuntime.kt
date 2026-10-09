package com.mangalens.orez.agent

class OrezAgentRuntime(
    private val planner: OrezAgentPlanner = OrezAgentPlanner(),
    private val policy: OrezPolicyEngine = OrezPolicyEngine()
) {
    fun decide(input: String, context: OrezAgentContext): OrezAgentDecision {
        if (OrezSubtitleRequest.isRequested(input) && !OrezSubtitleRequest.isDownloadChain(input) &&
            (context.selectedMedia == null || !OrezSubtitleRequest.matchesSelection(input, context.selectedMedia))) {
            return OrezAgentDecision(message = "Select the requested playable video in MangaLens before generating subtitles.")
        }
        val plan = planner.plan(input, context) ?: return if (OrezSubtitleRequest.isRequested(input))
            OrezAgentDecision(message = "Choose one explicit playable source or download one source before generating subtitles.")
            else OrezAgentDecision(continueToBrain = true)
        return decidePlan(plan, context)
    }

    fun decidePlan(plan: OrezTaskPlan, context: OrezAgentContext): OrezAgentDecision {
        // Discard proposed authority. A model-suggested plan has no right to widen app scope.
        val captured = plan.copy(authorization = OrezTaskAuthorization(
            origin = context.origin,
            explicitUserRequest = context.explicitUserRequest,
            chapterIds = setOfNotNull(context.activeChapterId),
            urls = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).findAll(plan.objective)
                .map { it.value.trimEnd('.', ',', ')', ']', '!', '?') }.toSet() + listOfNotNull(context.activeUrl),
            translation = context.translationOptions.copy(targetLanguage = plan.steps.firstOrNull {
                it.call.name != "generate_subtitles" && it.call.capability == OrezCapability.TRANSLATION
            }?.call?.arguments?.get("targetLanguage") ?: context.translationOptions.targetLanguage).normalized(),
            selectedMedia = context.selectedMedia?.captured()?.takeIf { plan.steps.any { it.call.name == "inspect_selected_media" } },
            subtitle = OrezSubtitleRequest.options(plan.objective, context.subtitleOptions.let { options ->
                if (context.selectedMedia?.hasProviderCaptionCandidate() == true && OrezSubtitleContract.isLegacy(options.normalized()))
                    options.copy(style = "natural", pipeline = com.mangalens.ui.video.SubtitlePipeline.SOURCE_TRANSLATION,
                        translationPolicy = "mlkit-dialogue-v1", capturedStyle = com.mangalens.core.translation.TranslationStyleProfile.NATURAL)
                else options
            }).takeIf {
                plan.steps.any { it.call.name in setOf("inspect_selected_media", "inspect_downloaded_media", "generate_subtitles") }
            }
        ))
        val invalid = runCatching {
            require(captured.steps.isNotEmpty()) { "The task has no executable steps." }
            if (captured.steps.size > 1 || OrezDurablePlanRules.supports(captured)) OrezDurablePlanRules.validate(captured)
            captured.steps.forEach { OrezToolRegistry().validate(it.call) }
        }.exceptionOrNull()
        if (invalid != null) return OrezAgentDecision(
            plan = captured.copy(status = OrezTaskStatus.FAILED),
            message = invalid.message ?: "Invalid tool request"
        )

        val verdicts = plan.steps.map { step -> step to policy.evaluate(step.call, context) }
        val denied = verdicts.firstOrNull { !it.second.allowed }
        if (denied != null) {
            return OrezAgentDecision(
                plan = captured.copy(status = OrezTaskStatus.FAILED),
                message = denied.second.reason.ifBlank { "That action is blocked by Orez safety policy." },
                continueToBrain = false
            )
        }

        val approval = verdicts.firstOrNull { it.second.requiresApproval }
        if (approval != null) {
            return OrezAgentDecision(
                plan = captured.copy(status = OrezTaskStatus.WAITING_APPROVAL),
                message = approval.second.reason,
                continueToBrain = false,
                requiresApproval = true
            )
        }

        val first = plan.steps.firstOrNull()?.call
            ?: return OrezAgentDecision(continueToBrain = true)

        return OrezAgentDecision(
            plan = captured.copy(status = OrezTaskStatus.RUNNING),
            immediateRoute = first.route,
            routeValue = first.arguments["value"].orEmpty(),
            message = buildString {
                append("Orez plan #")
                append(plan.id.take(8))
                append(": ")
                append(first.summary)
                if (plan.steps.size > 1) append(" • ").append(plan.steps.size).append(" steps")
                append(".")
            },
            continueToBrain = first.route == null && !OrezDurablePlanRules.supports(captured)
        )
    }
}

