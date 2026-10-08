package com.mangalens.orez.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface OrezToolResult {
    data class Completed(val outputs: Map<String, String>) : OrezToolResult
    data class Pending(val reason: String, val needsResume: Boolean = false) : OrezToolResult
    data class Failed(val reason: String) : OrezToolResult
    data class Cancelled(val reason: String) : OrezToolResult
}

fun interface OrezDurableTools {
    suspend fun execute(step: OrezPlanStep, requestId: String): OrezToolResult
}

object OrezDurablePlanRules {
    const val MAX_STEPS = 8

    fun validate(plan: OrezTaskPlan) {
        require(plan.objective.length <= 16_384) { "Task request is too long." }
        require(plan.id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid task identity" }
        require(plan.steps.size in 1..MAX_STEPS) { "Orez accepts up to $MAX_STEPS downloads in one task." }
        require(plan.steps.map { it.index } == plan.steps.indices.toList()) { "Task steps must have ordered unique indices." }
        require(plan.steps.all { it.call.name == "enqueue_download" }) {
            "Durable multi-step execution currently supports downloads. Other app tools remain single actions."
        }
        plan.steps.forEach { OrezToolRegistry().validate(it.call) }
        val completed = plan.steps.takeWhile { it.status == OrezStepStatus.COMPLETED }.size
        require(plan.steps.drop(completed).none { it.status == OrezStepStatus.COMPLETED }) { "Completed steps must form a prefix." }
        plan.steps.take(completed).forEach { step ->
            require(step.outputs["downloadId"] == requestId(plan.id, step.index) && !step.outputs["destination"].isNullOrBlank()) {
                "Completed steps require matching persisted transfer evidence."
            }
        }
    }

    // Step zero retains the identity used by older single-download task journals.
    fun requestId(taskId: String, index: Int) = if (index == 0) "orez-$taskId" else "orez-$taskId-step-$index"
}

/** Checkpoint-before-effect executor. Replays observe the same native transfer identity. */
class OrezTaskExecutor(private val store: OrezTaskStore, private val tools: OrezDurableTools) {
    sealed interface Result {
        data class Completed(val plan: OrezTaskPlan) : Result
        data class Pending(val reason: String, val needsResume: Boolean) : Result
        data class Failed(val reason: String) : Result
        data object Cancelled : Result
        data object AlreadyFinished : Result
    }

    suspend fun run(taskId: String): Result {
        var current = store.load(taskId) ?: return Result.Failed("Task journal is missing.")
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                current = store.load(taskId) ?: return Result.Failed("Task journal is missing.")
                if (current.status == OrezTaskStatus.CANCELLED) return Result.Cancelled
                if (current.status in setOf(OrezTaskStatus.COMPLETED, OrezTaskStatus.FAILED)) return Result.AlreadyFinished
                OrezDurablePlanRules.validate(current)
                // Validate every step before the first side effect, not only the next one.
                require(current.steps.all { step -> OrezPolicyEngine().evaluate(step.call, OrezAgentContext()).let {
                    it.allowed && !it.requiresApproval
                } }) { "This task needs authorization before execution." }
                val next = current.steps.firstOrNull { it.status != OrezStepStatus.COMPLETED }
                if (next == null) {
                    val complete = current.copy(status = OrezTaskStatus.COMPLETED)
                    return if (store.checkpoint(complete)) Result.Completed(complete) else Result.Cancelled
                }
                require(next.status !in setOf(OrezStepStatus.FAILED, OrezStepStatus.BLOCKED)) { "The task stopped at a failed step." }
                current = current.copy(status = OrezTaskStatus.RUNNING,
                    steps = current.steps.map { if (it.index == next.index) it.copy(status = OrezStepStatus.RUNNING) else it })
                if (!store.checkpoint(current)) return Result.Cancelled
                if (store.load(taskId)?.status == OrezTaskStatus.CANCELLED) return Result.Cancelled
                val outcome = tools.execute(next, OrezDurablePlanRules.requestId(taskId, next.index))
                currentCoroutineContext().ensureActive()
                if (store.load(taskId)?.status == OrezTaskStatus.CANCELLED) return Result.Cancelled
                when (outcome) {
                    is OrezToolResult.Completed -> {
                        require(outcome.outputs["downloadId"] == OrezDurablePlanRules.requestId(taskId, next.index)) {
                            "Tool result belongs to another transfer."
                        }
                        require(!outcome.outputs["destination"].isNullOrBlank()) { "Download completion requires a published destination." }
                        current = current.copy(steps = current.steps.map {
                            if (it.index == next.index) it.copy(status = OrezStepStatus.COMPLETED, outputs = outcome.outputs) else it
                        })
                        if (!store.checkpoint(current)) return Result.Cancelled
                    }
                    is OrezToolResult.Pending -> {
                        if (!store.checkpoint(current, if (outcome.needsResume) OrezTaskStatus.WAITING else OrezTaskStatus.RUNNING,
                                outcome.reason.take(300))) return Result.Cancelled
                        return Result.Pending(outcome.reason, outcome.needsResume)
                    }
                    is OrezToolResult.Cancelled -> {
                        store.checkpoint(current, OrezTaskStatus.CANCELLED, outcome.reason.take(300))
                        return Result.Cancelled
                    }
                    is OrezToolResult.Failed -> return fail(current, next.index, outcome.reason)
                }
            }
        } catch (cancelled: CancellationException) {
            // Android stopping the worker is not a user cancellation. Keep the running checkpoint.
            throw cancelled
        } catch (failure: Exception) {
            return fail(current, current.steps.firstOrNull { it.status != OrezStepStatus.COMPLETED }?.index,
                failure.message ?: "Task execution failed.")
        }
    }

    private suspend fun fail(plan: OrezTaskPlan, index: Int?, reason: String): Result {
        val failed = plan.copy(status = OrezTaskStatus.FAILED, steps = plan.steps.map {
            if (it.index == index) it.copy(status = OrezStepStatus.FAILED) else it
        })
        return if (store.checkpoint(failed, error = reason.take(300))) Result.Failed(reason) else Result.Cancelled
    }
}
