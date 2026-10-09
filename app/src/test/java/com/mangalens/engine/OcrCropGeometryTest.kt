package com.mangalens.engine

import org.junit.Assert.*
import org.junit.Test

class OcrCropGeometryTest {
    @Test fun retriesOnlyTheWeakRegionWithNearbyContext() {
        val plan = requirePlan(OcrBox(500f, 900f, 650f, 960f), 1200, 2048, 18f)
        assertTrue(plan.crop.left < 500f && plan.crop.top < 900f)
        assertTrue(plan.crop.right > 650f && plan.crop.bottom > 960f)
        assertTrue("A small weak bubble must not upscale the full tile", plan.crop.area < 1200f * 2048f * .05f)
        assertTrue(plan.scaledWidth > plan.crop.width)
    }

    @Test fun cropContextIsClampedToTheSourceImage() {
        val plan = requirePlan(OcrBox(1f, 2f, 140f, 55f), 500, 700, 16f)
        assertEquals(0f, plan.crop.left, 0f)
        assertEquals(0f, plan.crop.top, 0f)
        assertTrue(plan.crop.right < 500f)
        assertTrue(plan.crop.bottom < 700f)
    }

    @Test fun geometryMapsBackUsingTheActualRoundedPixelScales() {
        val plan = OcrRetryPlan(OcrBox(101f, 203f, 278f, 298f), 353, 189)
        val mapped = plan.map(OcrBox(35.3f, 18.9f, 176.5f, 94.5f))
        assertEquals(118.7f, mapped.left, .001f)
        assertEquals(212.5f, mapped.top, .001f)
        assertEquals(189.5f, mapped.right, .001f)
        assertEquals(250.5f, mapped.bottom, .001f)
    }

    @Test fun invalidAndTooLargeRegionsDoNotAllocateRetryImages() {
        assertNull(planOcrRetry(OcrBox(Float.NaN, 0f, 100f, 100f), 1000, 2000, 16f))
        assertNull(planOcrRetry(OcrBox(100f, 0f, 50f, 100f), 1000, 2000, 16f))
        assertNull(planOcrRetry(OcrBox(0f, 0f, 2000f, 2000f), 2000, 2000, 16f))
    }

    @Test fun retryPixelsStayWithinThePerRegionBudget() {
        val plan = requirePlan(OcrBox(100f, 100f, 950f, 600f), 1200, 2048, 16f)
        assertTrue(plan.scaledWidth <= 1280 && plan.scaledHeight <= 1280)
        assertTrue(plan.scaledWidth.toLong() * plan.scaledHeight <= 1_000_000L)
    }

    @Test fun retriesAreBoundedAndLeaveStrongLargeTextAlone() {
        val weak = (0..7).map { OcrReading("Weak dialogue $it", "LATIN", .60f,
            OcrBox(20f, it * 80f, 220f, it * 80f + 40f), textSize = 16f) }
        val strong = OcrReading("Already clear", "LATIN", .96f, OcrBox(20f, 700f, 220f, 740f), textSize = 40f)
        val targets = chooseOcrRetryTargets(weak + strong)
        assertEquals(6, targets.size)
        assertFalse(targets.contains(8))
    }

    @Test fun emptyTileFallbackCanIncreaseResolutionWithoutExceedingItsSeparateBudget() {
        val plan = planOcrRetry(OcrBox(0f, 0f, 1000f, 2048f), 1000, 2048, 16f,
            maxPixels = 4_000_000, maxDimension = 2560)
        assertNotNull("An empty tile retains one bounded high-resolution fallback", plan)
        assertTrue(plan!!.scaleX > 1.1f && plan.scaleY > 1.1f)
        assertTrue(plan.scaledWidth <= 2560 && plan.scaledHeight <= 2560)
        assertTrue(plan.scaledWidth.toLong() * plan.scaledHeight <= 4_000_000L)
    }

    private fun requirePlan(box: OcrBox, width: Int, height: Int, textSize: Float): OcrRetryPlan {
        val plan = planOcrRetry(box, width, height, textSize)
        assertNotNull("A bounded crop should support a higher-resolution retry", plan)
        return plan!!
    }
}
