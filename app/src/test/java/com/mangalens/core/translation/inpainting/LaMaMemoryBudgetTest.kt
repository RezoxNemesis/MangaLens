package com.mangalens.core.translation.inpainting

import org.junit.Assert.*
import org.junit.Test

class LaMaMemoryBudgetTest {
    @Test fun aSmallDefaultAndroidJavaHeapCannotAllocateThePinnedWeightsPretendingToBeReady() {
        assertFalse(LaMaMemoryBudget.permits(192L * 1024 * 1024, 2_000_000_000, 100_000_000, false, true, true))
    }
    @Test fun exactEstimatedHeadroomBoundaryIsAcceptedButOneByteBelowDeclines() {
        val java = LaMaReconstructionPin.MODEL_BYTES + LaMaMemoryBudget.JAVA_SCRATCH
        val system = LaMaReconstructionPin.MODEL_BYTES + LaMaMemoryBudget.NATIVE_SESSION_ESTIMATE + 100L
        assertTrue(LaMaMemoryBudget.permits(java, system, 100, false, true, true))
        assertFalse(LaMaMemoryBudget.permits(java - 1, system, 100, false, true, true))
        assertFalse(LaMaMemoryBudget.permits(java, system - 1, 100, false, true, true))
    }
    @Test fun actualLowMemoryFlagAndInvalidAvailabilityAlwaysDecline() {
        assertFalse(LaMaMemoryBudget.permits(Long.MAX_VALUE, Long.MAX_VALUE, 0, true, false, false))
        assertFalse(LaMaMemoryBudget.permits(-1, Long.MAX_VALUE, 0, false, false, false))
    }
    @Test fun loadedWeightsStillRequireScratchAndEstimatedNativeSessionHeadroom() {
        assertFalse(LaMaMemoryBudget.permits(LaMaMemoryBudget.JAVA_SCRATCH - 1, Long.MAX_VALUE, 0, false, false, true))
        assertFalse(LaMaMemoryBudget.permits(LaMaMemoryBudget.JAVA_SCRATCH, LaMaMemoryBudget.NATIVE_SESSION_ESTIMATE - 1, 0, false, false, true))
    }
}
