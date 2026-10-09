package com.mangalens.orez.agent

import kotlinx.coroutines.test.runTest
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import com.mangalens.orez.agent.OrezChapterTools.Companion.outputs
import org.junit.Assert.*
import org.junit.Test

class OrezChapterToolsTest {
    private val chapter = OrezChapterSnapshot("a".repeat(32), "Saved chapter", 2, "b".repeat(64))
    private val options = OrezTranslationOptions(targetLanguage = "en", ocrScript = "JAPANESE")
    private val authorization = OrezTaskAuthorization(OrezTrustOrigin.USER, true, setOf(chapter.chapterId), translation = options)
    private val step = OrezPlanStep(1, OrezToolRegistry().call("translate_saved_chapter", mapOf(
        "chapterId" to chapter.chapterId, "sourceFingerprint" to chapter.sourceFingerprint, "targetLanguage" to "en")))
    private class Host(var chapter: OrezChapterSnapshot) : OrezChapterHost {
        var starts = 0; var pauses = 0; var receipt: OrezChapterReceipt? = null
        var interruptStart = false
        var findEntered: CompletableDeferred<Unit>? = null
        var findRelease: CompletableDeferred<Unit>? = null
        override suspend fun inspect(chapterId: String) = chapter.takeIf { it.chapterId == chapterId }
        override suspend fun start(chapter: OrezChapterSnapshot, options: OrezTranslationOptions, requestId: String, allowReplacement: Boolean): OrezChapterReceipt {
            starts++
            if (interruptStart) throw CancellationException("old worker stopped while the newer generation became active")
            return receipt ?: OrezChapterReceipt("c".repeat(32), "1".repeat(32), requestId,
                chapter, options, OrezNativeChapterStatus.QUEUED, 0).also { receipt = it }
        }
        override suspend fun observe(taskId: String) = receipt?.takeIf { it.taskId == taskId }
        override suspend fun findOwned(requestId: String): OrezChapterReceipt? {
            findEntered?.complete(Unit); findRelease?.await()
            return receipt?.takeIf { it.ownerRequestId == requestId }
        }
        override suspend fun pause(receipt: OrezChapterReceipt) = receipt.copy(status = OrezNativeChapterStatus.PAUSED).also { this.receipt = it; pauses++ }
        override suspend fun resume(receipt: OrezChapterReceipt) = receipt.copy(status = OrezNativeChapterStatus.QUEUED).also { this.receipt = it }
        override suspend fun cancel(receipt: OrezChapterReceipt) = receipt.copy(status = OrezNativeChapterStatus.CANCELLED).also { this.receipt = it }
    }

    @Test fun nativeStartReturnsDurableReceiptAndReplayObservesWithoutRestarting() = runTest {
        val host = Host(chapter); val tools = OrezChapterTools(host, authorization)
        val first = tools.execute(step, "orez-user-task-step-1") as OrezToolResult.Pending
        assertEquals("c".repeat(32), first.outputs["translationTaskId"])
        assertFalse(first.needsResume)
        val replay = step.copy(status = OrezStepStatus.RUNNING, outputs = first.outputs)
        host.receipt = host.receipt!!.copy(status = OrezNativeChapterStatus.COMPLETED, completedPages = 2, translatedRegions = 5)
        val finished = tools.execute(replay, "orez-user-task-step-1") as OrezToolResult.Completed
        assertEquals(1, host.starts)
        assertEquals("COMPLETED", finished.outputs["status"])
        assertEquals("2", finished.outputs["completedPages"])
    }

    @Test fun alteredGenerationOrOwnerFailsInsteadOfAttachingAnotherRequestsResult() = runTest {
        for (alterOwner in listOf(true, false)) {
            val host = Host(chapter); val tools = OrezChapterTools(host, authorization)
            val pending = tools.execute(step, "orez-user-task-step-1") as OrezToolResult.Pending
            host.receipt = host.receipt!!.let { if (alterOwner) it.copy(ownerRequestId = "another-user-request")
                else it.copy(generation = "2".repeat(32)) }
            assertTrue(tools.execute(step.copy(status = OrezStepStatus.RUNNING, outputs = pending.outputs), "orez-user-task-step-1") is OrezToolResult.Failed)
            assertEquals(1, host.starts)
        }
    }

    @Test fun changedSourcesStopBeforeNativeSideEffects() = runTest {
        val host = Host(chapter.copy(sourceFingerprint = "d".repeat(64)))
        assertTrue(OrezChapterTools(host, authorization).execute(step, "orez-user-task-step-1") is OrezToolResult.Failed)
        assertEquals(0, host.starts)
    }

    @Test fun lostOrPartialNativeOutputNeverBecomesCompleted() = runTest {
        val host = Host(chapter); val tools = OrezChapterTools(host, authorization)
        val pending = tools.execute(step, "orez-user-task-step-1") as OrezToolResult.Pending
        host.receipt = host.receipt!!.copy(status = OrezNativeChapterStatus.PARTIAL, completedPages = 1)
        assertTrue(tools.execute(step.copy(outputs = pending.outputs), "orez-user-task-step-1") is OrezToolResult.Failed)
        host.receipt = null
        assertTrue(tools.execute(step.copy(outputs = pending.outputs), "orez-user-task-step-1") is OrezToolResult.Failed)
        assertEquals(1, host.starts)
    }

