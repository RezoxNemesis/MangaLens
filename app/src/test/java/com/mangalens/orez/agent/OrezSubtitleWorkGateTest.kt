package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrezSubtitleWorkGateTest {
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
    private class Fixture {
        val journal = Journal(); val store = OrezTaskStore(journal)
        val selected = OrezMediaSelection("content://explicit-subtitle/headless", headers = mapOf("Authorization" to "captured"))
        val proposed = OrezAgentRuntime().decide("Generate English subtitles for this video",
            OrezAgentContext(selectedMedia = selected, subtitleOptions = OrezSubtitleOptions(threads = 2))).plan!!
        val owner = OrezDurablePlanRules.requestId(proposed.id, 1)
        val media = OrezSubtitleSnapshot(selected.sourceId, selected, "a".repeat(64), "b".repeat(64))
        val g1 = OrezSubtitleReceipt("c".repeat(32), "1".repeat(32), owner, media, proposed.authorization!!.subtitle!!,
            OrezNativeSubtitleStatus.RUNNING, 16000, 8000, 1, 1)
        var native = g1; var controls = 0; var replaceOnStop = false; var throwOnStop = false; var ignoreStop = false
        val plan = proposed.copy(status = OrezTaskStatus.RUNNING, steps = proposed.steps.map {
            if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED, outputs = media.outputs(OrezDurablePlanRules.requestId(proposed.id, 0)),
                outputKind = OrezOutputKind.MEDIA_SOURCE)
            else it.copy(status = OrezStepStatus.RUNNING, outputs = g1.outputs(owner))
        })
        suspend fun pending(control: OrezPendingControl, resumeGap: Boolean = false): OrezTaskPlan {
            store.checkpoint(plan)
            if (resumeGap) { store.pause(plan.id); store.resume(plan.id, dispatchReady = false) }
            return store.beginStop(plan.id, control)!!
        }
        suspend fun allow(receipt: OrezSubtitleReceipt = native) = OrezSubtitleWorkGate.allow(store, plan.id, receipt,
            stop = { captured, control ->
                controls++
                if (throwOnStop) throw java.io.IOException("Journal unavailable")
                if (replaceOnStop) { native = native.copy(generation = "2".repeat(32), status = OrezNativeSubtitleStatus.QUEUED); null }
                else if (ignoreStop || native.generation != captured.generation) null
                else native.copy(status = if (control == OrezPendingControl.CANCEL) OrezNativeSubtitleStatus.CANCELLED else OrezNativeSubtitleStatus.PAUSED).also { native = it }
            }, current = { taskId -> native.takeIf { native.taskId == taskId } })
    }

    @Test fun restoredPauseAndCancelStopExactNativeReceiptAndClearDurablePendingIntent() = runTest {
        for (control in OrezPendingControl.entries) {
            val f = Fixture(); f.pending(control)
            assertFalse("Pending native stop must gate further PCM/inference work", f.allow())
            assertEquals(1, f.controls)
            assertEquals(if (control == OrezPendingControl.CANCEL) OrezNativeSubtitleStatus.CANCELLED else OrezNativeSubtitleStatus.PAUSED, f.native.status)
            val reopened = OrezTaskStore(f.journal).load(f.plan.id)!!
            assertNull(reopened.pendingControl); assertFalse(reopened.resuming)
            assertEquals(f.g1.generation, reopened.steps[1].outputs["generation"])
            assertEquals(if (control == OrezPendingControl.CANCEL) OrezTaskStatus.CANCELLED else OrezTaskStatus.WAITING, reopened.status)
        }
    }

    @Test fun resumeCommitGapProofCanStopOnlySameOwnedG2AndPersistsItsGeneration() = runTest {
        for (control in OrezPendingControl.entries) {
            val f = Fixture(); f.pending(control, resumeGap = true)
            f.native = f.g1.copy(generation = "2".repeat(32), status = OrezNativeSubtitleStatus.QUEUED)
            assertFalse(f.allow())
            val saved = f.store.load(f.plan.id)!!
            assertEquals("2".repeat(32), saved.steps[1].outputs["generation"])
            assertFalse(saved.resuming); assertNull(saved.pendingControl)
            assertEquals(1, f.controls)
        }
    }

    @Test fun sourceOwnerModelConfigTaskAndUnprovenGenerationCannotGainHeadlessStopRights() = runTest {
        for (mismatch in listOf("owner", "source", "descriptor", "model", "config", "task", "generation", "unverifiable")) {
            val f = Fixture(); f.pending(OrezPendingControl.CANCEL)
            f.native = when (mismatch) {
                "owner" -> f.g1.copy(ownerRequestId = "another-owner")
                "source" -> f.g1.copy(media = f.media.copy(sourceFingerprint = "f".repeat(64)))
                "descriptor" -> f.g1.copy(media = f.media.copy(descriptor = f.selected.copy(headers = emptyMap())))
                "model" -> f.g1.copy(media = f.media.copy(speechModelSha256 = "f".repeat(64)))
                "config" -> f.g1.copy(options = f.g1.options.copy(threads = 3))
                "task" -> f.g1.copy(taskId = "d".repeat(32))
                "generation" -> f.g1.copy(generation = "2".repeat(32))
                else -> f.g1.copy(media = f.media.copy(verifiable = false))
            }
            assertTrue("Invalid $mismatch intent must leave another native generation alone", f.allow())
            assertEquals("Invalid $mismatch dispatched a stop", 0, f.controls)
            assertEquals(OrezPendingControl.CANCEL, f.store.load(f.plan.id)!!.pendingControl)
        }
    }

    @Test fun replacementThatWinsNativeStoreLockCannotAcknowledgeOrStopNewGeneration() = runTest {
        val f = Fixture(); f.pending(OrezPendingControl.PAUSE); f.replaceOnStop = true
        assertTrue(f.allow())
        val saved = f.store.load(f.plan.id)!!
        assertEquals(OrezPendingControl.PAUSE, saved.pendingControl)
        assertEquals(f.g1.generation, saved.steps[1].outputs["generation"])
        assertEquals("2".repeat(32), f.native.generation)
        assertEquals(OrezNativeSubtitleStatus.QUEUED, f.native.status)
    }

    @Test fun alreadyStoppedSameGenerationCanAcknowledgeNativeStopCommitGapIdempotently() = runTest {
        val f = Fixture(); f.pending(OrezPendingControl.PAUSE)
        f.native = f.g1.copy(status = OrezNativeSubtitleStatus.PAUSED); f.ignoreStop = true
        assertFalse(f.allow())
        assertNull(f.store.load(f.plan.id)!!.pendingControl)
        assertTrue(f.allow()) // No pending intent; the native store still independently fences inactive work.
    }

    @Test fun missingStopAcknowledgementKeepsPendingAndDoesNotInventPausedStatus() = runTest {
        val f = Fixture(); f.pending(OrezPendingControl.PAUSE); f.ignoreStop = true
        assertFalse("An accepted pause gates work while its acknowledgement is pending", f.allow())
        assertEquals(OrezNativeSubtitleStatus.RUNNING, f.native.status)
        assertEquals(OrezPendingControl.PAUSE, f.store.load(f.plan.id)!!.pendingControl)
    }

    @Test fun nativeJournalFailureRetainsPendingIntentForAnotherBoundedRecoveryPass() = runTest {
        val f = Fixture(); f.pending(OrezPendingControl.CANCEL); f.throwOnStop = true
        assertFalse("A failed accepted stop must not silently permit more inference", f.allow())
        assertEquals(OrezPendingControl.CANCEL, f.store.load(f.plan.id)!!.pendingControl)
        assertEquals(OrezNativeSubtitleStatus.RUNNING, f.native.status)
    }

    @Test fun changedExecutionEpochReloadsLatestIntentBeforeDispatchingNativeControl() = runTest {
        val f = Fixture(); f.pending(OrezPendingControl.PAUSE)
        val stopping = f.store.load(f.plan.id)!!
        f.native = f.g1.copy(status = OrezNativeSubtitleStatus.PAUSED)
        f.store.finishStop(stopping.copy(steps = stopping.steps.map { if (it.index == 1) it.copy(outputs = f.native.outputs(f.owner)) else it }))!!
        val resumed = f.store.resume(f.plan.id, dispatchReady = false)!!
        f.native = f.g1.copy(generation = "2".repeat(32), status = OrezNativeSubtitleStatus.QUEUED)
        f.store.finishResume(resumed.copy(steps = resumed.steps.map { if (it.index == 1) it.copy(outputs = f.native.outputs(f.owner)) else it }))!!
        assertTrue(f.allow(f.g1))
        assertEquals(0, f.controls)
        assertEquals(OrezTaskStatus.PLANNED, f.store.load(f.plan.id)!!.status)
        assertEquals("2".repeat(32), f.store.load(f.plan.id)!!.steps[1].outputs["generation"])
    }
}
