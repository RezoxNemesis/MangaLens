package com.mangalens.core.translation.inpainting

import com.mangalens.core.translation.MangaWritableRect
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN original-observation/geometry controls; no learned segmentation confidence is invented. */
class LaMaGlyphMaskPlanTest {
    private val selected = MangaWritableRect(4, 4, 16, 16)
    private val line = MangaWritableRect(5, 5, 15, 15)
    private val target = LaMaObservedText("Hello!", line, listOf(line))
    private fun original() = IntArray(400) { -1 }.apply { for (y in 7..12) { this[y * 20 + 8] = 0xff000000.toInt(); this[y * 20 + 11] = 0xff000000.toInt() } }
    private fun mask(observed: List<LaMaObservedText> = listOf(target), native: List<MangaWritableRect> = emptyList()) =
        LaMaGlyphMaskPlan.create(20, 20, original(), selected, target, observed, native)
    @Test fun actualSavedTextAndSpatialSourceBothBindSelection() {
        assertSame(target, LaMaGlyphMaskPlan.select("HELLO.", selected, listOf(target)))
        assertNull(LaMaGlyphMaskPlan.select("Goodbye", selected, listOf(target)))
        assertNull(LaMaGlyphMaskPlan.select("Hello", MangaWritableRect(0, 0, 3, 3), listOf(target)))
    }
    @Test fun ambiguousRepeatedSameReadingCannotChooseAnArbitraryBubble() {
        assertNull(LaMaGlyphMaskPlan.select("Hello", selected, listOf(target, target.copy(bounds = MangaWritableRect(4, 4, 15, 15)))))
    }
    @Test fun observedLinesOutsideSavedOriginalBoxRefuseRepair() {
        assertNull(LaMaGlyphMaskPlan.select("Hello", selected, listOf(target.copy(lines = listOf(MangaWritableRect(3, 5, 15, 15))))))
    }
    @Test fun glyphEstimateIsNotTheWholeRectangleAndStaysInsideOriginalSource() {
        val actual = requireNotNull(mask())
        assertTrue(actual.count { it } in 13..143)
        for (y in 0 until 20) for (x in 0 until 20) if (x !in 4 until 16 || y !in 4 until 16) assertFalse(actual[y * 20 + x])
        assertFalse(actual[5 * 20 + 5]); assertTrue(actual[8 * 20 + 8])
    }
    @Test fun completeObservedNeighbourBlocksOverlappingRepair() {
        val other = LaMaObservedText("other", MangaWritableRect(14, 4, 18, 16), listOf(MangaWritableRect(14, 4, 18, 16)))
        assertNull(mask(listOf(target, other)))
    }
    @Test fun aDeferredObservationBeyondPublicationOrdinal255StillProtectsItsPixels() {
        val distant = List(256) { LaMaObservedText("different $it", MangaWritableRect(0, 0, 2, 2), listOf(MangaWritableRect(0, 0, 2, 2))) }
        val last = LaMaObservedText("deferred", MangaWritableRect(14, 4, 18, 16), listOf(MangaWritableRect(14, 4, 18, 16)))
        assertNull(mask(listOf(target) + distant + last))
    }
    @Test fun everySavedWritablePatchIsProtectedEvenWithoutFreshOcr() {
        assertNull(mask(native = listOf(MangaWritableRect(15, 3, 19, 17))))
    }
    @Test fun separateNeighboursCanBeExcludedWithoutRejectingTheSelectedGlyphs() {
        val other = LaMaObservedText("other", MangaWritableRect(0, 0, 3, 20), listOf(MangaWritableRect(0, 0, 3, 20)))
        assertNotNull(mask(listOf(target, other), listOf(MangaWritableRect(17, 0, 20, 20))))
    }
    @Test fun incompleteExcessiveObservationsRejectRatherThanTruncateProtection() {
        assertNull(mask(List(4097) { target }))
        assertNull(LaMaGlyphMaskPlan.select("Hello", selected, List(4097) { target }))
    }
    @Test fun missingOrInvalidObservedLinesCannotInventGlyphGeometry() {
        assertNull(LaMaGlyphMaskPlan.create(20, 20, original(), selected, target.copy(lines = emptyList()), listOf(target), emptyList()))
        val invalid = target.copy(lines = listOf(MangaWritableRect(-1, 1, 3, 3)))
        assertNull(LaMaGlyphMaskPlan.create(20, 20, original(), selected, invalid, listOf(invalid), emptyList()))
    }
    @Test fun blankAndDenseArtworkAreNotMisrepresentedAsSafeGlyphMasks() {
        assertNull(LaMaGlyphMaskPlan.create(20, 20, IntArray(400) { -1 }, selected, target, listOf(target), emptyList()))
        val dense = IntArray(400) { -1 }.apply { for (y in 5 until 15) for (x in 5 until 15) this[y * 20 + x] = 0xff000000.toInt() }
        assertNull(LaMaGlyphMaskPlan.create(20, 20, dense, selected, target, listOf(target), emptyList()))
    }
    @Test fun oneTransparentGlyphPixelDeclinesTheRgbPack() {
        val pixels = original().apply { this[8 * 20 + 8] = 0x00000000 }
        assertNull(LaMaGlyphMaskPlan.create(20, 20, pixels, selected, target, listOf(target), emptyList()))
    }
    @Test fun cancellationChecksTheBoundedGeometryAndPixelLoops() {
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            LaMaGlyphMaskPlan.create(20, 20, original(), selected, target, listOf(target), emptyList()) { throw kotlinx.coroutines.CancellationException() }
        }
    }
}
