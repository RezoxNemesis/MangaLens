package com.mangalens.core.reader

import org.junit.Assert.*
import org.junit.Test

class MangaImagePolicyTest {
    @Test fun unsupportedGifAndOtherFormatsCannotSelectTheRegionDecoder() {
        for (type in listOf("image/jpeg", "image/png", "image/webp")) assertTrue(MangaImagePolicy.supportsRegionFormat(type))
        for (type in listOf(null, "image/gif", "image/avif", "image/svg+xml", "application/octet-stream")) {
            assertFalse(MangaImagePolicy.supportsRegionFormat(type))
        }
    }

    @Test fun invalidOrOverflowingDimensionsCannotPlanRegions() {
        assertNull(MangaImagePolicy.dimensions(0, 10))
        assertNull(MangaImagePolicy.dimensions(10, -1))
        assertNull(MangaImagePolicy.dimensions(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(MangaImagePolicy.dimensions(10_001, 10_000))
        assertNotNull(MangaImagePolicy.dimensions(10_000, 10_000))
    }

    @Test fun suppliedLongPageDecodesNativeWidthInSmallRegionsInsteadOfResizingAllItsDialogue() {
        val page = MangaImagePolicy.dimensions(720, 12635)!!
        val regions = MangaImagePolicy.visibleRegions(page, 0, 1280)
        assertEquals(listOf(MangaImagePolicy.Region(0, 2048), MangaImagePolicy.Region(2048, 4096)), regions)
        for (region in regions) {
            assertEquals(1, MangaImagePolicy.sampleSize(page.width, region.height, 720))
            assertTrue(page.width.toLong() * region.height <= MangaImagePolicy.MAX_DECODE_PIXELS)
        }
        assertEquals(12635, page.height)
    }

    @Test fun scrollWithinATileDoesNotInvalidateItsDecodeAndLaterTilesKeepSourceCoordinates() {
        val page = MangaImagePolicy.dimensions(720, 12635)!!
        assertEquals(MangaImagePolicy.visibleRegions(page, 2200, 3000), MangaImagePolicy.visibleRegions(page, 2201, 3001))
        val regions = MangaImagePolicy.visibleRegions(page, 11_000, 12_635)
        assertEquals(8192, regions.first().top)
        assertEquals(12635, regions.last().bottom)
        for ((a, b) in regions.zipWithNext()) assertEquals(a.bottom, b.top)
    }

    @Test fun everySuppliedChapterHeightHasContinuousBoundedRegionsAtEveryTileBoundary() {
        val heights = listOf(12635, 9170, 10000, 8940, 9235, 10000, 10000, 9510, 10000, 9000, 9490, 10000, 9385, 9755, 8885)
        for (height in heights) {
            val page = MangaImagePolicy.dimensions(720, height)!!
            for (top in 0 until height step 1024) {
                val end = minOf(height, top + 1280)
                val regions = MangaImagePolicy.visibleRegions(page, top, end)
                assertTrue(regions.first().top <= top)
                assertTrue(regions.last().bottom >= end)
                assertTrue(regions.all { it.height in 1..2048 && it.top >= 0 && it.bottom <= height })
                for ((a, b) in regions.zipWithNext()) assertEquals(a.bottom, b.top)
            }
        }
    }

    @Test fun offscreenPagesDoNotDecodeAnyRegions() {
        val page = MangaImagePolicy.dimensions(720, 12635)!!
        assertTrue(MangaImagePolicy.visibleRegions(page, 0, 0).isEmpty())
        assertTrue(MangaImagePolicy.visibleRegions(page, 13000, 14000).isEmpty())
        assertTrue(MangaImagePolicy.visibleRegions(page, -100, -1).isEmpty())
    }

    @Test fun hugeOrWideRegionsRespectBothSidesPixelBudgetAndRequestedViewportWidth() {
        for ((width, height, requested) in listOf(Triple(720, 2048, 360), Triple(9000, 4000, 1080), Triple(10000, 10000, 2048))) {
            val sample = MangaImagePolicy.sampleSize(width, height, requested)
            val decodedWidth = (width.toLong() + sample - 1) / sample
            val decodedHeight = (height.toLong() + sample - 1) / sample
            assertEquals(0, sample and (sample - 1))
            assertTrue(decodedWidth <= minOf(requested, MangaImagePolicy.MAX_DECODE_SIDE))
            assertTrue(decodedHeight <= MangaImagePolicy.MAX_DECODE_SIDE)
            assertTrue(decodedWidth * decodedHeight <= MangaImagePolicy.MAX_DECODE_PIXELS)
        }
    }

    @Test fun coverUsesTheTopArtworkAndNeverReadsPastTheSourcePage() {
        assertEquals(MangaImagePolicy.Region(0, 1044), MangaImagePolicy.coverRegion(MangaImagePolicy.Dimensions(720, 12635)))
        assertEquals(MangaImagePolicy.Region(0, 500), MangaImagePolicy.coverRegion(MangaImagePolicy.Dimensions(1000, 500)))
    }
}
