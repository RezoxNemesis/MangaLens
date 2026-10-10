package com.mangalens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.core.translation.memory.MemoryRegionBounds
import com.mangalens.core.translation.memory.MemorySourceProof
import com.mangalens.ui.reader.ReaderBubbleOriginalCropLoader
import com.mangalens.ui.reader.ReaderSfxOriginalPatch
import com.mangalens.ui.reader.drawOriginalSfxPatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Authored UNRUN. Actual private PNG/held decoder/draw controls; no OCR/model or native receipt is invented. */
@RunWith(AndroidJUnit4::class)
class ReaderSfxOriginalPixelsTest {
    private fun fixture(block: suspend (File, MemorySourceProof) -> Unit) = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sfx-original-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = Bitmap.createBitmap(16, 20, Bitmap.Config.ARGB_8888)
            val file = File(root, "original.png")
            try {
                original.eraseColor(Color.WHITE)
                for (y in 3 until 12) for (x in 2 until 10) original.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.RED)
                file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { original.recycle() }
            block(root, MemorySourceProof("chapter", 37, file.canonicalPath, ChapterTranslationStore.sha256(file), 16, 20, MemoryRegionBounds(2, 3, 10, 12)))
        } finally { root.deleteRecursively() }
    }
    @Test fun verifiedOriginalPatchRestoresPixelsOnlyInsideTheExactNativeWritableBounds() = fixture { root, source ->
        val bytes = File(source.sourcePath).readBytes()
        val crop = ReaderBubbleOriginalCropLoader(root).openVerifiedSource(source, 500000) { true }!!
        val patch = ReaderSfxOriginalPatch(crop, Rect(2, 3, 10, 12), 16, 20, 16, 20, source.bounds) { true }
        val rendered = Bitmap.createBitmap(16, 20, Bitmap.Config.ARGB_8888)
        try {
            rendered.eraseColor(Color.GREEN)
            assertTrue(drawOriginalSfxPatch(Canvas(rendered), patch))
            for (y in 0 until 20) for (x in 0 until 16) assertEquals(if (x in 2 until 10 && y in 3 until 12)
                if ((x + y) % 2 == 0) Color.BLACK else Color.RED else Color.GREEN, rendered.getPixel(x, y))
            assertArrayEquals(bytes, File(source.sourcePath).readBytes())
        } finally { patch.close(); rendered.recycle() }
        assertFalse(crop.isSourceCurrent())
    }
    @Test fun retiredOriginalRestorationCannotSuppressOrPaintOverTheTranslatedView() = fixture { root, source ->
        val crop = ReaderBubbleOriginalCropLoader(root).openVerifiedSource(source, 500000) { true }!!
        var current = true
        val patch = ReaderSfxOriginalPatch(crop, Rect(2, 3, 10, 12), 16, 20, 16, 20, source.bounds) { current }
        val rendered = Bitmap.createBitmap(16, 20, Bitmap.Config.ARGB_8888)
        try {
            rendered.eraseColor(Color.GREEN); current = false
            assertFalse(drawOriginalSfxPatch(Canvas(rendered), patch))
            for (y in 0 until 20) for (x in 0 until 16) assertEquals(Color.GREEN, rendered.getPixel(x, y))
        } finally { patch.close(); rendered.recycle() }
    }
}
