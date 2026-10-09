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
    @Test fun expandedPatchDoesNotSampleGreyPanelAsWhitePaper() {
        val image = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        val panel = Color.rgb(235, 235, 235)
        Canvas(image).apply {
            drawColor(panel)
            drawRect(200f, 140f, 600f, 260f, Paint().apply { color = Color.WHITE })
            drawRect(280f, 185f, 520f, 215f, Paint().apply { color = Color.BLACK })
        }
        val bounds = RectF(260f, 175f, 540f, 225f)
        val patch = MangaLettering.prepare(image, bounds, listOf(bounds), "SOURCE TEXT", false)
        try {
            assertEquals("Grey expanded perimeter contaminated erased source ink", Color.WHITE,
                patch.background.getPixel(400 - patch.bounds.left, 200 - patch.bounds.top))
            val cleaned = image.copy(Bitmap.Config.ARGB_8888, true)
            try {
                MangaLettering.drawBackground(Canvas(cleaned), patch)
                assertEquals(Color.WHITE, cleaned.getPixel(400, 200))
                assertEquals("Unmasked panel texture changed", panel, cleaned.getPixel(20, 20))
            } finally { cleaned.recycle() }
        } finally { patch.background.recycle(); image.recycle() }
    }

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

    @Test fun sourceRemovalReconstructsTintGradientAtImageEdges() {
        val image = Bitmap.createBitmap(400, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        for (y in 0 until image.height) canvas.drawRect(0f, y.toFloat(), 400f, y + 1f,
            Paint().apply { color = Color.rgb(210 + y / 8, 200 + y / 8, 180 + y / 8) })
        val source = "DIALOGUE"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 38f }
        val glyph = Rect(); paint.getTextBounds(source, 0, source.length, glyph)
        canvas.drawText(source, 0f, 100f, paint)
        val bounds = RectF(glyph).apply { offset(0f, 100f) }
        val patch = MangaLettering.prepare(image, bounds, listOf(bounds), source, false)
        try {
            val top = patch.background.getPixel(patch.background.width / 2, 1)
            val bottom = patch.background.getPixel(patch.background.width / 2, patch.background.height - 2)
            assertTrue("Gradient collapsed to a flat rectangle", Color.red(bottom) > Color.red(top))
            for (y in 0 until patch.background.height) for (x in 0 until patch.background.width) {
                val pixel = patch.background.getPixel(x, y)
                assertTrue("Original dark text survived", Color.red(pixel) > 200)
                assertEquals(255, Color.alpha(pixel))
            }
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
            val candidates = mutableListOf<String>()
            val engine = AdvancedTranslationEngine(context).apply {
                tileObserver = { offset, regions -> candidates += "$offset: $regions" }
            }
            val regions = engine.recognizeScriptAware(image)
            assertEquals("Tall OCR regions: $regions", 2, regions.size)
            assertTrue("Tile-boundary OCR regions: $regions; candidates: $candidates", regions.any { it.source.contains("semester", true) && it.bounds.top > 1900 })
            assertTrue("Bottom OCR regions: $regions", regions.any { it.source.contains("graduation", true) && it.bounds.top > 4400 })
        } finally { image.recycle(); prefs.edit().putString("script", script).putBoolean("high_accuracy", accuracy).commit() }
    }

    @Test fun splitSpeechBalloonBlocksMergeBeforeTranslation() {
        val engine = AdvancedTranslationEngine()
        val first = com.mangalens.engine.TranslationRegion(
            source = "THEY SAID THE",
            translated = "THEY SAID THE",
            bounds = RectF(250f, 120f, 560f, 170f),
            sourceLanguage = com.mangalens.engine.LocalSourceLanguage.ENGLISH,
            textColor = Color.BLACK,
            backgroundColor = Color.WHITE,
            textSize = 42f,
            lineBounds = listOf(RectF(250f, 120f, 560f, 170f)),
            recognitionConfidence = .92f
        )
        val second = com.mangalens.engine.TranslationRegion(
            source = "WITCH OF THE FOREST WON'T INTERFERE",
            translated = "WITCH OF THE FOREST WON'T INTERFERE",
            bounds = RectF(210f, 182f, 600f, 268f),
            sourceLanguage = com.mangalens.engine.LocalSourceLanguage.ENGLISH,
            textColor = Color.BLACK,
            backgroundColor = Color.WHITE,
            textSize = 40f,
            lineBounds = listOf(
                RectF(230f, 182f, 580f, 222f),
                RectF(210f, 228f, 600f, 268f)
            ),
            recognitionConfidence = .90f
        )

        val merged = engine.mergeLikelySameBalloon(listOf(first, second))
        assertEquals(1, merged.size)
        assertTrue(merged.single().source.contains("THEY SAID THE"))
        assertTrue(merged.single().source.contains("WITCH OF THE FOREST"))
        assertEquals(3, merged.single().lineBounds.size)
    }

    @Test fun speechBubbleUsesRecoveredInteriorInsteadOfTinyOcrBox() {
        val image = Bitmap.createBitmap(900, 520, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val panel = Color.rgb(45, 48, 58)
        canvas.drawColor(panel)
        val bubble = RectF(110f, 70f, 790f, 440f)
        canvas.drawRoundRect(bubble, 150f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })
        canvas.drawRoundRect(bubble, 150f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 6f
        })

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        val sourceLines = listOf("THE WITCH OF THE FOREST", "WON'T INTERFERE AS LONG", "AS WE DON'T HARM IT")
        val baselines = listOf(220f, 278f, 336f)
        val lineBounds = sourceLines.zip(baselines).map { (text, baseline) ->
            val glyph = Rect()
            paint.getTextBounds(text, 0, text.length, glyph)
            val left = bubble.centerX() - glyph.width() / 2f
            canvas.drawText(text, left, baseline, paint)
            RectF(glyph).apply { offset(left, baseline) }
        }
        val sourceBounds = RectF(
            lineBounds.minOf { it.left }, lineBounds.minOf { it.top },
            lineBounds.maxOf { it.right }, lineBounds.maxOf { it.bottom }
        )

        val patch = MangaLettering.prepare(image, sourceBounds, lineBounds, sourceLines.joinToString("\n"))
        try {
            assertTrue("Writable area stayed trapped in the OCR glyph box", patch.bounds.width() > sourceBounds.width() * 1.12f)
            assertTrue("Writable area did not recover vertical balloon space", patch.bounds.height() > sourceBounds.height() * 1.20f)
            assertTrue("Recovered patch crossed into panel artwork", patch.bounds.left > 95 && patch.bounds.right < 805)

            val result = image.copy(Bitmap.Config.ARGB_8888, true)
            try {
                MangaLettering.draw(Canvas(result), patch,
                    "उन्होंने कहा कि जंगल की चुड़ैल तब तक हस्तक्षेप नहीं करेगी जब तक हम जंगल को नुकसान नहीं पहुँचाते।")
                assertEquals("Panel art outside the balloon changed", panel, result.getPixel(40, 40))
                assertFalse("Translated balloon did not change", image.sameAs(result))
            } finally { result.recycle() }
        } finally {
            patch.background.recycle()
            image.recycle()
        }
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
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/mangalens-qa/")
    }
}

