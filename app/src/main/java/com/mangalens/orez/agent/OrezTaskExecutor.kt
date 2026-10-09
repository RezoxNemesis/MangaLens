package com.mangalens.orez.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface OrezToolResult {
    data class Completed(val outputs: Map<String, String>) : OrezToolResult
    data class Pending(val reason: String, val needsResume: Boolean = false, val outputs: Map<String, String> = emptyMap()) : OrezToolResult
    data class Failed(val reason: String) : OrezToolResult
    data class Cancelled(val reason: String) : OrezToolResult
}

fun interface OrezDurableTools {
    suspend fun execute(step: OrezPlanStep, requestId: String): OrezToolResult
}

/** Checkpoint-before-effect executor. Replays retain the same native identity and captured scope. */
class OrezTaskExecutor(private val store: OrezTaskStore, private val tools: OrezDurableTools) {
    sealed interface Result {
        data class Completed(val plan: OrezTaskPlan) : Result
        data class Pending(val reason: String, val needsResume: Boolean) : Result
        data class Failed(val reason: String) : Result
        data object Cancelled : Result
        data object AlreadyFinished : Result
        data object Superseded : Result
    }

    suspend fun run(taskId: String, expectedEpoch: Long? = null): Result {
        var current = store.load(taskId) ?: return Result.Failed("Task journal is missing.")
        val epoch = expectedEpoch ?: current.executionEpoch
        if (current.executionEpoch != epoch) return Result.Superseded
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                current = store.load(taskId) ?: return Result.Failed("Task journal is missing.")
                if (current.status == OrezTaskStatus.CANCELLED) return Result.Cancelled
                if (current.executionEpoch != epoch) return Result.Superseded
                if (current.pausedByUser || current.resuming) return Result.Pending("Task paused. Resume to continue.", true)
                if (current.status in setOf(OrezTaskStatus.COMPLETED, OrezTaskStatus.FAILED)) return Result.AlreadyFinished
                OrezDurablePlanRules.validate(current)
                // Validate every step before the first side effect, not only the next one.
                require(current.steps.all { step -> OrezPolicyEngine().evaluate(step.call,
                    current.authorization?.context() ?: OrezAgentContext()).let {
                    it.allowed && !it.requiresApproval
                } }) { "This task needs authorization before execution." }
                val next = current.steps.firstOrNull { it.status != OrezStepStatus.COMPLETED }
                if (next == null) {
                    val complete = current.copy(status = OrezTaskStatus.COMPLETED)
                    return if (store.checkpoint(complete)) Result.Completed(complete) else stopped(taskId, epoch)
                }
                require(next.status !in setOf(OrezStepStatus.FAILED, OrezStepStatus.BLOCKED)) { "The task stopped at a failed step." }
                val resolved = OrezDurablePlanRules.resolve(current, next)
                current = current.copy(status = OrezTaskStatus.RUNNING,
                    steps = current.steps.map { if (it.index == next.index) it.copy(status = OrezStepStatus.RUNNING) else it })
                if (!store.checkpoint(current)) return stopped(taskId, epoch)
                if (!store.isExecuting(taskId, epoch)) return stopped(taskId, epoch)
                val outcome = tools.execute(resolved, OrezDurablePlanRules.requestId(taskId, next.index))
                currentCoroutineContext().ensureActive()
                if (!store.isExecuting(taskId, epoch)) return stopped(taskId, epoch)
                when (outcome) {
                    is OrezToolResult.Completed -> {
                        OrezDurablePlanRules.validateReceipt(current, resolved, outcome.outputs, completed = true)
                        current = current.copy(steps = current.steps.map {
                            if (it.index == next.index) it.copy(status = OrezStepStatus.COMPLETED, outputs = outcome.outputs,
                                outputKind = OrezDurablePlanRules.outputKind(it.call.name)) else it
                        })
                        if (!store.checkpoint(current)) return stopped(taskId, epoch)
                    }
                    is OrezToolResult.Pending -> {
                        if (outcome.outputs.isNotEmpty()) {
                            OrezDurablePlanRules.validateReceipt(current, resolved, outcome.outputs, completed = false)
                            current = current.copy(steps = current.steps.map {
                                if (it.index == next.index) it.copy(outputs = outcome.outputs) else it
                            })
                        }
                        if (!store.checkpoint(current, if (outcome.needsResume) OrezTaskStatus.WAITING else OrezTaskStatus.RUNNING,
                                outcome.reason.take(300))) return stopped(taskId, epoch)
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
        return if (store.checkpoint(failed, error = reason.take(300))) Result.Failed(reason) else stopped(plan.id, plan.executionEpoch)
    }

    private suspend fun stopped(taskId: String, epoch: Long): Result {
        val latest = store.load(taskId) ?: return Result.Failed("Task journal is missing.")
        return when {
            latest.status == OrezTaskStatus.CANCELLED -> Result.Cancelled
            latest.pausedByUser -> Result.Pending("Task paused. Resume to continue.", true)
            latest.executionEpoch != epoch -> Result.Superseded
            else -> Result.AlreadyFinished
        }
    }
}
