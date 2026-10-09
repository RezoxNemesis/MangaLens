package com.mangalens.orez

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OrezModelTransferOwnerTest {
    @Test fun latestPauseSurvivesAnOlderQueuedPauseAcknowledgement() {
        val fence = OrezModelTransferPauseFence()
        val first = fence.request()
        val latest = fence.request()
        fence.acknowledge(first)
        assertTrue("older Pause1 commit must retain the later Pause3 fence", fence.isPaused)
        fence.acknowledge(latest)
        assertFalse(fence.isPaused)
    }

    @Test fun duplicateOlderAcknowledgementCannotReleaseANewPause() {
        val fence = OrezModelTransferPauseFence()
        val first = fence.request()
        fence.acknowledge(first)
        val latest = fence.request()
        fence.acknowledge(first)
        assertTrue("older command completion released a newer immediate Pause", fence.isPaused)
        fence.acknowledge(latest)
        assertFalse(fence.isPaused)
    }

    @Test fun lateProgressFailureAndCancellationCannotOverwriteResumedTransfer() {
        val persisted = Persistence()
        val old = persisted.owner()
        old.begin("old") { persisted.status = "old downloading" }
        old.pause { persisted.status = "paused" }
        // A reconstructed manager and worker read the same persisted identity after resume.
        val resumed = persisted.owner()
        assertTrue(resumed.beginIfIdle("new") { persisted.status = "new downloading" })
        assertFalse(old.update("old") { persisted.status = "old progress" })
        assertFalse(old.finish("old") { persisted.status = "old failure / cancelled" })
        assertEquals(OrezModelTransferIdentity("new", true), resumed.identity())
        assertEquals("new downloading", persisted.status)
    }

    @Test fun pauseInvalidatesAnUncancelledOldReadBeforeItCanWriteOrQuarantine() {
        val persisted = Persistence()
        val worker = persisted.owner()
        worker.begin("old")
        persisted.owner().pause()
        try { worker.checkpoint("old"); fail("paused generation must be rejected") }
        catch (_: CancellationException) { }
        assertFalse(worker.update("old") { fail("paused transfer mutated a partial") })
    }

    @Test fun retriesKeepTheirOwnIdentityButOnlyItsFinalResultCanClearIt() {
        val persisted = Persistence()
        val worker = persisted.owner()
        worker.begin("same")
        assertTrue(worker.update("same") { persisted.status = "waiting for retry" })
        assertFalse(persisted.owner().beginIfIdle("other") { fail("duplicate enqueue") })
        assertTrue(worker.finish("same") { persisted.status = "complete" })
        assertFalse(worker.isCurrent("same"))
        assertEquals("complete", persisted.status)
    }

    private class Persistence {
        private val lock = Any()
        private var identity = OrezModelTransferIdentity(null, false)
        var status: String = ""
        fun owner() = OrezModelTransferOwner<() -> Unit>(lock, { identity }, { next, mutation -> identity = next; mutation() }, {})
    }
}
