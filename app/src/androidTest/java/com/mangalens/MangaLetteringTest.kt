package com.mangalens

import android.graphics.*
import android.view.LayoutInflater
import android.view.TextureView
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.MangaLettering
import com.mangalens.engine.AdvancedTranslationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MangaLetteringTest {
    @Test fun opaqueRemovalPreservesTintAndMatchesBoldCondensedLettering() {
        val image = Bitmap.createBitmap(1000, 350, Bitmap.Config.ARGB_8888)
        val paper = Color.rgb(209, 200, 184)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 65f; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        val source = "THE SECOND SEMESTER"
        val inkBounds = Rect(); paint.getTextBounds(source, 0, source.length, inkBounds)
        val bounds = RectF(inkBounds).apply { offset(60f, 180f) }
        Canvas(image).apply { drawColor(paper); drawText(source, 60f, 180f, paint); drawRect(900f, 0f, 1000f, 350f, Paint().apply { color = Color.RED }) }
        val patch = MangaLettering.prepare(image, bounds, listOf(bounds), source)
        try {
            assertEquals("sans-serif-condensed", patch.style.family)
            assertEquals(Typeface.BOLD, patch.style.face)
            assertEquals(Color.BLACK, patch.style.color)
            for (y in 0 until patch.background.height) for (x in 0 until patch.background.width) {
                assertEquals("Source ink survived removal", paper, patch.background.getPixel(x, y))
                assertEquals(255, Color.alpha(patch.background.getPixel(x, y)))
            }
            val result = image.copy(Bitmap.Config.ARGB_8888, true)
            try {
                MangaLettering.draw(Canvas(result), patch, "यह दूसरा सेमेस्टर है।")
                assertEquals(Color.RED, result.getPixel(950, 200))
                assertEquals(paper, result.getPixel(20, 20))
                assertFalse(image.sameAs(result))
                save("lettering-original.png", image); save("lettering-reconstructed.png", result)
            } finally { result.recycle() }
        } finally { patch.background.recycle(); image.recycle() }
    }

    @Test fun whiteItalicOnDarkAndLongHindiStayInsideOriginalRegion() {
        val image = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 60f; typeface = Typeface.create("serif", Typeface.ITALIC) }
        val source = "Something interesting"
        val box = Rect(); paint.getTextBounds(source, 0, source.length, box)
        val bounds = RectF(box).apply { offset(50f, 150f) }
        Canvas(image).apply { drawColor(Color.rgb(25, 30, 40)); drawText(source, 50f, 150f, paint) }
        val patch = MangaLettering.prepare(image, bounds, listOf(bounds), source)
        try {
            assertEquals(Color.WHITE, patch.style.color)
            assertTrue(patch.style.face == Typeface.ITALIC || patch.style.face == Typeface.BOLD_ITALIC)
            val text = "एक दिलचस्प योजना के साथ वापस आने का समय आ गया है। ".repeat(3)
            val layout = MangaLettering.layout(text, patch.style, patch.bounds.width(), patch.bounds.height(), 1.5f)
            assertTrue(layout.height <= patch.bounds.height())
            for (line in 0 until layout.lineCount) assertTrue(layout.getLineWidth(line) <= patch.bounds.width() + .5f)
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        } finally { patch.background.recycle(); image.recycle() }
    }

    @Test fun tallPagesRecognizeTextAtTileBoundaryAndAtBottom() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("mangalens_ocr", 0)
        val script = prefs.getString("script", "AUTO"); val accuracy = prefs.getBoolean("high_accuracy", true)
        prefs.edit().putString("script", "LATIN").putBoolean("high_accuracy", false).commit()
        val image = Bitmap.createBitmap(1000, 4700, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 55f }
            drawText("The second semester", 50f, 2020f, paint)
            drawText("Time for graduation", 50f, 4510f, paint)
        }
        try {
            val regions = AdvancedTranslationEngine(context).recognizeScriptAware(image)
            assertEquals("Tall OCR regions: $regions", 2, regions.size)
            assertTrue("Tile-boundary OCR regions: $regions", regions.any { it.source.contains("semester", true) && it.bounds.top > 1900 })
            assertTrue("Bottom OCR regions: $regions", regions.any { it.source.contains("graduation", true) && it.bounds.top > 4400 })
        } finally { image.recycle(); prefs.edit().putString("script", script).putBoolean("high_accuracy", accuracy).commit() }
    }

    @Test fun videoPlayerUsesCapturableTextureSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val player = LayoutInflater.from(instrumentation.targetContext).inflate(R.layout.ocr_player_view, null) as PlayerView
            assertTrue(player.videoSurfaceView is TextureView)
        }
    }

    private fun save(name: String, image: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), name)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("mkdir -p /sdcard/Download/mangalens-qa")
        device.executeShellCommand("cp '${file.absolutePath}' /sdcard/Download/mangalens-qa/")
    }
}
