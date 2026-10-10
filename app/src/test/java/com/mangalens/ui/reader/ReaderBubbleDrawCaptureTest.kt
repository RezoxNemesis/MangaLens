package com.mangalens.ui.reader

import com.mangalens.core.translation.SavedMangaLettering
import org.junit.Assert.*
import org.junit.Test

/** Actual Canvas frame/draw metadata is distinct from original-source proof. No Compose timing claim. */
class ReaderBubbleDrawCaptureTest {
    private val native = SavedMangaLettering("native OCR", "saved text", 10, 20, 70, 80,
        "sans-serif", 0, -16777216, 18f, "ALIGN_CENTER", 10, 20, 70, 80)
    private val target = ReaderBubbleHitTarget(ReaderBubbleTap(7, 3, native), 100, 200)

    @Test fun layoutWithoutAnActualPaintCannotOpenANativeEntry() {
        assertNull(ReaderBubbleDrawCapture(listOf(target)).hit(ReaderBubbleCanvasFrame(0f, 0f, 200f, 400f), 40f, 60f))
    }
    @Test fun lastDrawnPagedCanvasUsesItsActualOffsetRatherThanWholeViewportDimensions() {
        val capture = ReaderBubbleDrawCapture(listOf(target)); capture.painted(200f, 400f)
        val selected = capture.hit(ReaderBubbleCanvasFrame(100f, 150f, 200f, 400f), 140f, 210f)
        assertEquals(7, selected?.pageIndex); assertEquals(3, selected?.letteringIndex); assertSame(native, selected?.expectedNative)
        assertNull(capture.hit(ReaderBubbleCanvasFrame(100f, 150f, 200f, 400f), 40f, 60f))
    }
    @Test fun newLayoutBeforeItsPaintCannotReuseTheOldPixels() {
        val capture = ReaderBubbleDrawCapture(listOf(target)); capture.painted(200f, 400f)
        assertNull(capture.hit(ReaderBubbleCanvasFrame(0f, 0f, 300f, 600f), 60f, 90f))
        capture.painted(300f, 600f)
        assertNotNull(capture.hit(ReaderBubbleCanvasFrame(0f, 0f, 300f, 600f), 60f, 90f))
    }
    @Test fun currentInvalidPaintRetiresAnEarlierValidDraw() {
        val capture = ReaderBubbleDrawCapture(listOf(target)); capture.painted(200f, 400f)
        capture.painted(Float.NaN, 400f)
        assertNull(capture.hit(ReaderBubbleCanvasFrame(0f, 0f, 200f, 400f), 40f, 60f))
    }
    @Test fun invalidPositionCannotAcquireCoordinatesFromAnOtherwiseValidPaint() {
        val capture = ReaderBubbleDrawCapture(listOf(target)); capture.painted(200f, 400f)
        assertNull(capture.hit(ReaderBubbleCanvasFrame(Float.POSITIVE_INFINITY, 0f, 200f, 400f), 40f, 60f))
        assertNull(capture.hit(null, 40f, 60f))
    }
    @Test fun overlapUsesTheLastValidDrawAndRetainsItsUnfilteredNativeIndex() {
        val upper = ReaderBubbleHitTarget(ReaderBubbleTap(7, 8, native.copy(source = "upper original")), 100, 200)
        val invalid = ReaderBubbleHitTarget(ReaderBubbleTap(7, 9, native.copy(size = Float.NaN)), 100, 200)
        val capture = ReaderBubbleDrawCapture(listOf(target, upper, invalid)); capture.painted(200f, 400f)
        assertEquals(8, capture.hit(ReaderBubbleCanvasFrame(0f, 0f, 200f, 400f), 40f, 60f)?.letteringIndex)
    }
}
