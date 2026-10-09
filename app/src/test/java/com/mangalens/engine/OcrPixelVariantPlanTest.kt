package com.mangalens.engine

import org.junit.Assert.*
import org.junit.Test

class OcrPixelVariantPlanTest {
    @Test fun diagnosticHypothesesStayBoundedForTheOriginalCropSizesAndLargeRegions() {
        assertEquals(6, originalPixelDiagnosticVariants.size)
        assertEquals(6, originalPixelDiagnosticVariants.map { it.label }.distinct().size)
        for ((width, height) in listOf(405 to 160, 500 to 350, 900 to 1100, 2100 to 2400)) {
            for (spec in originalPixelDiagnosticVariants) {
                val plan = planOriginalPixelVariant(width, height, spec)!!
                assertTrue(plan.outputWidth in 1..1280 && plan.outputHeight in 1..1280)
                assertTrue(plan.outputWidth.toLong() * plan.outputHeight <= 1_000_000L)
                for (corner in corners(width.toFloat(), height.toFloat())) {
                    val mapped = plan.transform.map(corner.x, corner.y)
                    assertTrue("$spec $mapped in ${plan.outputWidth}x${plan.outputHeight}",
                        mapped.x >= -.001f && mapped.y >= -.001f &&
                            mapped.x <= plan.outputWidth + .001f && mapped.y <= plan.outputHeight + .001f)
                }
            }
        }
    }

    @Test fun rotationAddsMarginsRatherThanClippingTheGlyphsAtACropEdge() {
        val plan = planOriginalPixelVariant(400, 160, OcrPixelVariantSpec("rotated", 2f, clockwiseDegrees = 3f))!!
        assertTrue(plan.outputWidth > 800)
        assertTrue(plan.transform.map(0f, 0f).x > plan.transform.map(0f, 160f).x)
    }

    @Test fun deslantMovesLowerPixelsWithoutCroppingTheUpperOrLowerLetterStrokes() {
        val plan = planOriginalPixelVariant(500, 350, OcrPixelVariantSpec("deslant", 2f, shearX = .18f))!!
        assertTrue(plan.outputWidth > 1000)
        assertTrue(plan.transform.map(200f, 320f).x > plan.transform.map(200f, 20f).x)
    }

    @Test fun transformedLandmarksRoundTripToTheSameOriginalPixels() {
        for (spec in originalPixelDiagnosticVariants) {
            val plan = planOriginalPixelVariant(500, 350, spec)!!
            for (point in listOf(OcrPixelPoint(110f, 104f), OcrPixelPoint(405f, 251f), OcrPixelPoint(250f, 175f))) {
                val output = plan.transform.map(point.x, point.y)
                val restored = plan.transform.inverse(output.x, output.y)
                assertEquals(point.x, restored.x, .001f)
                assertEquals(point.y, restored.y, .001f)
            }
        }
    }

    @Test fun nativeBoxMappingRetainsItsTextCoverageAndClipsOnlyOutsideImageMargins() {
        for (spec in originalPixelDiagnosticVariants) {
            val plan = planOriginalPixelVariant(500, 350, spec)!!
            val source = OcrBox(110f, 104f, 405f, 251f)
            val mapped = corners(source.width, source.height).map { plan.transform.map(source.left + it.x, source.top + it.y) }
            val native = OcrBox(mapped.minOf { it.x }, mapped.minOf { it.y }, mapped.maxOf { it.x }, mapped.maxOf { it.y })
            val restored = plan.mapBounds(native)!!
            assertTrue(restored.left <= source.left + .001f && restored.top <= source.top + .001f)
            assertTrue(restored.right >= source.right - .001f && restored.bottom >= source.bottom - .001f)
            val fullCanvas = plan.mapBounds(OcrBox(-10f, -10f, plan.outputWidth + 10f, plan.outputHeight + 10f))!!
            assertEquals(OcrBox(0f, 0f, 500f, 350f), fullCanvas)
        }
    }

    @Test fun corruptGeometryOrUnboundedExperimentalParametersCannotAllocateABitmap() {
        assertNull(planOriginalPixelVariant(0, 350, originalPixelDiagnosticVariants.first()))
        assertNull(planOriginalPixelVariant(500, 350, OcrPixelVariantSpec("bad", Float.NaN)))
        assertNull(planOriginalPixelVariant(500, 350, OcrPixelVariantSpec("bad", 100f)))
        assertNull(planOriginalPixelVariant(500, 350, OcrPixelVariantSpec("bad", 2f, clockwiseDegrees = 90f)))
        assertNull(planOriginalPixelVariant(500, 350, OcrPixelVariantSpec("bad", 2f, shearX = Float.NaN)))
        assertNull(planOriginalPixelVariant(500, 350, OcrPixelVariantSpec("bad", 2f), maxPixels = 0))
        val plan = planOriginalPixelVariant(500, 350, originalPixelDiagnosticVariants.first())!!
        assertNull(plan.mapBounds(OcrBox(0f, 0f, Float.NaN, 3f)))
        assertNull(plan.mapBounds(OcrBox(-20f, -20f, -10f, -10f)))
    }

    private fun corners(width: Float, height: Float) = listOf(OcrPixelPoint(0f, 0f), OcrPixelPoint(width, 0f),
        OcrPixelPoint(0f, height), OcrPixelPoint(width, height))
}
