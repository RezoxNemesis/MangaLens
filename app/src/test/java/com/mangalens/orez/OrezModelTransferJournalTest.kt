package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class OrezModelTransferJournalTest {
    @Test fun durablePendingRequestWithoutWorkCanReplayItsExactIdentity() = withJournal { file ->
        val pending = identity(pending = true)
        OrezModelTransferJournal(file).persist(pending)
        val restarted = OrezModelTransferJournal(file).identity
        assertEquals(pending, restarted)
        assertEquals(OrezModelTransferRecovery.REQUEUE_PENDING, restarted.recovery(emptySet()))
        assertEquals(OrezModelTransferRecovery.NONE, restarted.recovery(setOf(pending.id!!)))
    }

    @Test fun oldOwnerAndDifferentDurableWorkBecomeIdleInsteadOfStuckDownloading() = withJournal { file ->
        val old = identity()
        OrezModelTransferJournal(file).persist(old)
        val restarted = OrezModelTransferJournal(file).identity
        assertEquals(OrezModelTransferRecovery.MISSING_WORK, restarted.recovery(setOf(UUID.randomUUID().toString())))
    }

    @Test fun durableAcceptedOwnerWinsOverOldAsyncPreferenceSnapshot() = withJournal { file ->
        val oldPreferences = identity()
        val accepted = identity(pending = true)
        val journal = OrezModelTransferJournal(file, oldPreferences)
        journal.persist(accepted)
        // WorkManager accepts G2 only after persist returns; an old preferences image is irrelevant.
        val durableWork = setOf(accepted.id!!)
        val restarted = OrezModelTransferJournal(file, oldPreferences)
        assertEquals(accepted, restarted.identity)
        assertEquals(OrezModelTransferRecovery.NONE, restarted.identity.recovery(durableWork))
    }

    @Test fun persistedPauseDoesNotRevivePendingOrRunningWorkAfterRestart() = withJournal { file ->
        val pending = identity(pending = true)
        val journal = OrezModelTransferJournal(file)
        journal.persist(pending)
        journal.persist(pending.copy(active = false, pendingEnqueue = false))
        val restarted = OrezModelTransferJournal(file, pending)
        assertFalse(restarted.identity.active)
        assertEquals(OrezModelTransferRecovery.NONE, restarted.identity.recovery(emptySet()))
        assertEquals(OrezModelTransferRecovery.NONE, restarted.identity.recovery(setOf(pending.id!!)))
    }

    @Test fun failedControlCommitRetainsOldIdentityAndCannotPublishNewWork() = withJournal { file ->
        val old = identity(active = false)
        OrezModelTransferJournal(file).persist(old)
        val journal = OrezModelTransferJournal(file, beforeCommit = { throw IOException("disk failure") })
        var workPublished = false
        try {
            journal.persist(identity(pending = true))
            workPublished = true
            fail("control commit should fail")
        } catch (_: IOException) { }
        assertFalse(workPublished)
        assertEquals(old, journal.identity)
        assertEquals(old, OrezModelTransferJournal(file).identity)
    }

    @Test fun enqueueAcknowledgementIsDurableAndMissingAcceptedWorkBecomesActionable() = withJournal { file ->
        val pending = identity(pending = true)
        val journal = OrezModelTransferJournal(file)
        journal.persist(pending)
        val owner = OrezModelTransferOwner<() -> Unit>(Any(), { journal.identity }, { next, mutation -> journal.persist(next); mutation() }, {})
        assertTrue(owner.enqueued(pending.id!!))
        val restarted = OrezModelTransferJournal(file).identity
        assertFalse(restarted.pendingEnqueue)
        assertEquals(OrezModelTransferRecovery.MISSING_WORK, restarted.recovery(emptySet()))
        assertEquals(OrezModelTransferRecovery.NONE, restarted.recovery(setOf(pending.id)))
    }

    @Test fun interruptedEnqueueAcknowledgementNeverReplacesAnExistingDurableRequest() = withJournal { file ->
        val pending = identity(pending = true)
        OrezModelTransferJournal(file).persist(pending)
        val interrupted = OrezModelTransferJournal(file, beforeCommit = { throw IOException("killed before acknowledgement") })
        try { interrupted.persist(pending.copy(pendingEnqueue = false)); fail("commit should fail") }
        catch (_: IOException) { }
        val restarted = OrezModelTransferJournal(file).identity
        assertTrue(restarted.pendingEnqueue)
        assertEquals("a fresh database row prevents duplicate REPLACE", OrezModelTransferRecovery.NONE,
            restarted.recovery(setOf(pending.id!!)))
    }

    private fun identity(active: Boolean = true, pending: Boolean = false) =
        OrezModelTransferIdentity(UUID.randomUUID().toString(), active, pending, "LITE")

    private fun withJournal(action: (File) -> Unit) {
        val directory = Files.createTempDirectory("orez-transfer-journal").toFile()
        try { action(File(directory, "transfer.control")) } finally { directory.deleteRecursively() }
    }
}
