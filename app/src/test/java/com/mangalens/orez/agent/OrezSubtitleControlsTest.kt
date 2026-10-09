package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrezSubtitleControlsTest {
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
    private class Host(var receipt: OrezSubtitleReceipt) : OrezSubtitleHost {
        var pauses = 0; var cancels = 0; var resumes = 0
        var failAfterResume = false; var replaceDuringStop = false; var swapTaskOnResume = false
        var interruptStart = false
        var lookupEntered: CompletableDeferred<Unit>? = null; var lookupRelease: CompletableDeferred<Unit>? = null
        override suspend fun inspectSelection(selection: OrezMediaSelection, pinnedModelSha256: String?) = receipt.media
        override suspend fun inspectDownload(download: OrezSubtitleDownload, pinnedModelSha256: String?) = receipt.media
        override suspend fun start(media: OrezSubtitleSnapshot, options: OrezSubtitleOptions, requestId: String, allowReplacement: Boolean): OrezSubtitleReceipt {
            if (interruptStart) throw CancellationException("Old orchestrator interrupted")
            return receipt
        }
        override suspend fun observe(taskId: String, requestId: String, media: OrezSubtitleSnapshot, options: OrezSubtitleOptions) = receipt
        override suspend fun findOwned(requestId: String): OrezSubtitleReceipt {
            lookupEntered?.complete(Unit); lookupRelease?.await(); return receipt
        }
        override suspend fun pause(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt? {
            pauses++
            if (replaceDuringStop) { this.receipt = receipt.copy(generation = "2".repeat(32), status = OrezNativeSubtitleStatus.QUEUED); return null }
            return receipt.copy(status = OrezNativeSubtitleStatus.PAUSED).also { this.receipt = it }
        }
        override suspend fun cancel(receipt: OrezSubtitleReceipt) = receipt.copy(status = OrezNativeSubtitleStatus.CANCELLED).also { cancels++; this.receipt = it }
        override suspend fun resume(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt {
            resumes++
            this.receipt = receipt.copy(generation = "2".repeat(32), status = OrezNativeSubtitleStatus.QUEUED,
                taskId = if (swapTaskOnResume) "f".repeat(32) else receipt.taskId)
            if (failAfterResume) throw java.io.IOException("Scheduling failed after native G2 committed")
            return this.receipt
        }
    }
    private class Fixture {
        val journal = Journal(); val store = OrezTaskStore(journal)
        val selection = OrezMediaSelection("content://explicit-subtitle/control")
        val proposed = OrezAgentRuntime().decide("Generate English subtitles for this video",
            OrezAgentContext(selectedMedia = selection, subtitleOptions = OrezSubtitleOptions(threads = 2))).plan!!
        val media = OrezSubtitleSnapshot(selection.sourceId, selection, "a".repeat(64), "b".repeat(64))
        val owner = OrezDurablePlanRules.requestId(proposed.id, 1)
        val g1 = OrezSubtitleReceipt("c".repeat(32), "1".repeat(32), owner, media, proposed.authorization!!.subtitle!!,
            OrezNativeSubtitleStatus.PAUSED, 16000, 8000, 1, 1)
        val host = Host(g1)
        val plan = proposed.copy(steps = proposed.steps.map {
            if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED, outputs = media.outputs(OrezDurablePlanRules.requestId(proposed.id, 0)),
                outputKind = OrezOutputKind.MEDIA_SOURCE) else it.copy(status = OrezStepStatus.RUNNING, outputs = g1.outputs(owner))
        })
        suspend fun beginResume(): Pair<OrezTaskPlan, OrezTaskPlan> {
            store.checkpoint(plan); val original = store.pause(plan.id)!!
            return original to store.resume(plan.id, dispatchReady = false)!!
        }
    }

    @Test fun failedSchedulingAfterNativeResumeCommitRetainsRecoveryProofAndReconcilesG2() = runTest {
        val f = Fixture(); val (original, transition) = f.beginResume(); f.host.failAfterResume = true
        try { OrezSubtitleControlOperations(f.host).resume(original, transition, transition.steps[1]); fail("Expected scheduling failure") }
        catch (_: java.io.IOException) { }
        f.store.noteControlFailure(transition, "Retry scheduling")
        val reopened = OrezTaskStore(f.journal); val pending = reopened.load(f.plan.id)!!
        assertTrue(pending.resuming); assertEquals(f.g1.generation, pending.steps[1].outputs["generation"])
        val next = reopened.resume(pending.id, dispatchReady = false)!!
        val reconciled = OrezSubtitleControlOperations(f.host).resume(pending, next, next.steps[1])
        assertEquals("2".repeat(32), reopened.finishResume(reconciled)!!.steps[1].outputs["generation"])
        assertEquals(1, f.host.resumes)
    }

    @Test fun pauseAndCancelAtNativeResumeCommitGapUseExactPersistedRecoveryScope() = runTest {
        for (control in OrezPendingControl.entries) {
            val f = Fixture(); f.beginResume()
            f.host.receipt = f.g1.copy(generation = "2".repeat(32), status = OrezNativeSubtitleStatus.QUEUED)
            val reopened = OrezTaskStore(f.journal)
            val pending = reopened.beginStop(f.plan.id, control)!!
            assertTrue(pending.resuming)
            val acknowledged = OrezSubtitleControlOperations(f.host).stop(pending, pending.steps[1], control)
            val done = reopened.finishStop(acknowledged)!!
            assertEquals("2".repeat(32), done.steps[1].outputs["generation"])
            assertFalse(done.resuming); assertNull(done.pendingControl)
            assertEquals(if (control == OrezPendingControl.CANCEL) OrezNativeSubtitleStatus.CANCELLED else OrezNativeSubtitleStatus.PAUSED, f.host.receipt.status)
        }
    }

    @Test fun resumeRecoveryProofCannotControlAnotherOwnerOrSource() = runTest {
        for (bad in listOf("owner", "source", "model")) {
            val f = Fixture(); f.beginResume()
            f.host.receipt = when (bad) {
                "owner" -> f.g1.copy(ownerRequestId = "another-owner", generation = "2".repeat(32))
                "source" -> f.g1.copy(media = f.media.copy(sourceFingerprint = "f".repeat(64)), generation = "2".repeat(32))
                else -> f.g1.copy(media = f.media.copy(speechModelSha256 = "f".repeat(64)), generation = "2".repeat(32))
            }
            val pending = f.store.beginStop(f.plan.id, OrezPendingControl.CANCEL)!!
            assertTrue(runCatching { OrezSubtitleControlOperations(f.host).stop(pending, pending.steps[1], OrezPendingControl.CANCEL) }.isFailure)
            assertEquals(0, f.host.cancels)
        }
    }

    @Test fun stoppedNativeGenerationCannotBeSilentlyReplacedInAResumeReceipt() = runTest {
        val f = Fixture(); val (original, transition) = f.beginResume(); f.host.swapTaskOnResume = true
        assertTrue("Resume keeps the native task identity", runCatching {
            OrezSubtitleControlOperations(f.host).resume(original, transition, transition.steps[1])
        }.isFailure)
        assertTrue(f.store.load(f.plan.id)!!.resuming)
    }

    @Test fun aNativeStopThatLosesGenerationRaceMustRemainPendingRatherThanAcknowledgeG1() = runTest {
        val f = Fixture(); f.store.checkpoint(f.plan); f.host.replaceDuringStop = true
        val pending = f.store.beginStop(f.plan.id, OrezPendingControl.PAUSE)!!
        assertTrue("A null native stop is not proof that G1 was stopped", runCatching {
            OrezSubtitleControlOperations(f.host).stop(pending, pending.steps[1], OrezPendingControl.PAUSE)
        }.isFailure)
        assertEquals(OrezPendingControl.PAUSE, f.store.load(f.plan.id)!!.pendingControl)
        assertEquals("2".repeat(32), f.host.receipt.generation)
    }

    @Test fun delayedOldCancellationLookupCannotPauseTheSameOwnersNewResumedGeneration() = runTest {
        val f = Fixture(); f.store.checkpoint(f.plan); f.store.pause(f.plan.id)
        f.host.interruptStart = true; f.host.lookupEntered = CompletableDeferred(); f.host.lookupRelease = CompletableDeferred()
        val tools = OrezSubtitleTools(f.host, f.plan.authorization!!,
            stopOwned = { OrezTaskControlFence.stopIfRequested(f.store, f.plan.id, it, f.host) },
            mayStopOwned = { f.store.load(f.plan.id)!!.let { it.pausedByUser && !it.resuming } })
        val resolved = OrezDurablePlanRules.resolve(f.plan, f.plan.steps[1]).copy(status = OrezStepStatus.RUNNING, outputs = emptyMap())
        val old = async { try { tools.execute(resolved, f.owner); fail("Expected interruption") } catch (_: CancellationException) { } }
        f.host.lookupEntered!!.await()
        OrezTaskControlFence.mutex.withLock {
            val transition = f.store.resume(f.plan.id, dispatchReady = false)!!
            val g2 = f.g1.copy(generation = "2".repeat(32), status = OrezNativeSubtitleStatus.QUEUED); f.host.receipt = g2
            f.store.finishResume(transition.copy(steps = transition.steps.map { if (it.index == 1) it.copy(outputs = g2.outputs(f.owner)) else it }))!!
        }
        f.host.lookupRelease!!.complete(Unit); old.await()
        assertEquals(0, f.host.pauses); assertEquals(OrezNativeSubtitleStatus.QUEUED, f.host.receipt.status)
    }
}
