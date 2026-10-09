package com.mangalens.orez.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrezControlRecoveryBatchTest {
    @Test fun failingFirst128ControlsCannotStarveLaterCapturedControls() = runTest {
        val captured = (0..128).map { "task-$it" }; val attempted = mutableListOf<String>()
        OrezControlRecoveryBatch.run(captured) { id ->
            attempted += id
            if (id != "task-128") throw java.io.IOException("Pending native control still cannot commit")
        }
        assertEquals(captured, attempted)
    }

    @Test fun duplicateAndFailingIdsAreAttemptedOnceWithoutRetryLoops() = runTest {
        val attempted = mutableListOf<String>()
        OrezControlRecoveryBatch.run(listOf("failing", "failing", "later", "later")) { id ->
            attempted += id
            if (id == "failing") throw IllegalStateException("Retain scope proof")
        }
        assertEquals(listOf("failing", "later"), attempted)
    }

    @Test fun workerStopLeavesRemainingCapturedControlsForNextRestoration() = runTest {
        val attempted = mutableListOf<String>()
        try {
            OrezControlRecoveryBatch.run(listOf("first", "stopped", "later")) { id ->
                attempted += id
                if (id == "stopped") throw CancellationException("WorkManager stopped the recovery worker")
            }
            fail("Expected worker cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf("first", "stopped"), attempted)
    }
}
