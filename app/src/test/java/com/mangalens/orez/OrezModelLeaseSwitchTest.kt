package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

class OrezModelLeaseSwitchTest {
    @Test fun unchangedOwnedModelNeedsNoNativeLoadOrRelease() {
        val result = OrezModelLeaseSwitch.switch("old", "old", true,
            { fail("Unexpected native load"); false }, { fail("Unexpected lease release") }, { "old" })
        assertTrue(result.requestedLoaded)
        assertEquals("old", result.loadedPath)
    }

    @Test fun successfulSwitchClosesOnlyItsOwnLeaseBeforeLoading() {
        val calls = mutableListOf<String>()
        val result = OrezModelLeaseSwitch.switch("new", "old", true,
            { calls += "load:$it"; true }, { calls += "close-own" }, { null })
        assertEquals(listOf("close-own", "load:new"), calls)
        assertTrue(result.requestedLoaded)
        assertEquals("new", result.loadedPath)
    }

    @Test fun busyPeerRestoresPreviousLeaseWithoutTreatingCandidateAsBroken() {
        val calls = mutableListOf<String>()
        val result = OrezModelLeaseSwitch.switch("new", "old", true,
            { calls += "load:$it"; it == "old" }, { calls += "close-own" }, { "old" })
        assertEquals(listOf("close-own", "load:new", "load:old"), calls)
        assertFalse(result.requestedLoaded)
        assertEquals("old", result.loadedPath)
        assertTrue(result.busy)
    }

    @Test fun nativeFailureRestoresPreviousModelAndRemainsHonestAboutRequestedPath() {
        val result = OrezModelLeaseSwitch.switch("new", "old", true,
            { it == "old" }, {}, { null })
        assertFalse(result.requestedLoaded)
        assertFalse(result.busy)
        assertEquals("old", result.loadedPath)
    }

    @Test fun thrownNativeFailureAlsoRestoresPreviousLease() {
        val result = OrezModelLeaseSwitch.switch("new", "old", true,
            { if (it == "new") throw IllegalStateException("Runtime rejected candidate"); true }, {}, { null })
        assertFalse(result.requestedLoaded)
        assertEquals("old", result.loadedPath)
    }

    @Test fun missingPriorLeaseCannotClaimAnUnloadedModelIsReady() {
        var releases = 0
        val result = OrezModelLeaseSwitch.switch("new", "old", false,
            { false }, { releases++ }, { "peer" })
        assertEquals(0, releases)
        assertFalse(result.requestedLoaded)
        assertNull(result.loadedPath)
        assertTrue(result.busy)
    }

    @Test fun cancellationPropagatesAfterRestoringThePreviousLease() {
        val calls = mutableListOf<String>()
        try {
            OrezModelLeaseSwitch.switch("new", "old", true,
                { calls += it; if (it == "new") throw kotlinx.coroutines.CancellationException("Cancelled"); true }, {}, { null })
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(listOf("new", "old"), calls)
    }
}
