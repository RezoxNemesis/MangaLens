package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.TranslationService
import com.mangalens.engine.AdvancedTranslationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real bundled OCR engine and downloaded on-device English/Hindi translation model. */
@RunWith(AndroidJUnit4::class)
class ChapterTranslationTest {
    @Test fun twoPageChapterRecognizesTranslatesAndRendersHindi() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        val oldScript = prefs.getString("script", "AUTO")
        val oldAccuracy = prefs.getBoolean("high_accuracy", true)
        prefs.edit().putString("script", "LATIN").putBoolean("high_accuracy", false).commit()
        val ocr = AdvancedTranslationEngine(context)
        val translator = TranslationService()
        try {
            withTimeout(180_000) {
                for (text in listOf("Hello, how are you today?", "Thank you for your help.")) {
                    val bitmap = Bitmap.createBitmap(1400, 360, Bitmap.Config.ARGB_8888)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 76f }
                    Canvas(bitmap).apply { drawColor(Color.WHITE); drawText(text, 50f, 200f, paint) }
                    try {
                        val regions = ocr.recognizeScriptAware(bitmap)
                        assertTrue("Fixture page has no recognized text", regions.isNotEmpty())
                        val translated = regions.map { region ->
                            val hindi = translator.translate(region.source, "hi")
                            assertTrue("Translation did not produce Hindi: $hindi", hindi.any { it in '\u0900'..'\u097f' })
                            assertNotEquals(region.source, hindi)
                            region.copy(translated = hindi, textColor = Color.BLACK, backgroundColor = Color.WHITE)
                        }
                        val rendered = ocr.render(bitmap, translated)
                        try {
                            assertFalse("Hindi translation was not rendered", bitmap.sameAs(rendered))
                            assertEquals(bitmap.getPixel(0, 0), rendered.getPixel(0, 0))
                        } finally { rendered.recycle() }
                    } finally { bitmap.recycle() }
                }
            }
        } finally {
            ocr.close(); translator.close()
            prefs.edit().putString("script", oldScript).putBoolean("high_accuracy", oldAccuracy).commit()
        }
    }
}
