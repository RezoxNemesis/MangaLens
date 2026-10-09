package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrezNativeWorkflowTest {
    private val chapterId = "a".repeat(32)
    private val fingerprint = "b".repeat(64)
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
    private fun nativePlan() = OrezTaskPlan(objective = "Translate this chapter into English",
        authorization = OrezTaskAuthorization(OrezTrustOrigin.USER, true, setOf(chapterId),
            translation = OrezTranslationOptions(targetLanguage = "en")), steps = listOf(
            OrezPlanStep(0, OrezToolRegistry().call("inspect_saved_chapter", mapOf("chapterId" to chapterId))),
            OrezPlanStep(1, OrezToolRegistry().call("translate_saved_chapter", mapOf("targetLanguage" to "en")),
                dependsOn = setOf(0), references = mapOf(
                    "chapterId" to OrezOutputReference(0, OrezOutputField.CHAPTER_ID),
                    "sourceFingerprint" to OrezOutputReference(0, OrezOutputField.SOURCE_FINGERPRINT)))))
    private fun inspect(id: String) = mapOf("requestId" to id, "chapterId" to chapterId,
        "sourceFingerprint" to fingerprint, "pageCount" to "2", "title" to "Selected chapter")
    private fun translation(id: String) = inspect(id) + mapOf("translationTaskId" to "c".repeat(32),
        "generation" to "1".repeat(32), "targetLanguage" to "en",
        "status" to "COMPLETED", "completedPages" to "2", "destination" to "chapter-translation:${"c".repeat(32)}")
    @Test fun savedChapterInspectionIsAnExplicitNativeReadTool() {
        val call = runCatching {
            OrezToolRegistry().call("inspect_saved_chapter", mapOf("chapterId" to "a".repeat(32)))
        }.getOrNull()
        assertNotNull("A selected saved chapter needs a native tool rather than a navigation handoff", call)
        assertEquals(OrezToolRisk.READ_ONLY, call!!.risk)
        assertEquals(OrezCapability.LIBRARY, call.capability)
        assertNull(call.route)
    }

    @Test fun dependentNativeStepsResolveOnlyVerifiedTypedOutputsAndSurviveReplay() = runTest {
        val store = OrezTaskStore(Journal()); val plan = nativePlan(); store.checkpoint(plan)
        val seen = mutableListOf<Int>(); var stopped = false
        val tools = OrezDurableTools { step, id ->
            seen += step.index
            if (step.index == 0) OrezToolResult.Completed(inspect(id)) else {
                assertEquals(chapterId, step.call.arguments["chapterId"])
                assertEquals(fingerprint, step.call.arguments["sourceFingerprint"])
                if (!stopped) { stopped = true; throw CancellationException("process stopped after native start") }
                OrezToolResult.Completed(translation(id))
            }
        }
        try { OrezTaskExecutor(store, tools).run(plan.id); fail("Expected durable interruption") }
        catch (_: CancellationException) { }
        assertEquals(OrezOutputKind.SAVED_CHAPTER, store.load(plan.id)!!.steps[0].outputKind)
        assertTrue(OrezTaskExecutor(store, tools).run(plan.id) is OrezTaskExecutor.Result.Completed)
        assertEquals(listOf(0, 1, 1), seen)
        assertEquals(OrezOutputKind.CHAPTER_TRANSLATION, store.load(plan.id)!!.steps[1].outputKind)
    }

    @Test fun runtimeCreatesARealNativeChainAndCapturesUserScope() {
        val decision = OrezAgentRuntime().decide("Translate this chapter into English", OrezAgentContext(
            hasActiveChapter = true, activeChapterId = chapterId,
            translationOptions = OrezTranslationOptions(targetLanguage = "hi", ocrScript = "JAPANESE")))
        assertFalse(decision.continueToBrain)
        assertNull(decision.immediateRoute)
        assertEquals(listOf("inspect_saved_chapter", "translate_saved_chapter"), decision.plan!!.steps.map { it.call.name })
        assertEquals(setOf(chapterId), decision.plan!!.authorization!!.chapterIds)
        assertEquals("en", decision.plan!!.authorization!!.translation!!.targetLanguage)
        assertEquals("JAPANESE", decision.plan!!.authorization!!.translation!!.ocrScript)
    }

    @Test fun aNativeResultForAnotherChapterCannotCompleteTheStep() = runTest {
        val store = OrezTaskStore(Journal()); val plan = nativePlan(); store.checkpoint(plan)
        var count = 0
        assertTrue(OrezTaskExecutor(store, OrezDurableTools { _, id ->
            count++; OrezToolResult.Completed(inspect(id) + ("chapterId" to "d".repeat(32)))
        }).run(plan.id) is OrezTaskExecutor.Result.Failed)
        assertEquals(1, count)
        assertEquals(OrezStepStatus.PENDING, store.load(plan.id)!!.steps[1].status)
    }

    @Test fun untrustedCapturedOriginCannotBecomeDefaultUserAuthorityAfterRestart() = runTest {
        val store = OrezTaskStore(Journal()); val plan = nativePlan().let {
            it.copy(authorization = it.authorization!!.copy(origin = OrezTrustOrigin.IMPORTED_CONTENT, explicitUserRequest = false))
        }; store.checkpoint(plan)
        var count = 0
        assertTrue(OrezTaskExecutor(store, OrezDurableTools { _, id -> count++; OrezToolResult.Completed(inspect(id)) })
            .run(plan.id) is OrezTaskExecutor.Result.Failed)
        assertEquals(0, count)
    }

    @Test fun aForwardDependencyRejectsTheWholePlanBeforeAnyToolRuns() = runTest {
        val store = OrezTaskStore(Journal()); val initial = nativePlan()
        val plan = initial.copy(steps = initial.steps.map { if (it.index == 0) it.copy(dependsOn = setOf(1)) else it })
        store.checkpoint(plan); var count = 0
        assertTrue(OrezTaskExecutor(store, OrezDurableTools { _, id -> count++; OrezToolResult.Completed(inspect(id)) })
            .run(plan.id) is OrezTaskExecutor.Result.Failed)
        assertEquals(0, count)
    }

    @Test fun partialOrHandoffEvidenceCannotCompleteNativeTranslation() = runTest {
        val store = OrezTaskStore(Journal()); val plan = nativePlan(); store.checkpoint(plan)
        assertTrue(OrezTaskExecutor(store, OrezDurableTools { step, id ->
            OrezToolResult.Completed(if (step.index == 0) inspect(id) else translation(id) + ("status" to "PARTIAL"))
        }).run(plan.id) is OrezTaskExecutor.Result.Failed)
        assertEquals(OrezStepStatus.COMPLETED, store.load(plan.id)!!.steps[0].status)
        assertEquals(OrezStepStatus.FAILED, store.load(plan.id)!!.steps[1].status)
    }

    @Test fun aReceiptCannotShrinkTheInspectedPageScopeToClaimCompletion() = runTest {
        val store = OrezTaskStore(Journal()); val plan = nativePlan(); store.checkpoint(plan)
        val result = OrezTaskExecutor(store, OrezDurableTools { step, id ->
            OrezToolResult.Completed(if (step.index == 0) inspect(id) else translation(id) +
                mapOf("pageCount" to "1", "completedPages" to "1"))
        }).run(plan.id)
        assertTrue("One translated page cannot satisfy a two-page chapter scope", result is OrezTaskExecutor.Result.Failed)
    }

    @Test fun pauseFenceBlocksLateEffectsAndResumeRetainsStableIdentities() = runTest {
        val journal = Journal(); val store = OrezTaskStore(journal); val otherStore = OrezTaskStore(journal)
        val plan = nativePlan(); store.checkpoint(plan); var calls = 0
        val result = OrezTaskExecutor(store, OrezDurableTools { _, id ->
            calls++; otherStore.pause(plan.id); OrezToolResult.Completed(inspect(id))
        }).run(plan.id)
        assertTrue(result is OrezTaskExecutor.Result.Pending && result.needsResume)
        val paused = store.load(plan.id)!!
        assertTrue(paused.pausedByUser)
        assertEquals(OrezStepStatus.RUNNING, paused.steps[0].status)
        assertFalse(store.checkpoint(plan, OrezTaskStatus.COMPLETED))
        OrezTaskExecutor(store, OrezDurableTools { _, id -> calls++; OrezToolResult.Completed(inspect(id)) }).run(plan.id)
        assertEquals(1, calls)
        val resumed = otherStore.resume(plan.id)!!
        assertEquals(2L, resumed.executionEpoch)
        assertEquals(plan.authorization, resumed.authorization)
        assertTrue(OrezTaskExecutor(store, OrezDurableTools { step, id ->
            OrezToolResult.Completed(if (step.index == 0) inspect(id) else translation(id))
        }).run(plan.id) is OrezTaskExecutor.Result.Completed)
    }

    @Test fun aWorkerFromBeforeResumeCannotAdoptOrMutateTheNewEpoch() = runTest {
        val store = OrezTaskStore(Journal()); val plan = nativePlan(); store.checkpoint(plan)
        store.pause(plan.id)
        val resumed = store.resume(plan.id)!!
        var effects = 0
        val result = OrezTaskExecutor(store, OrezDurableTools { _, _ ->
            effects++; OrezToolResult.Pending("Old worker cannot dispatch its captured epoch", needsResume = true)
        }).run(plan.id, expectedEpoch = plan.executionEpoch)
        assertEquals(OrezTaskExecutor.Result.Superseded, result)
        assertEquals(0, effects)
        assertEquals(resumed, store.load(plan.id))
    }

    @Test fun aResumedGenerationCannotBeOverwrittenByThePreviousWorker() = runTest {
        val store = OrezTaskStore(Journal()); val plan = nativePlan(); store.checkpoint(plan)
        val result = OrezTaskExecutor(store, OrezDurableTools { _, id ->
            store.pause(plan.id); store.resume(plan.id); OrezToolResult.Completed(inspect(id))
        }).run(plan.id)
        assertEquals(OrezTaskExecutor.Result.Superseded, result)
        assertEquals(OrezTaskStatus.PLANNED, store.load(plan.id)!!.status)
        assertEquals(2L, store.load(plan.id)!!.executionEpoch)
    }

    @Test fun sameEpochLateWorkerCannotEraseVerifiedStepsOrDowngradeCompletion() = runTest {
        val store = OrezTaskStore(Journal()); val plan = nativePlan(); store.checkpoint(plan)
        assertTrue(OrezTaskExecutor(store, OrezDurableTools { step, id ->
            OrezToolResult.Completed(if (step.index == 0) inspect(id) else translation(id))
        }).run(plan.id) is OrezTaskExecutor.Result.Completed)
        val finished = store.load(plan.id)!!
        assertFalse("A delayed worker snapshot cannot overwrite newer verified output", store.checkpoint(plan, OrezTaskStatus.FAILED))
        assertFalse("A delayed status callback cannot downgrade completion", store.checkpoint(finished, OrezTaskStatus.RUNNING))
        assertEquals(finished, store.load(plan.id))
    }

    @Test fun proposedPlanAuthorityCannotWidenTheChapterSelectedInAppState() {
        val proposed = nativePlan()
        val decision = OrezAgentRuntime().decidePlan(proposed, OrezAgentContext(hasActiveChapter = true, activeChapterId = "d".repeat(32)))
        assertEquals(OrezTaskStatus.FAILED, decision.plan!!.status)
        assertEquals(setOf("d".repeat(32)), decision.plan!!.authorization!!.chapterIds)
        assertNull(decision.immediateRoute)
    }

    @Test fun modelCannotSkipInspectionAndInventNativeChapterScope() {
        val proposal = """{"tool":"translate_saved_chapter","arguments":{"chapterId":"$chapterId","targetLanguage":"en"}}"""
        assertNull(OrezModelPlanDecoder().decode(proposal, "Translate this chapter into English",
            OrezAgentContext(hasActiveChapter = true, activeChapterId = chapterId)))
    }

    @Test fun nativeResumeTransitionMustNotDispatchBeforeItsNewReceiptIsCommitted() = runTest {
        val store = OrezTaskStore(Journal()); val plan = nativePlan(); store.checkpoint(plan); store.pause(plan.id)
        val transitioning = store.resume(plan.id, dispatchReady = false)!!
        assertEquals(OrezTaskStatus.WAITING, transitioning.status)
        assertTrue(transitioning.pausedByUser && transitioning.resuming)
        assertEquals(transitioning, store.load(plan.id))
        var effects = 0
        assertTrue(OrezTaskExecutor(store, OrezDurableTools { _, id -> effects++; OrezToolResult.Completed(inspect(id)) })
            .run(plan.id) is OrezTaskExecutor.Result.Pending)
        assertEquals(0, effects)
        val ready = store.finishResume(transitioning)!!
        assertEquals(OrezTaskStatus.PLANNED, ready.status)
        assertFalse(ready.pausedByUser || ready.resuming)
        assertFalse(store.checkpoint(transitioning, OrezTaskStatus.RUNNING))
        assertEquals(ready, store.load(plan.id))
    }

    @Test fun cancellationIntentSurvivesReopenWithResumeProofUntilNativeAcknowledgement() = runTest {
        val journal = Journal(); val store = OrezTaskStore(journal); val plan = nativePlan(); store.checkpoint(plan)
        store.pause(plan.id); val transition = store.resume(plan.id, dispatchReady = false)!!
        val cancel = store.beginStop(plan.id, OrezPendingControl.CANCEL)!!
        val reopened = OrezTaskStore(journal)
        assertEquals(cancel, reopened.load(plan.id))
        assertEquals(OrezTaskStatus.WAITING, cancel.status)
        assertEquals(OrezPendingControl.CANCEL, cancel.pendingControl)
        assertTrue(cancel.resuming && cancel.pausedByUser)
        assertNull(store.finishResume(transition))
        assertNull(store.resume(plan.id))
        assertFalse(store.checkpoint(plan, OrezTaskStatus.RUNNING))
        val recovered = reopened.beginStop(plan.id, OrezPendingControl.CANCEL, restoreOnly = true)!!
        val cancelled = reopened.finishStop(recovered)!!
        assertEquals(OrezTaskStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.pendingControl)
        assertFalse(cancelled.resuming)
        assertNull(reopened.beginStop(plan.id, OrezPendingControl.CANCEL, restoreOnly = true))
    }

    @Test fun ordinaryResumeFailureRetainsUnresolvedNativeGenerationProof() = runTest {
        val journal = Journal(); val store = OrezTaskStore(journal); val plan = nativePlan(); store.checkpoint(plan); store.pause(plan.id)
        val transition = store.resume(plan.id, dispatchReady = false)!!
        assertTrue(store.noteControlFailure(transition, "Scheduling failed after native commit."))
        val recovered = OrezTaskStore(journal).load(plan.id)!!
        assertTrue(recovered.resuming && recovered.pausedByUser)
        assertEquals(OrezTaskStatus.WAITING, recovered.status)
        assertEquals("Scheduling failed after native commit.", journal.row!!.lastError)
        assertEquals(transition.executionEpoch, recovered.executionEpoch)
    }
}
