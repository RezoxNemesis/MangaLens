package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class OriginalMangaGeometryTest {
    @Test fun oddDownsampledDimensionsRoundOutwardUsingBothActualAxes() {
        assertEquals(SavedOriginalSourceBounds(left = 4, top = 6, right = 93, bottom = 143),
            OriginalMangaGeometry.fromSampled(1, 2, 23, 47, 250, 333, 1001, 1009))
    }
    @Test fun fullResolutionPreservesExactBoundsIncludingPageEdges() {
        assertEquals(SavedOriginalSourceBounds(left = 0, top = 1, right = 101, bottom = 203),
            OriginalMangaGeometry.fromSampled(0, 1, 101, 203, 101, 203, 101, 203))
    }
    @Test fun largePageDepthUsesOriginalHeightRatherThanViewportOrWidthRatio() {
        assertEquals(SavedOriginalSourceBounds(left = 10, top = 28_006, right = 1600, bottom = 30_007),
            OriginalMangaGeometry.fromSampled(5, 14_000, 800, 15_000, 1000, 20_000, 2000, 40_009))
    }
    @Test fun invalidOrUpscaledSourceProofCannotBeInvented() {
        for (action in listOf<() -> Any>(
            { OriginalMangaGeometry.fromSampled(-1, 0, 9, 9, 10, 10, 20, 20) },
            { OriginalMangaGeometry.fromSampled(0, 0, 11, 9, 10, 10, 20, 20) },
            { OriginalMangaGeometry.fromSampled(0, 0, 9, 9, 10, 10, 5, 5) },
            { OriginalMangaGeometry.fromSampled(0, 0, 9, 9, 0, 10, 20, 20) },
            { OriginalMangaGeometry.fromSampled(0, 0, 9, 9, 10, 10, 100_000, 100_000) }
        )) assertTrue(runCatching(action).isFailure)
    }
}
