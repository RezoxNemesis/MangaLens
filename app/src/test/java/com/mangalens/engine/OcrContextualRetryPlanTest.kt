package com.mangalens.engine

import org.junit.Assert.*
import org.junit.Test

class OcrContextualRetryPlanTest {
    private fun reading(bounds: OcrBox = OcrBox(110f, 103f, 403f, 249f), source: String = "Sorry IM S0 ATE?!") =
        OcrReading(source, "LATIN", .494f, bounds, plausible = true, textSize = 40f)

    @Test fun knownUncertaintyUsesBoundedOriginalContextRatherThanTightRegionScale() {
        val plan = requireNotNull(planContextualOcrRetry(reading(), 500, 350))
        assertEquals(OcrBox(0f, 0f, 500f, 350f), plan.crop)
        assertEquals(1195, plan.pixels.outputWidth); assertEquals(836, plan.pixels.outputHeight)
        assertEquals(2.3885715f, plan.pixels.transform.a, .00001f)
        assertTrue(plan.pixels.outputWidth.toLong() * plan.pixels.outputHeight <= 1_000_000)
    }

    @Test fun clearTextDoesNotOptIntoAContextualNativePass() {
        assertNull(planContextualOcrRetry(reading(source = "Jin, do not worry."), 500, 350))
    }

    @Test fun contextCannotCaptureDetectedNeighborBubblesAndMapsToOriginalCoordinates() {
        val original = reading(OcrBox(250f, 300f, 550f, 450f))
        val right = OcrBox(610f, 290f, 870f, 460f); val below = OcrBox(260f, 510f, 540f, 680f)
        val plan = requireNotNull(planContextualOcrRetry(original, 1000, 1000, listOf(right, below)))
        assertTrue(plan.crop.right < right.left); assertTrue(plan.crop.bottom < below.top)
        assertTrue(plan.crop.intersectionArea(original.bounds) == original.bounds.area)
        val mapped = requireNotNull(plan.map(OcrBox(0f, 0f, plan.pixels.outputWidth.toFloat(), plan.pixels.outputHeight.toFloat())))
        assertEquals(plan.crop.left, mapped.left, .001f); assertEquals(plan.crop.top, mapped.top, .001f)
        assertEquals(plan.crop.right, mapped.right, .001f); assertEquals(plan.crop.bottom, mapped.bottom, .001f)
    }

    @Test fun invalidBoundsAndHugeContextCannotExceedExistingPixelOwnershipCaps() {
        assertNull(planContextualOcrRetry(reading(OcrBox(-1f, 0f, 500f, 350f)), 500, 350))
        assertNull(planContextualOcrRetry(reading(), 0, 350))
        val plan = requireNotNull(planContextualOcrRetry(reading(OcrBox(1000f, 1000f, 1300f, 1150f)), 7200, 91700))
        assertTrue(plan.pixels.outputWidth <= 1280 && plan.pixels.outputHeight <= 1280)
        assertTrue(plan.pixels.outputWidth.toLong() * plan.pixels.outputHeight <= 1_000_000)
    }
}
