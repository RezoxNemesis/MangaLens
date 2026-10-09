package com.mangalens.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrMixedCaseVariantTest {
    @Test fun suppliedAnnotationVariantWithOneMalformedWordStillRequestsOriginalPixels() {
        val source = "From hebhe worlaAa"
        assertEquals(source, OcrSourceQuality.normalizeLatinSource(source))
        assertTrue("A single malformed lower-case word bypassed source uncertainty", OcrSourceQuality.needsPixelRetry(source))
    }
}
