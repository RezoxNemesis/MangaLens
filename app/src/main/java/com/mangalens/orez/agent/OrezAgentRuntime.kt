package com.mangalens.orez.agent

class OrezAgentRuntime(
    private val planner: OrezAgentPlanner = OrezAgentPlanner(),
    private val policy: OrezPolicyEngine = OrezPolicyEngine()
) {
    fun decide(input: String, context: OrezAgentContext): OrezAgentDecision {
        val plan = planner.plan(input, context)
            ?: return OrezAgentDecision(continueToBrain = true)
        return decidePlan(plan, context)
    }

    fun decidePlan(plan: OrezTaskPlan, context: OrezAgentContext): OrezAgentDecision {
        val invalid = runCatching {
            require(plan.steps.isNotEmpty()) { "The task has no executable steps." }
            if (plan.steps.size > 1 || plan.steps.first().call.name == "enqueue_download") OrezDurablePlanRules.validate(plan)
            plan.steps.forEach { OrezToolRegistry().validate(it.call) }
        }.exceptionOrNull()
        if (invalid != null) return OrezAgentDecision(
            plan = plan.copy(status = OrezTaskStatus.FAILED),
            message = invalid.message ?: "Invalid tool request"
        )

        val verdicts = plan.steps.map { step -> step to policy.evaluate(step.call, context) }
        val denied = verdicts.firstOrNull { !it.second.allowed }
        if (denied != null) {
            return OrezAgentDecision(
                plan = plan.copy(status = OrezTaskStatus.FAILED),
                message = denied.second.reason.ifBlank { "That action is blocked by Orez safety policy." },
                continueToBrain = false
            )
        }

        val approval = verdicts.firstOrNull { it.second.requiresApproval }
        if (approval != null) {
            return OrezAgentDecision(
                plan = plan.copy(status = OrezTaskStatus.WAITING_APPROVAL),
                message = approval.second.reason,
                continueToBrain = false,
                requiresApproval = true
            )
        }

        val first = plan.steps.firstOrNull()?.call
            ?: return OrezAgentDecision(continueToBrain = true)

        return OrezAgentDecision(
            plan = plan.copy(status = OrezTaskStatus.RUNNING),
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
            continueToBrain = first.route == null
        )
    }
}

