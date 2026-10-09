package com.mangalens.core.events

import org.junit.Assert.*
import org.junit.Test

class CommittedDownloadHintTest {
    private fun capture(task: String = "task-a", epoch: Long = 4, active: Boolean = true,
        ids: Set<String> = setOf("orez-task-a"), observed: String = "orez-task-a",
        terminal: DownloadTerminalHint? = DownloadTerminalHint.COMPLETED) =
        CommittedDownloadHint.create(task, epoch, active, ids, observed, terminal)

    @Test fun onlyCommittedTerminalStateWithCurrentOwnershipProducesAHint() {
        assertNull(capture(active = false))
        assertNull(capture(terminal = null))
        assertNull(capture(observed = "orez-other-task"))
        assertNull(capture(ids = emptySet()))
        assertNotNull(capture())
    }

    @Test fun executionGenerationAndTaskAreCapturedWithoutSourceContent() {
        val captured = capture()!!
        assertEquals(4L, captured.identity.generation)
        assertEquals(EventIdentity.task("task-a"), captured.identity.task)
        assertEquals(EventIdentity.source("orez-task-a"), captured.identity.source)
        assertEquals(EventIdentity.owner("orez-task-a"), captured.identity.owner)
        assertFalse(captured.toString().contains("orez-task-a"))
    }

    @Test fun aResumedGenerationCannotWakeThePreviousCapturedTaskObserver() {
        val previous = capture()!!
        val current = capture(epoch = 5)!!
        val bus = AppEventBus()
        bus.subscribe(TaskEventFilter(previous.identity, setOf(AppEventType.DOWNLOAD_COMPLETE))).use { subscription ->
            assertEquals(0, bus.publish(current))
            assertNull(subscription.poll())
        }
    }

    @Test fun malformedOwnershipCannotPublishURLsPrivatePathsOrUnboundedIdentities() {
        assertNull(capture(task = "https://private.test/?cookie=secret"))
        assertNull(capture(observed = "/data/private/file", ids = setOf("/data/private/file")))
        assertNull(capture(epoch = -1))
        assertNull(capture(ids = (1..9).map { "id-$it" }.toSet(), observed = "id-1"))
    }

    @Test fun publisherFailureHintHasNoUntrustedReasonOrFreeFormPayload() {
        val failed = capture(terminal = DownloadTerminalHint.FAILED)!!
        assertEquals(AppEventType.DOWNLOAD_FAILED, failed.type)
        assertEquals(capture()!!.identity, failed.identity)
    }
}
