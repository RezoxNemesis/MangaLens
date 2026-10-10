package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class MangaWritableGeometryTest {
    private val source = MangaWritableRect(20, 20, 40, 40)
    @Test fun aloneRetainsWholePageExpansionBudget() { assertEquals(MangaWritableRect(0, 0, 100, 100), MangaWritableGeometry.limit(source, emptyList(), 100, 100)) }
    @Test fun horizontalNeighboursHaveTheSameExclusionPlane() {
        val other = MangaWritableRect(55, 20, 75, 40)
        val a = MangaWritableGeometry.limit(source, listOf(other), 100, 100)!!
        val b = MangaWritableGeometry.limit(other, listOf(source), 100, 100)!!
        assertEquals(a.right, b.left); assertFalse(a.intersects(b)); assertTrue(a.right >= source.right)
    }
    @Test fun verticallyAdjacentRegionsHaveTheSameExclusionPlane() {
        val other = MangaWritableRect(20, 55, 40, 75)
        val a = MangaWritableGeometry.limit(source, listOf(other), 100, 100)!!
        val b = MangaWritableGeometry.limit(other, listOf(source), 100, 100)!!
        assertEquals(a.bottom, b.top); assertFalse(a.intersects(b))
    }
    @Test fun oddGapDoesNotCreateAOnePixelOverlap() {
        val other = MangaWritableRect(51, 20, 75, 40)
        assertEquals(MangaWritableGeometry.limit(source, listOf(other), 100, 100)!!.right,
            MangaWritableGeometry.limit(other, listOf(source), 100, 100)!!.left)
    }
    @Test fun inherentSourceIntersectionIsUnresolved() { assertNull(MangaWritableGeometry.limit(source, listOf(MangaWritableRect(39, 25, 65, 38)), 100, 100)) }
    @Test fun touchingBoxesRetainTheirFullSourceAndNoSharedArea() { assertEquals(40, MangaWritableGeometry.limit(source, listOf(MangaWritableRect(40, 20, 50, 40)), 100, 100)!!.right) }
    @Test fun invalidNeighbourIsRejectedRatherThanInvented() { assertNull(MangaWritableGeometry.limit(source, listOf(MangaWritableRect(-1, 1, 5, 5)), 100, 100)) }
    @Test fun priorSuccessfulExpandedPatchExcludesItsWholeArea() { assertNull(MangaWritableGeometry.limit(source, listOf(MangaWritableRect(38, 10, 70, 70)), 100, 100)) }
    @Test fun diagonalExclusionsNeverShrinkInsideSource() {
        val value = MangaWritableGeometry.limit(source, listOf(MangaWritableRect(60, 60, 90, 90)), 100, 100)!!
        assertTrue(value.right >= source.right && value.bottom >= source.bottom); assertFalse(value.intersects(MangaWritableRect(60, 60, 90, 90)))
    }
    @Test fun sourceBeyondLetteringPublicationCapStillBlocksAnEarlyWritablePatch() {
        val later = MangaWritableRect(51, 20, 75, 40)
        val observed = List(256) { MangaWritableRect(0, 80, 10, 90) } + later
        val complete = MangaWritableGeometry.limit(source, observed, 100, 100)!!
        val oldTruncated = MangaWritableGeometry.limit(source, observed.take(256), 100, 100)!!
        assertEquals(45, complete.right); assertEquals(100, oldTruncated.right)
        assertFalse(complete.intersects(later)); assertTrue(oldTruncated.intersects(later))
    }
    @Test fun allBoundedOriginalObservationsAreProtectedIndependentlyOfSavedOutputCap() {
        val neighbours = List(MangaWritableGeometry.MAX_OBSERVATIONS) { MangaWritableRect(51, 20, 75, 40) }
        assertEquals(45, MangaWritableGeometry.limit(source, neighbours, 100, 100)!!.right)
    }
    @Test fun overLimitGeometryCannotSilentlyBecomeATruncatedProof() {
        try { MangaWritableGeometry.limit(source, List(MangaWritableGeometry.MAX_PROTECTED_RECTS + 1) { MangaWritableRect(51, 20, 75, 40) }, 100, 100); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun largeMidpointUsesLongArithmetic() {
        val a = MangaWritableRect(1_500_000_000, 0, 1_600_000_000, 20); val b = MangaWritableRect(1_700_000_000, 0, 1_800_000_000, 20)
        assertEquals(1_650_000_000, MangaWritableGeometry.limit(a, listOf(b), 2_000_000_000, 100)!!.right)
    }
}
