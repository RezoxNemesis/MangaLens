package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.engine.AdvancedTranslationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultilingualOcrTest {
    @Test fun japaneseRecognizerReadsKana() = checkScript("JAPANESE", "こんにちは 世界", "こんにちは")
    @Test fun chineseRecognizerReadsHan() = checkScript("CHINESE", "你好 世界", "你好")
    @Test fun koreanRecognizerReadsHangul() = checkScript("KOREAN", "안녕하세요 세계", "안녕")
    @Test fun devanagariRecognizerReadsHindi() = checkScript("DEVANAGARI", "नमस्ते दुनिया", "नमस्ते")

    @Test fun autoModePrefersConfidentLatinInsteadOfLongWrongScriptOutput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        val oldScript = prefs.getString("script", "AUTO")
        val oldAccuracy = prefs.getBoolean("high_accuracy", true)
        prefs.edit().putString("script", "AUTO").putBoolean("high_accuracy", true).commit()
        val bitmap = Bitmap.createBitmap(1500, 420, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 27, 25)
            textSize = 78f
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.ITALIC)
        }
        Canvas(bitmap).apply {
            drawColor(Color.rgb(219, 212, 198))
            drawText("THE SECOND SEMESTER OF MY FOURTH YEAR.", 65f, 235f, paint)
        }
        val engine = AdvancedTranslationEngine(context)
        try {
            val regions = engine.recognizeScriptAware(bitmap)
            val actual = regions.joinToString(" ") { it.source }
            assertTrue("AUTO OCR returned: $actual", actual.contains("SECOND", true))
            assertTrue("AUTO OCR returned: $actual", actual.contains("SEMESTER", true))
            assertTrue("AUTO OCR returned too little readable Latin text: $actual", actual.count { it.isLetter() } >= 20)
            assertFalse("Wrong-script Devanagari hallucination: $actual", actual.any { it in '\u0900'..'\u097f' })
            assertFalse("Wrong-script kana hallucination: $actual", actual.any { it in '\u3040'..'\u30ff' })
            assertFalse("Wrong-script Hangul hallucination: $actual", actual.any { it in '\uac00'..'\ud7af' })
        } finally {
            engine.close()
            bitmap.recycle()
            prefs.edit().putString("script", oldScript).putBoolean("high_accuracy", oldAccuracy).commit()
        }
    }

    private fun checkScript(script: String, text: String, expected: String) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        val oldScript = prefs.getString("script", "AUTO")
        val oldAccuracy = prefs.getBoolean("high_accuracy", true)
        prefs.edit().putString("script", script).putBoolean("high_accuracy", true).commit()
        val bitmap = Bitmap.createBitmap(1400, 360, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f }
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText(text, 70f, 200f, paint) }
        val engine = AdvancedTranslationEngine(context)
        try {
            val regions = engine.recognizeScriptAware(bitmap)
            val actual = regions.joinToString(" ") { it.source }
            assertTrue("$script OCR returned: $actual", actual.contains(expected))
            assertTrue(regions.all { it.bounds.width() > 0 && it.bounds.height() > 0 &&
                it.bounds.left >= 0 && it.bounds.top >= 0 && it.bounds.right <= bitmap.width && it.bounds.bottom <= bitmap.height })
            val rendered = engine.render(bitmap, regions.map { it.copy(translated = "Hello", textColor = Color.BLACK, backgroundColor = Color.WHITE) })
            try {
                assertFalse("Rendering must change the translated region", bitmap.sameAs(rendered))
                assertEquals("Rendering must preserve pixels outside text regions", bitmap.getPixel(0, 0), rendered.getPixel(0, 0))
            } finally { rendered.recycle() }
        } finally {
            engine.close(); bitmap.recycle()
            prefs.edit().putString("script", oldScript).putBoolean("high_accuracy", oldAccuracy).commit()
        }
    }
}
