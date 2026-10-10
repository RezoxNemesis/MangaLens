package com.mangalens.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPanelGeometryTest {
    private fun page(width: Int, height: Int, vararg rectangles: IntArray): IntArray =
        IntArray(width * height) { -1 }.also { pixels -> rectangles.forEach { rect ->
            for (y in rect[1] until rect[3]) for (x in rect[0] until rect[2]) pixels[y * width + x] = -0x1000000
        } }

    @Test fun horizontalGutterProducesTopThenBottom() {
        val panels = ReaderPanelGeometry.detect(64, 64, page(64, 64,
            intArrayOf(1, 1, 63, 28), intArrayOf(1, 36, 63, 63)))
        assertEquals(2, panels.size)
        assertTrue(panels[0].bottom < panels[1].top)
        assertEquals(0f, panels[0].top)
        assertEquals(1f, panels[1].bottom)
    }

    @Test fun rowsPrecedeColumnsInComicReadingOrder() {
        val image = page(64, 64, intArrayOf(1, 1, 27, 27), intArrayOf(37, 1, 63, 27),
            intArrayOf(1, 37, 27, 63), intArrayOf(37, 37, 63, 63))
        val ltr = ReaderPanelGeometry.detect(64, 64, image)
        val rtl = ReaderPanelGeometry.detect(64, 64, image, rightToLeft = true)
        assertEquals(4, ltr.size)
        assertEquals(4, rtl.size)
        assertTrue(ltr[0].left < ltr[1].left && ltr[1].top < ltr[2].top)
        assertTrue(rtl[0].left > rtl[1].left && rtl[1].top < rtl[2].top)
    }

    @Test fun blankImageDoesNotInventPanels() {
        assertEquals(listOf(ReaderPanelRect(0f, 0f, 1f, 1f)),
            ReaderPanelGeometry.detect(64, 64, IntArray(4096) { -1 }))
    }

    @Test fun staggeredColumnRowsFallBackInsteadOfInventingReadingOrder() {
        val image = page(64, 64, intArrayOf(1, 1, 27, 20), intArrayOf(1, 28, 27, 63),
            intArrayOf(37, 1, 63, 37), intArrayOf(37, 45, 63, 63))
        assertEquals(listOf(ReaderPanelRect(0f, 0f, 1f, 1f)), ReaderPanelGeometry.detect(64, 64, image))
        assertEquals(listOf(ReaderPanelRect(0f, 0f, 1f, 1f)), ReaderPanelGeometry.detect(64, 64, image, true))
    }

    @Test fun borderWhitespaceIsNotAnotherPanel() {
        val panels = ReaderPanelGeometry.detect(64, 64, page(64, 64, intArrayOf(20, 20, 44, 44)))
        assertEquals(1, panels.size)
        assertEquals(ReaderPanelRect(0f, 0f, 1f, 1f), panels.single())
    }

    @Test fun solidArtworkRemainsWholePage() {
        assertEquals(1, ReaderPanelGeometry.detect(64, 64, IntArray(4096) { -0x1000000 }).size)
    }

    @Test fun transparentGuttersAreCompositedOnReaderWhite() {
        val pixels = page(64, 64, intArrayOf(1, 1, 28, 63), intArrayOf(36, 1, 63, 63))
        for (y in 0 until 64) for (x in 28 until 36) pixels[y * 64 + x] = 0x00000000
        assertEquals(2, ReaderPanelGeometry.detect(64, 64, pixels).size)
    }

    @Test fun manyGuttersStayBoundedAndNonoverlapping() {
        val rectangles = (0..7).flatMap { y -> (0..7).map { x ->
            intArrayOf(x * 32 + 2, y * 32 + 2, x * 32 + 28, y * 32 + 28)
        } }.toTypedArray()
        val panels = ReaderPanelGeometry.detect(256, 256, page(256, 256, *rectangles))
        assertTrue(panels.size in 2..ReaderPanelGeometry.MAX_PANELS)
        panels.forEach { assertTrue(it.left >= 0 && it.top >= 0 && it.right <= 1 && it.bottom <= 1 &&
            it.left < it.right && it.top < it.bottom) }
        for (i in panels.indices) for (j in i + 1 until panels.size) {
            val a = panels[i]; val b = panels[j]
            assertTrue(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedInputIsRejectedBeforeScanning() {
        ReaderPanelGeometry.detect(257, 256, IntArray(257 * 256))
    }

    @Test(expected = IllegalArgumentException::class) fun pixelGeometryMustMatch() {
        ReaderPanelGeometry.detect(64, 64, IntArray(3))
    }
}
