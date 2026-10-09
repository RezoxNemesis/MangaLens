package com.mangalens.engine

import org.junit.Assert.*
import org.junit.Test

class OcrSourceResolutionPolicyTest {
    private val proof = OcrSourceResolutionProof("a".repeat(32), "b".repeat(32), "c".repeat(32), 2,
        "/private/chapters/source.jpg", "d".repeat(64), 720, 9170, 180, 2292)
    private val request = OcrSourceResolutionRequest(OcrBox(50f, 1500f, 175f, 1587.5f), 180, 2292)

    @Test fun readsOriginalCropRatherThanUpscalingLostWholePagePixels() {
        val plan = requireNotNull(planOriginalSourceCrop(proof, request))
        assertEquals(OcrBox(200f, 6001f, 700f, 6352f), plan.sourceCrop)
        assertEquals(1, plan.decodeSample)
        assertEquals(500, plan.expectedDecodeWidth); assertEquals(351, plan.expectedDecodeHeight)
        assertEquals(proof, plan.proof)
    }

    @Test fun inverseMappingUsesActualDecoderDimensionsIncludingRoundingAndPageOffset() {
        val plan = requireNotNull(planOriginalSourceCrop(proof, request))
        val result = requireNotNull(plan.mapToPage(OcrBox(25f, 21f, 480f, 330f), 500, 351))
        assertEquals(56.25f, result.left, .001f)
        assertEquals(170f, result.right, .001f)
        assertEquals((6001f + 21f) * 2292f / 9170f, result.top, .001f)
        assertEquals((6001f + 330f) * 2292f / 9170f, result.bottom, .001f)
        val half = requireNotNull(plan.mapToPage(OcrBox(0f, 0f, 250f, 175f), 250, 175))
        assertEquals(50f, half.left, .001f); assertEquals(175f, half.right, .001f)
        assertEquals(6001f * 2292f / 9170f, half.top, .001f)
        assertEquals(6352f * 2292f / 9170f, half.bottom, .001f)
    }

    @Test fun originalDecoderRegionIsBoundedBeforeAnyBitmapAllocation() {
        val large = proof.copy(originalWidth = 10000, originalHeight = 10000, decodedWidth = 1000, decodedHeight = 1000)
        val plan = requireNotNull(planOriginalSourceCrop(large, OcrSourceResolutionRequest(OcrBox(0f, 0f, 1000f, 1000f), 1000, 1000)))
        assertTrue(plan.decodeSample > 1)
        assertTrue(plan.expectedDecodeWidth <= 1280 && plan.expectedDecodeHeight <= 1280)
        assertTrue(plan.expectedDecodeWidth.toLong() * plan.expectedDecodeHeight <= 1_000_000L)
        assertEquals(0, plan.decodeSample and (plan.decodeSample - 1))
    }

    @Test fun invalidCapturedDimensionsHashAndUnboundRequestCannotCreateACrop() {
        for (bad in listOf(proof.copy(sourceSha256 = "not-a-hash"), proof.copy(originalHeight = 0),
            proof.copy(decodedWidth = 721), proof.copy(pageIndex = -1), proof.copy(sourcePath = "relative.jpg")))
            assertNull(bad.toString(), planOriginalSourceCrop(bad, request))
        assertNull(planOriginalSourceCrop(proof, request.copy(decodedHeight = 2291)))
        assertNull(planOriginalSourceCrop(proof, request.copy(crop = OcrBox(-1f, 0f, 20f, 10f))))
        assertNull(planOriginalSourceCrop(proof, request.copy(crop = OcrBox(170f, 0f, 181f, 10f))))
        assertNull(planOriginalSourceCrop(proof, request.copy(crop = OcrBox(Float.NaN, 0f, 1f, 1f))))
    }

    @Test fun unchangedResolutionStaysOnTheExistingCropPathAndOutOfBoundsNativeGeometryIsClipped() {
        val full = proof.copy(decodedWidth = 720, decodedHeight = 9170)
        assertNull(planOriginalSourceCrop(full, OcrSourceResolutionRequest(OcrBox(200f, 6000f, 700f, 6350f), 720, 9170)))
        val plan = requireNotNull(planOriginalSourceCrop(proof, request))
        assertNull(plan.mapToPage(OcrBox(-20f, -20f, -10f, -1f), 500, 351))
        val clipped = requireNotNull(plan.mapToPage(OcrBox(-20f, -20f, 600f, 500f), 500, 351))
        assertEquals(50f, clipped.left, .001f); assertEquals(175f, clipped.right, .001f)
        assertTrue(clipped.top >= 0f && clipped.bottom <= 2292f)
    }

    @Test fun mappedPageGeometryReturnsToItsActualTileAndKeepsClippedRoundingInsideIt() {
        assertEquals(OcrBox(50f, 8f, 170f, 108f), originalPageBoxToTile(OcrBox(50f, 1800f, 170f, 1900f), 1792, 180, 500))
        assertEquals(OcrBox(0f, 0f, 180f, 500f), originalPageBoxToTile(OcrBox(-.1f, 1791.9f, 180.1f, 2292.1f), 1792, 180, 500))
        assertNull(originalPageBoxToTile(OcrBox(10f, 10f, 50f, 100f), 1792, 180, 500))
        assertNull(originalPageBoxToTile(OcrBox(10f, 1800f, 50f, 1900f), -1, 180, 500))
    }

    @Test fun zeroBasedFirstChapterPageHasAQualifiedOriginalCrop() {
        assertNotNull("ChapterTranslationStore starts pages at0", planOriginalSourceCrop(proof.copy(pageIndex = 0), request))
    }
}
