package com.mangalens.ui.video

import com.mangalens.download.OriginalFragmentPlan
import com.mangalens.download.OriginalMediaFragment
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Authored UNRUN; every seek is derived from captured ranges/size or actual observed bytes. */
class OriginalFragmentByteMapTest {
    private fun plan(rows: List<OriginalMediaFragment>) = OriginalFragmentPlan("https://fixture.invalid/base", "v1080", "video/mp4", 8_000_000L, rows)
    private fun part(bytes: Long? = null, start: Long? = null, end: Long? = null) = OriginalMediaFragment("https://fixture.invalid/segment", start, end, bytes)
    @Test fun exactRangeAndObservedSizesMapAcrossEveryOriginalBoundary() {
        val map = OriginalFragmentByteMap(plan(listOf(part(start = 10L, end = 18L), part(), part(11))))
        assertNull(map.locate(9)); map.observe(1, 20)
        assertEquals(OriginalFragmentByteMap.Position(0, 7), map.locate(7))
        assertEquals(OriginalFragmentByteMap.Position(1, 0), map.locate(8))
        assertEquals(OriginalFragmentByteMap.Position(2, 0), map.locate(28))
        assertEquals(OriginalFragmentByteMap.Position(3, 0), map.locate(39))
        assertNull(map.locate(40)); assertEquals(39L, map.total())
    }
    @Test fun durationCannotFillAnUnknownByteGap() {
        val map = OriginalFragmentByteMap(plan(listOf(part(8), part())))
        assertEquals(OriginalFragmentByteMap.Position(1, 0), map.locate(8)); assertNull(map.locate(9)); assertNull(map.total())
    }
    @Test fun actualEofCannotOverwriteCapturedRangeLength() {
        val map = OriginalFragmentByteMap(plan(listOf(part(start = 10, end = 20))))
        assertTrue(runCatching { map.observe(0, 9) }.exceptionOrNull() is IOException)
        assertEquals(10L, map.total())
    }
    @Test fun repeatedObservationCannotBorrowDifferentRepresentation() {
        val map = OriginalFragmentByteMap(plan(listOf(part())))
        map.observe(0, 12)
        assertTrue(runCatching { map.observe(0, 13) }.exceptionOrNull() is IOException)
        assertEquals(12L, map.total())
    }
    @Test fun duplicateUrlsHaveSeparateOrderedBytePositions() {
        val map = OriginalFragmentByteMap(plan(listOf(part(8), part(11))))
        assertEquals(OriginalFragmentByteMap.Position(1, 3), map.locate(11)); assertEquals(19L, map.total())
    }
    @Test fun callerMutationCannotChangeCapturedByteMap() {
        val rows = mutableListOf(part(8), part(11)); val map = OriginalFragmentByteMap(plan(rows))
        rows.clear(); assertEquals(19L, map.total()); assertEquals(OriginalFragmentByteMap.Position(1, 0), map.locate(8))
    }
}
