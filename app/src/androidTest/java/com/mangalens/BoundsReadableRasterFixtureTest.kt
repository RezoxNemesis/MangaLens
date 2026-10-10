package com.mangalens

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Platform gate for the fatal-raster test input; host libwebp alone cannot qualify it. */
@RunWith(AndroidJUnit4::class)
class BoundsReadableRasterFixtureTest {
    @Test fun actualAndroidReadsTheDimensionsButRejectsTheCompleteInvalidWebpRaster() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((width, height) in listOf(32 to 48, 320 to 480)) {
            val bytes = BoundsReadableRasterFixture.create(width, height)
            val file = File(context.cacheDir, "bounds-readable-native-${UUID.randomUUID()}.webp")
            try {
                file.writeBytes(bytes)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                assertEquals(width, bounds.outWidth)
                assertEquals(height, bounds.outHeight)
                assertEquals("image/webp", bounds.outMimeType)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                bitmap?.recycle()
                assertNull("A fatal Huffman error must not be treated as partial raster success", bitmap)
                assertArrayEquals(bytes, file.readBytes())
            } finally { file.delete() }
        }
    }
}
