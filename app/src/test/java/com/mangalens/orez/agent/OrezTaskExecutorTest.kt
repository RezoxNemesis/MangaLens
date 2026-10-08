package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrezTaskExecutorTest {
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive(): Flow<List<OrezTaskEntity>> = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }

    private fun plan() = OrezAgentPlanner().plan(
        "Download these at 720p https://example.com/a.mp4 and https://example.com/b.mp4", OrezAgentContext())!!

    private fun complete(id: String) = OrezToolResult.Completed(mapOf("downloadId" to id, "destination" to "content://media/$id"))

    @Test fun interruptionAfterAnEffectReusesItsIdentityAndSkipsVerifiedSteps() = runTest {
        val store = OrezTaskStore(Journal()); val plan = plan(); store.checkpoint(plan)
        val effects = mutableSetOf<String>(); val seen = mutableListOf<String>()
        var interrupted = false
        val tools = OrezDurableTools { step, id ->
            seen += id; effects += id
            if (step.index == 1 && !interrupted) { interrupted = true; throw CancellationException("process stopped") }
            complete(id)
        }
        try { OrezTaskExecutor(store, tools).run(plan.id); fail("Expected interruption") }
        catch (_: CancellationException) { }
        val checkpoint = store.load(plan.id)!!
        assertEquals(OrezStepStatus.COMPLETED, checkpoint.steps[0].status)
        assertEquals(OrezStepStatus.RUNNING, checkpoint.steps[1].status)
        assertTrue(OrezTaskExecutor(store, tools).run(plan.id) is OrezTaskExecutor.Result.Completed)
        assertEquals(2, effects.size)
        assertEquals(listOf("orez-${plan.id}", "orez-${plan.id}-step-1", "orez-${plan.id}-step-1"), seen)
        assertTrue(store.load(plan.id)!!.steps.all { it.status == OrezStepStatus.COMPLETED })
        assertTrue(OrezTaskExecutor(store, tools).run(plan.id) is OrezTaskExecutor.Result.AlreadyFinished)
        assertEquals(3, seen.size)
    }

    @Test fun cancellationDuringToolExecutionCannotStartTheNextStepOrResurrectTheTask() = runTest {
        val store = OrezTaskStore(Journal()); val plan = plan(); store.checkpoint(plan)
        var calls = 0
        val result = OrezTaskExecutor(store, OrezDurableTools { _, id ->
            calls++; store.checkpoint(store.load(plan.id)!!, OrezTaskStatus.CANCELLED); complete(id)
        }).run(plan.id)
        assertEquals(OrezTaskExecutor.Result.Cancelled, result)
        assertEquals(1, calls)
        assertEquals(OrezTaskStatus.CANCELLED, store.load(plan.id)!!.status)
    }

    @Test fun malformedLaterStepPreventsEverySideEffect() = runTest {
        val store = OrezTaskStore(Journal()); val initial = plan()
        val invalid = initial.copy(steps = initial.steps.map {
            if (it.index == 1) it.copy(call = it.call.copy(arguments = mapOf("value" to "file:///private"))) else it
        })
        store.checkpoint(invalid); var calls = 0
        assertTrue(OrezTaskExecutor(store, OrezDurableTools { _, id -> calls++; complete(id) }).run(invalid.id)
            is OrezTaskExecutor.Result.Failed)
        assertEquals(0, calls)
    }

    @Test fun pausedStepRetainsCompletedEvidenceAndResumesInPlace() = runTest {
        val store = OrezTaskStore(Journal()); val plan = plan(); store.checkpoint(plan)
        val seen = mutableListOf<Int>(); var paused = true
        val tools = OrezDurableTools { step, id ->
            seen += step.index
            if (step.index == 1 && paused) OrezToolResult.Pending("Paused", needsResume = true) else complete(id)
        }
        val result = OrezTaskExecutor(store, tools).run(plan.id) as OrezTaskExecutor.Result.Pending
        assertTrue(result.needsResume)
        assertEquals(OrezTaskStatus.WAITING, store.load(plan.id)!!.status)
        assertEquals("orez-${plan.id}", store.load(plan.id)!!.steps[0].outputs["downloadId"])
        paused = false
        assertTrue(OrezTaskExecutor(store, tools).run(plan.id) is OrezTaskExecutor.Result.Completed)
        assertEquals(listOf(0, 1, 1), seen)
    }

    @Test fun anotherTransfersResultCannotCompleteThisTask() = runTest {
        val store = OrezTaskStore(Journal()); val plan = plan(); store.checkpoint(plan)
        val result = OrezTaskExecutor(store, OrezDurableTools { _, _ -> complete("another-transfer") }).run(plan.id)
        assertTrue(result is OrezTaskExecutor.Result.Failed)
        assertEquals(OrezStepStatus.FAILED, store.load(plan.id)!!.steps[0].status)
        assertEquals(OrezStepStatus.PENDING, store.load(plan.id)!!.steps[1].status)
    }

    @Test fun nativeFailurePreservesEarlierCompletionForAnExplicitRetry() = runTest {
        val store = OrezTaskStore(Journal()); val plan = plan(); store.checkpoint(plan)
        val tools = OrezDurableTools { step, id -> if (step.index == 1) OrezToolResult.Failed("Source expired") else complete(id) }
        assertTrue(OrezTaskExecutor(store, tools).run(plan.id) is OrezTaskExecutor.Result.Failed)
        val failed = store.load(plan.id)!!
        assertEquals(OrezStepStatus.COMPLETED, failed.steps[0].status)
        assertEquals(OrezStepStatus.FAILED, failed.steps[1].status)
        store.checkpoint(failed.copy(status = OrezTaskStatus.PLANNED, steps = failed.steps.map {
            if (it.status == OrezStepStatus.FAILED) it.copy(status = OrezStepStatus.PENDING) else it
        }))
        val retried = mutableListOf<Int>()
        assertTrue(OrezTaskExecutor(store, OrezDurableTools { step, id -> retried += step.index; complete(id) }).run(plan.id)
            is OrezTaskExecutor.Result.Completed)
        assertEquals(listOf(1), retried)
    }
}
