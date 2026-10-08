package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

class MediaCompletenessPolicyTest {
    @Test fun emptyAndTruncatedTracksFailWithoutArbitraryFileSizeLimits() {
        assertFalse(MediaCompletenessPolicy.hasCompleteTail(600_000_000L, -1L))
        assertFalse(MediaCompletenessPolicy.hasCompleteTail(600_000_000L, 30_000_000L))
        assertTrue(MediaCompletenessPolicy.hasCompleteTail(600_000_000L, 599_960_000L))
        assertTrue(MediaCompletenessPolicy.hasCompleteTail(300_000L, 260_000L))
    }

    @Test fun longVideosCannotHideMinutesOfMissingMedia() {
        assertEquals(10_000_000L, MediaCompletenessPolicy.tailToleranceUs(7_200_000_000L))
        assertFalse(MediaCompletenessPolicy.hasCompleteTail(7_200_000_000L, 7_100_000_000L))
        assertTrue(MediaCompletenessPolicy.hasCompleteTail(7_200_000_000L, 7_199_960_000L))
    }
}
