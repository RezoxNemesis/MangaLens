package com.mangalens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.engine.AdvancedTranslationEngine
import com.mangalens.engine.LocalSourceLanguage
import com.mangalens.engine.TranslationRegion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrRegionReliabilityTest {
    @Test fun adjacentJapaneseColumnsMergeInNativeReadingOrderWithoutMutatingBounds() {
        val right = region("大丈夫", RectF(150f, 100f, 175f, 250f))
        val left = region("行こう", RectF(112f, 105f, 137f, 255f))
        val merged = AdvancedTranslationEngine().mergeLikelySameBalloon(listOf(left, right)).single()
        assertEquals("大丈夫\n行こう", merged.source)
        assertEquals(LocalSourceLanguage.JAPANESE, merged.sourceLanguage)
        assertEquals(RectF(112f, 100f, 175f, 255f), merged.bounds)
        assertEquals(listOf(right.bounds, left.bounds), merged.lineBounds)
        assertEquals(RectF(150f, 100f, 175f, 250f), right.bounds)
        assertEquals(RectF(112f, 105f, 137f, 255f), left.bounds)
    }

    @Test fun horizontalCaptionAndVerticalColumnStaySeparate() {
        val caption = region("これは横書きです", RectF(100f, 100f, 180f, 260f)).copy(lineBounds = listOf(
            RectF(100f, 100f, 180f, 125f), RectF(100f, 160f, 180f, 185f), RectF(100f, 220f, 180f, 245f)
        ))
        val column = region("行こう", RectF(65f, 105f, 90f, 255f))
        assertEquals(2, AdvancedTranslationEngine().mergeLikelySameBalloon(listOf(caption, column)).size)
    }

    @Test fun stackedJapaneseGlyphFragmentsDoNotSplitAfterColumnDetection() {
        val glyphs = "こんにちは".mapIndexed { index, character ->
            region(character.toString(), RectF(100f, 100f + index * 35f, 125f, 125f + index * 35f))
        }
        val merged = AdvancedTranslationEngine().mergeLikelySameBalloon(glyphs).single()
        assertEquals("こんにちは", merged.source.filterNot(Char::isWhitespace))
        assertEquals(5, merged.lineBounds.size)
    }

    @Test fun autoReadsSeparatedLatinAndJapaneseDialogue() = runBlocking {
        val bitmap = Bitmap.createBitmap(1500, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f }
        canvas.drawText("Please wait for me.", 70f, 190f, paint)
        canvas.drawText("こんにちは 世界", 70f, 590f, paint)
        try {
            val regions = AdvancedTranslationEngine().recognizeScriptAware(bitmap, AdvancedTranslationEngine.OcrOptions("AUTO", true))
            val source = regions.joinToString(" ") { it.source }
            assertTrue("Mixed OCR omitted Latin dialogue: $source", source.contains("wait", true))
            assertTrue("Mixed OCR omitted Japanese dialogue: $source", source.contains("こんにちは"))
            assertValidGeometry(regions, bitmap)
        } finally { bitmap.recycle() }
    }

    @Test fun verticalJapaneseUsesGlyphWidthAndPreservesTheWholeColumn() = runBlocking {
        val bitmap = Bitmap.createBitmap(900, 750, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f }
        "こんにちは".forEachIndexed { index, character ->
            canvas.drawText(character.toString(), 600f, 130f + index * 105f, paint)
        }
        try {
            val regions = AdvancedTranslationEngine().recognizeScriptAware(bitmap, AdvancedTranslationEngine.OcrOptions("JAPANESE", false))
            val source = regions.joinToString("") { it.source }.filterNot(Char::isWhitespace)
            assertTrue("Vertical Japanese reading: $source", source.contains("こんにちは"))
            assertTrue("Vertical lettering must use glyph size, not column height: $regions", regions.all { it.textSize <= 110f })
            assertValidGeometry(regions, bitmap)
        } finally { bitmap.recycle() }
    }

    @Test fun aPageCapturesPreferencesBeforeProcessingItsFirstTile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("mangalens_ocr", 0)
        val oldScript = prefs.getString("script", "AUTO")
        val oldAccuracy = prefs.getBoolean("high_accuracy", true)
        prefs.edit().putString("script", "LATIN").putBoolean("high_accuracy", false).commit()
        val bitmap = Bitmap.createBitmap(1200, 4300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 70f }
        canvas.drawText("Please protect the children.", 50f, 220f, paint)
        canvas.drawText("We will meet at the gate.", 50f, 3820f, paint)
        val engine = AdvancedTranslationEngine(context).apply {
            tileObserver = { _, _ -> prefs.edit().putString("script", "JAPANESE").commit() }
        }
        try {
            val regions = engine.recognizeScriptAware(bitmap)
            assertTrue("Both story panels should survive: $regions", regions.size >= 2)
            assertTrue("Changing preferences must not change an active page's OCR configuration", regions.all { it.recognizerScript == "LATIN" })
            assertValidGeometry(regions, bitmap)
        } finally {
            bitmap.recycle()
            prefs.edit().putString("script", oldScript).putBoolean("high_accuracy", oldAccuracy).commit()
        }
    }

    private fun region(source: String, bounds: RectF) = TranslationRegion(
        source, source, bounds, LocalSourceLanguage.JAPANESE, Color.BLACK, Color.WHITE,
        25f, listOf(bounds), .9f, "JAPANESE"
    )

    private fun assertValidGeometry(regions: List<TranslationRegion>, bitmap: Bitmap) {
        assertTrue(regions.isNotEmpty())
        regions.forEach { region ->
            (listOf(region.bounds) + region.lineBounds).forEach { box ->
                assertTrue("Invalid source geometry: $box", box.width() > 0f && box.height() > 0f &&
                    box.left >= 0f && box.top >= 0f && box.right <= bitmap.width && box.bottom <= bitmap.height)
            }
        }
    }
}
