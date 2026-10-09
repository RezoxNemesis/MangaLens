package com.mangalens.core.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Real PNGs and the translator's production decoder establish original-versus-sampled proof. */
@RunWith(AndroidJUnit4::class)
class OriginalMangaGeometryInstrumentedTest {
    @Test fun fullResolutionNativeDecodePreservesExactOriginalBounds() = withFixture(101, 203) { file ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val decoded = requireNotNull(OriginalMangaPageDecoder.decode(context, file))
        try {
            assertEquals(101, decoded.originalWidth); assertEquals(203, decoded.originalHeight)
            assertEquals(101, decoded.bitmap.width); assertEquals(203, decoded.bitmap.height)
            assertEquals(SavedOriginalSourceBounds(left = 1, top = 2, right = 101, bottom = 203),
                OriginalMangaGeometry.fromSampled(1, 2, 101, 203, decoded.bitmap.width, decoded.bitmap.height,
                    decoded.originalWidth, decoded.originalHeight))
        } finally { decoded.bitmap.recycle() }
    }

    @Test fun deepOddPageNativeDownsampleKeepsOriginalHeightAndOutwardCoordinates() = withFixture(1001, 12007) { file ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val decoded = requireNotNull(OriginalMangaPageDecoder.decode(context, file))
        try {
            assertEquals(1001, decoded.originalWidth); assertEquals(12007, decoded.originalHeight)
            assertTrue(decoded.bitmap.width < decoded.originalWidth)
            assertTrue(decoded.bitmap.height < decoded.originalHeight)
            val width = decoded.bitmap.width; val height = decoded.bitmap.height
            val original = OriginalMangaGeometry.fromSampled(1, height / 2, width, height, width, height,
                decoded.originalWidth, decoded.originalHeight)
            assertEquals((1001L / width).toInt(), original.left)
            assertEquals(((height / 2).toLong() * 12007 / height).toInt(), original.top)
            assertEquals(1001, original.right); assertEquals(12007, original.bottom)
            assertTrue(original.bottom > height)
        } finally { decoded.bitmap.recycle() }
    }

    private fun withFixture(width: Int, height: Int, check: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "original-geometry-").toFile()
        try {
            val file = File(directory, "actual-source.png")
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(image); canvas.drawColor(Color.WHITE)
                canvas.drawRect(1f, 2f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f })
                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }
            } finally { image.recycle() }
            check(file)
        } finally { directory.deleteRecursively() }
    }
}