    @Test fun pauseRacingNativeStartStopsTheOwnedGeneration() = runTest {
        val host = Host(chapter); var checks = 0
        val result = OrezChapterTools(host, authorization) { ++checks == 1 }.execute(step, "orez-user-task-step-1")
        assertTrue(result is OrezToolResult.Pending && result.needsResume)
        assertEquals(1, host.starts)
        assertEquals(1, host.pauses)
        assertEquals(OrezNativeChapterStatus.PAUSED, host.receipt!!.status)
    }

    @Test fun providerReceiptUsesNativeHex32GenerationThroughExecutorValidation() = runTest {
        val plan = OrezAgentRuntime().decide("Translate this chapter into English", OrezAgentContext(
            hasActiveChapter = true, activeChapterId = chapter.chapterId, translationOptions = options)).plan!!
        val journal = object : OrezTaskDao {
            var row: OrezTaskEntity? = null
            override fun observeActive() = flowOf(listOfNotNull(row))
            override suspend fun get(id: String) = row?.takeIf { it.id == id }
            override suspend fun upsert(task: OrezTaskEntity) { row = task }
            override suspend fun pruneFinished(before: Long) = Unit
        }
        val store = OrezTaskStore(journal); store.checkpoint(plan)
        val host = Host(chapter).apply {
            receipt = OrezChapterReceipt("c".repeat(32), "1".repeat(32), OrezDurablePlanRules.requestId(plan.id, 1),
                chapter, options, OrezNativeChapterStatus.COMPLETED, 2, 5)
        }
        assertTrue(OrezTaskExecutor(store, OrezChapterTools(host, plan.authorization!!)).run(plan.id) is OrezTaskExecutor.Result.Completed)
        assertEquals("1".repeat(32), store.load(plan.id)!!.steps[1].outputs["generation"])
    }

    @Test fun oldWorkerCleanupMustNotPauseTheSameOwnersNewResumedGeneration() = runTest {
        val host = Host(chapter).apply {
            interruptStart = true
            receipt = OrezChapterReceipt("c".repeat(32), "2".repeat(32), "orez-user-task-step-1", chapter,
                options, OrezNativeChapterStatus.RUNNING, 0)
        }
        var executing = true
        val tools = OrezChapterTools(host, authorization, mayStopOwned = { false }, isExecuting = { executing.also { executing = false } })
        try { tools.execute(step.copy(status = OrezStepStatus.RUNNING), "orez-user-task-step-1"); fail("Expected interruption") }
        catch (_: CancellationException) { }
        assertEquals(0, host.pauses)
        assertEquals("2".repeat(32), host.receipt!!.generation)
        assertEquals(OrezNativeChapterStatus.RUNNING, host.receipt!!.status)
    }

    @Test fun cleanupRechecksStopRightsAfterSuspendedLookupAndConcurrentResume() = runTest {
        val plan = OrezAgentRuntime().decide("Translate this chapter into English", OrezAgentContext(
            hasActiveChapter = true, activeChapterId = chapter.chapterId, translationOptions = options)).plan!!
        val journal = object : OrezTaskDao {
            var row: OrezTaskEntity? = null
            override fun observeActive() = flowOf(listOfNotNull(row))
            override suspend fun get(id: String) = row?.takeIf { it.id == id }
            override suspend fun upsert(task: OrezTaskEntity) { row = task }
            override suspend fun pruneFinished(before: Long) = Unit
        }
        val store = OrezTaskStore(journal); val requestId = OrezDurablePlanRules.requestId(plan.id, 1)
        val g1 = OrezChapterReceipt("c".repeat(32), "1".repeat(32), requestId, chapter, options, OrezNativeChapterStatus.RUNNING, 0)
        val running = plan.copy(steps = plan.steps.map { if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED,
            outputs = chapter.outputs(OrezDurablePlanRules.requestId(plan.id, 0)), outputKind = OrezOutputKind.SAVED_CHAPTER)
            else it.copy(status = OrezStepStatus.RUNNING, outputs = g1.outputs(requestId)) })
        store.checkpoint(running); store.pause(plan.id)
        val host = Host(chapter).apply {
            receipt = g1; interruptStart = true; findEntered = CompletableDeferred(); findRelease = CompletableDeferred()
        }
        val tools = OrezChapterTools(host, plan.authorization!!,
            stopOwned = { OrezTaskControlFence.stopIfRequested(store, plan.id, it, host) },
            mayStopOwned = { store.load(plan.id)!!.let { it.pausedByUser && !it.resuming } }, isExecuting = { true })
        val oldCleanup = async {
            try { tools.execute(step.copy(status = OrezStepStatus.RUNNING), requestId); fail("Expected old worker interruption") }
            catch (_: CancellationException) { }
        }
        host.findEntered!!.await()
        OrezTaskControlFence.mutex.withLock {
            val transition = store.resume(plan.id, dispatchReady = false)!!
            val g2 = g1.copy(generation = "2".repeat(32)); host.receipt = g2
            val ready = store.finishResume(transition.copy(steps = transition.steps.map {
                if (it.index == 1) it.copy(outputs = g2.outputs(requestId)) else it
            }))!!
            store.checkpoint(ready, OrezTaskStatus.RUNNING)
        }
        host.findRelease!!.complete(Unit); oldCleanup.await()
        assertEquals(0, host.pauses)
        assertEquals("2".repeat(32), host.receipt!!.generation)
        assertEquals(OrezNativeChapterStatus.RUNNING, host.receipt!!.status)
        assertEquals("2".repeat(32), store.load(plan.id)!!.steps[1].outputs["generation"])
    }
}
