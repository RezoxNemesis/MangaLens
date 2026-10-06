package com.mangalens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.MangaLettering
import com.mangalens.core.translation.TranslationService
import com.mangalens.engine.AdvancedTranslationEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Private clean-source screenshots are staged on the device, never committed as assets. */
@RunWith(AndroidJUnit4::class)
class UploadedMangaTest {
    @Test fun cleanScreenshotsRecognizeTranslateAndReplaceInsideBubbles() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val directory = args.getString("sample_manga_dir")
        assumeTrue("Stage sample_manga_dir for real manga acceptance", directory != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("mangalens_ocr", 0)
        val script = prefs.getString("script", "AUTO")
        val accuracy = prefs.getBoolean("high_accuracy", true)
        prefs.edit().putString("script", "AUTO").putBoolean("high_accuracy", true).commit()
        val engine = AdvancedTranslationEngine(context)
        val translator = TranslationService()
        val output = File(context.getExternalFilesDir(null), "uploaded-manga").apply { mkdirs() }
        try {
            withTimeout(240_000) {
                val expected = listOf("WORRY ABOUT", "AT RISK", "ACTUAL ACCIDENT")
                expected.forEachIndexed { index, phrase ->
                    val file = File(directory!!, "${index + 1}.jpg")
                    val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
                    try {
                        // Exclude browser chrome; check only the supplied chapter area.
                        val regions = engine.recognizeScriptAware(bitmap).filter {
                            it.bounds.top >= 165 && it.bounds.bottom <= 1430
                        }
                        val recognized = regions.joinToString("\n") { it.source }
                        assertTrue("Missing '$phrase' in $recognized", recognized.replace('\n', ' ').contains(phrase, true))
                        val translated = regions.map { region ->
                            val hindi = translator.translate(region.source, "hi")
                            region.copy(translated = hindi)
                        }
                        assertTrue("No Hindi translation: $translated", translated.any {
                            it.translated.any { letter -> letter in '\u0900'..'\u097f' }
                        })
                        val rendered = engine.render(bitmap, translated)
                        try {
                            assertFalse("No replacement lettering was drawn", bitmap.sameAs(rendered))
                            val remainingLatin = engine.recognize(rendered).joinToString(" ") { it.source.replace('\n', ' ') }
                            assertFalse("Original dialogue is still readable under the translation: $remainingLatin",
                                remainingLatin.contains(phrase, true))
                            // Erasing/relettering must not modify artwork outside OCR patches.
                            val boxes = regions.map {
                                val patch = MangaLettering.prepare(bitmap, it.bounds, it.lineBounds, it.source)
                                try { Rect(patch.bounds) } finally { patch.background.recycle() }
                            }
                            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                                if (boxes.none { it.contains(x, y) })
                                    assertEquals("Artwork changed outside text at $x,$y", bitmap.getPixel(x, y), rendered.getPixel(x, y))
                            }
                            File(output, "${index + 1}-Hindi.png").outputStream().use {
                                rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            File(output, "${index + 1}-text.txt").writeText(translated.joinToString("\n\n") {
                                "${it.bounds}\n${it.source}\n${it.translated}"
                            })
                        } finally { rendered.recycle() }
                    } finally { bitmap.recycle() }
                }
            }
        } finally {
            engine.close(); translator.close()
            prefs.edit().putString("script", script).putBoolean("high_accuracy", accuracy).commit()
        }
    }
}
