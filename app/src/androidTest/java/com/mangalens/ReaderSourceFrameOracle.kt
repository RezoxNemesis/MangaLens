package com.mangalens

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil

/** A description node exists before Coil decoding. Verify actual visible source pixels. */
internal object ReaderSourceFrameOracle {
    internal fun matchingSamples(expected: IntArray, actual: IntArray): Int {
        require(expected.size == actual.size)
        return expected.indices.count { index ->
            val a = expected[index]; val b = actual[index]
            maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)), abs((a shr 8 and 255) - (b shr 8 and 255)),
                abs((a and 255) - (b and 255))) <= 45
        }
    }

    fun awaitVisibleSource(support: CoreScreenSmokeSupport, description: String, file: File,
        letteringBounds: List<Rect>, deadline: Long) {
        val selector = By.desc(description).pkg(support.context.packageName)
        val page = support.node(selector, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
        val viewport = page.visibleBounds
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0 && viewport.width() > 0) { "Reader source has no decodable geometry." }
        // The acceptance reader is explicitly vertical, starts at source row zero,
        // and fits the source width. This central band avoids the header/footer HUD.
        val scale = viewport.width().toFloat() / bounds.outWidth
        val rows = (0 until 12).map { viewport.top + (viewport.height() * (.25f + it * .03f)).toInt() }
        val columns = (0 until 12).map { viewport.left + (viewport.width() * (.15f + it * .06f)).toInt() }
        val points = rows.flatMap { y -> columns.map { x -> x to y } }.filter { (x, y) ->
            val sx = ((x - viewport.left) / scale).toInt(); val sy = ((y - viewport.top) / scale).toInt()
            sy in 0 until bounds.outHeight && letteringBounds.none { it.contains(sx, sy) }
        }
        check(points.size >= 48) { "Reader source comparison has too few unobscured samples." }
        val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false) ?: error("Reader source cannot be region decoded.")
        val patch = try {
            decoder.decodeRegion(Rect(0, 0, bounds.outWidth,
                ceil((rows.last() - viewport.top + 1) / scale).toInt().coerceIn(1, bounds.outHeight)),
                BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 })
                ?: error("Visible source patch cannot be decoded.")
        } finally { decoder.recycle() }
        val expected = try { points.map { (x, y) -> patch.getPixel(
            ((x - viewport.left) / scale).toInt().coerceIn(0, patch.width - 1),
            ((y - viewport.top) / scale).toInt().coerceIn(0, patch.height - 1)) }.toIntArray() }
        finally { patch.recycle() }
        var matching = 0
        support.waitFor("Reader image remained blank or did not match its verified source ($matching/${expected.size} samples)",
            (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) {
            val current = support.device.findObject(selector)?.visibleBounds
            if (current != viewport) return@waitFor false
            val frame = support.instrumentation.uiAutomation.takeScreenshot() ?: return@waitFor false
            try {
                if (points.any { (x, y) -> x !in 0 until frame.width || y !in 0 until frame.height }) return@waitFor false
                val actual = points.map { (x, y) -> frame.getPixel(x, y) }.toIntArray()
                matching = matchingSamples(expected, actual)
                matching * 100 >= expected.size * 80
            } finally { frame.recycle() }
        }
        assertTrue("Rendered Reader frame must match its verified source", matching * 100 >= expected.size * 80)
    }
}
