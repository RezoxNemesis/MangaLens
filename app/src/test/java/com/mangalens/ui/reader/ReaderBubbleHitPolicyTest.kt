package com.mangalens.ui.reader

import com.mangalens.core.translation.SavedMangaLettering
import com.mangalens.core.translation.SavedOriginalSourceBounds
import org.junit.Assert.*
import org.junit.Test

/** Prepared pure mapping checks; no Bitmap/native/OCR/UI pass is asserted by this source. */
class ReaderBubbleHitPolicyTest {
    private fun target(index: Int = 7, left: Int = 10, top: Int = 20, right: Int = 30, bottom: Int = 40) =
        ReaderBubbleHitTarget(ReaderBubbleTap(3, index, SavedMangaLettering("Actual saved OCR", "Saved translation",
            left, top, right, bottom, "sans-serif", 0, 0xff000000.toInt(), 18f, "ALIGN_CENTER", 9, 19, 29, 39,
            originalSourceBounds = SavedOriginalSourceBounds(left = 40, top = 60, right = 121, bottom = 122))), 250, 333)

    @Test fun actualUniformRendererScaleSelectsItsSavedEntryAndPreservesSeparateOriginalCropBounds() {
        val target = target()
        assertEquals(target.tap, ReaderBubbleHitPolicy.hit(listOf(target), 500f, 666f, 40f, 60f))
        assertEquals(SavedOriginalSourceBounds(left = 40, top = 60, right = 121, bottom = 122), target.tap.expectedNative.originalSourceBounds)
        assertNull(ReaderBubbleHitPolicy.hit(listOf(target), 500f, 666f, 140f, 200f))
    }

    @Test fun halfOpenRenderedRectangleDoesNotCaptureSurroundingReaderTapZones() {
        val target = target()
        assertEquals(target.tap, ReaderBubbleHitPolicy.hit(listOf(target), 500f, 666f, 20f, 40f))
        assertNull(ReaderBubbleHitPolicy.hit(listOf(target), 500f, 666f, 60f, 50f))
        assertNull(ReaderBubbleHitPolicy.hit(listOf(target), 500f, 666f, 40f, 80f))
        assertNull(ReaderBubbleHitPolicy.hit(listOf(target), 500f, 666f, 19.99f, 40f))
    }

    @Test fun overlappingSavedRegionsUseTheActualLastDrawnEntryWithItsUnfilteredNativeIndex() {
        val first = target(index = 3)
        val last = target(index = 19)
        assertEquals(last.tap, ReaderBubbleHitPolicy.hit(listOf(first, last), 500f, 666f, 40f, 60f))
    }

    @Test fun legacyMissingOriginalGeometryIsOnlyAHittableReadOnlySavedRegionNeverInventedCropProof() {
        val legacy = target().let { it.copy(tap = it.tap.copy(expectedNative = it.tap.expectedNative.copy(originalSourceBounds = null))) }
        val tap = ReaderBubbleHitPolicy.hit(listOf(legacy), 500f, 666f, 40f, 60f)
        assertNotNull(tap)
        assertNull(tap!!.expectedNative.originalSourceBounds)
    }

    @Test fun clippedCanvasAndNonfiniteCoordinatesCannotOpenOffscreenRegions() {
        val target = target()
        assertNull(ReaderBubbleHitPolicy.hit(listOf(target), 500f, 30f, 40f, 60f))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            assertNull(ReaderBubbleHitPolicy.hit(listOf(target), 500f, 666f, invalid, 60f))
            assertNull(ReaderBubbleHitPolicy.hit(listOf(target), invalid, 666f, 40f, 60f))
        }
    }

    @Test fun malformedSavedRendererMetadataCannotAcquireABubbleInteraction() {
        val good = target()
        val invalid = listOf(good.copy(imageWidth = 0), good.copy(imageHeight = 0),
            good.copy(tap = good.tap.copy(letteringIndex = -1)), good.copy(tap = good.tap.copy(pageIndex = -1)),
            good.copy(tap = good.tap.copy(expectedNative = good.tap.expectedNative.copy(left = -1))),
            good.copy(tap = good.tap.copy(expectedNative = good.tap.expectedNative.copy(right = 251))),
            good.copy(tap = good.tap.copy(expectedNative = good.tap.expectedNative.copy(size = Float.NaN))))
        invalid.forEach { assertNull(ReaderBubbleHitPolicy.hit(listOf(it), 500f, 666f, 40f, 60f)) }
    }

    @Test fun aPersonalDisplayStringDoesNotBecomeTheOriginalNativeEntryPayload() {
        val native = target()
        val personal = native.tap.expectedNative.copy(source = "Explicit personal OCR", translated = "Explicit personal translation")
        assertNotEquals(personal, native.tap.expectedNative)
        val tap = ReaderBubbleHitPolicy.hit(listOf(native), 500f, 666f, 40f, 60f)!!
        assertEquals("Actual saved OCR", tap.expectedNative.source)
        assertEquals("Saved translation", tap.expectedNative.translated)
    }
}
