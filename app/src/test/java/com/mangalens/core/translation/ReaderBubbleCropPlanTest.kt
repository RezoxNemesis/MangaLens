package com.mangalens.core.translation

import com.mangalens.core.translation.memory.MemoryRegionBounds
import com.mangalens.core.translation.memory.MemorySourceProof
import org.junit.Assert.*
import org.junit.Test

/** Prepared pure bounds/budget controls; this does not execute an Android Bitmap decoder. */
class ReaderBubbleCropPlanTest {
    private fun source(width: Int, height: Int, bounds: MemoryRegionBounds = MemoryRegionBounds(0, 0, width, height)) =
        MemorySourceProof("chapter-one", 0, "/private/chapters/original.png", "a".repeat(64), width, height, bounds)

    @Test fun smallActualOriginalRegionKeepsItsExactCoordinatesAndNeedsNoUpscale() {
        val actual = source(1001, 1009, MemoryRegionBounds(4, 6, 93, 143))
        val plan = ReaderBubbleCropPlan.create(actual)
        assertEquals(actual.bounds, plan.bounds)
        assertEquals(1, plan.sample)
        assertEquals(89, plan.predictedWidth); assertEquals(137, plan.predictedHeight)
    }

    @Test fun previewPixelLimitIsIndependentOfOriginalSourcePageSize() {
        val plan = ReaderBubbleCropPlan.create(source(2000, 2000))
        assertEquals(2, plan.sample)
        assertEquals(1000, plan.predictedWidth); assertEquals(1000, plan.predictedHeight)
        assertEquals(1_000_000L, plan.predictedWidth.toLong() * plan.predictedHeight)
    }

    @Test fun roundedNonDivisibleDecodeDimensionsRemainInsideBothPreviewBudgets() {
        val plan = ReaderBubbleCropPlan.create(source(2561, 2559))
        assertEquals(4, plan.sample)
        assertEquals(641, plan.predictedWidth); assertEquals(640, plan.predictedHeight)
        assertTrue(plan.predictedWidth.toLong() * plan.predictedHeight <= 1_000_000L)
        assertTrue(maxOf(plan.predictedWidth, plan.predictedHeight) <= 1280)
    }

    @Test fun veryThinOriginalRegionStillHonoursTheLongestEdgeLimit() {
        val plan = ReaderBubbleCropPlan.create(source(100_000, 1))
        assertEquals(128, plan.sample)
        assertEquals(782, plan.predictedWidth); assertEquals(1, plan.predictedHeight)
        assertTrue(plan.sample and (plan.sample - 1) == 0)
    }

    @Test fun maximumAcceptedOriginalAreaHasABoundedPreviewWithoutIntegerOverflow() {
        val plan = ReaderBubbleCropPlan.create(source(100_000, 1000))
        assertEquals(128, plan.sample)
        assertEquals(782, plan.predictedWidth); assertEquals(8, plan.predictedHeight)
        assertTrue(plan.predictedWidth.toLong() * plan.predictedHeight <= 1_000_000L)
    }

    @Test fun malformedOriginalMetadataCannotBecomeARegionDecoderRequest() {
        val invalid = listOf(source(100_001, 1), source(100_000, 1001), source(10, 10, MemoryRegionBounds(-1, 0, 3, 4)),
            source(10, 10, MemoryRegionBounds(0, 0, 11, 4)), source(10, 10, MemoryRegionBounds(3, 3, 3, 4)),
            source(10, 10).copy(sourceSha256 = "not-a-hash"), source(10, 10).copy(pageIndex = -1))
        invalid.forEach { assertTrue(runCatching { ReaderBubbleCropPlan.create(it) }.isFailure) }
    }

    @Test fun actualDecoderOutputMustBeQualifiedAgainRatherThanTrustingPredictedDimensions() {
        val plan = ReaderBubbleCropPlan.create(source(2000, 2000))
        assertTrue(plan.acceptsActualDecode(1000, 1000))
        assertFalse(plan.acceptsActualDecode(1001, 1000))
        assertFalse(plan.acceptsActualDecode(1281, 1))
        assertFalse(plan.acceptsActualDecode(0, 1))
        assertFalse(plan.acceptsActualDecode(Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
