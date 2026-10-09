package com.mangalens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.engine.AdvancedTranslationEngine
import com.mangalens.engine.OcrSourceQuality
import com.mangalens.engine.TranslationRegion
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Opt-in actual supplied-source fixture gate. Root supplies the recorded original JPEG. */
@RunWith(AndroidJUnit4::class)
class UserSourceOcrQualityTest {
    @Test fun overArtworkAnnotationRecoversItsWorldAndNameFromOriginalPixels() = runBlocking {
        readOriginalCrop("annotation", 315, 4200, 405, 160) { regions ->
            val raw = regions.joinToString(" ") { it.source }
            val source = raw.lowercase(Locale.ROOT)
            assertTrue("Missing other-world meaning: $source", Regex("other\\s+world").containsMatchIn(source))
            assertTrue("Missing visually supplied proper name: $source", source.contains("akalifa"))
            assertFalse("Annotation remains uncertain: $raw", OcrSourceQuality.needsPixelRetry(raw))
        }
    }

    @Test fun slantedApologyKeepsTheNameAndRecoversLateWithoutGuessingTheSource() = runBlocking {
        readOriginalCrop("apology", 200, 6150, 500, 350) { regions ->
            val source = regions.joinToString(" ") { it.source }.uppercase(Locale.ROOT)
            assertTrue("Missing addressee name: $source", source.contains("YEORUM"))
            assertTrue("Missing apology: $source", source.contains("SORRY"))
            assertTrue("Missing copula: $source", Regex("I['’]M").containsMatchIn(source))
            assertTrue("Wrong adjective retained: $source", Regex("\\bLATE\\b").containsMatchIn(source))
            assertFalse("Apology remains uncertain: $source", OcrSourceQuality.needsPixelRetry(source))
        }
    }

    private suspend fun readOriginalCrop(
        id: String, x: Int, y: Int, width: Int, height: Int,
        assertion: (List<TranslationRegion>) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = File(InstrumentationRegistry.getArguments().getString("real_manhwa_ocr_fixture")
            ?: "/sdcard/Download/mangalens-qa/user-fixtures/manhwa-reader002.jpg")
        assertTrue("Supply host001.jpg with real_manhwa_ocr_fixture; no generated substitute: $fixture", fixture.isFile)
        assertEquals("Fixture must be the recorded supplied page", SOURCE_SHA256, sha256(fixture.readBytes()))
        val page = requireNotNull(BitmapFactory.decodeFile(fixture.absolutePath))
        val crop = try {
            assertEquals(720, page.width)
            assertEquals(9170, page.height)
            Bitmap.createBitmap(page, x, y, width, height)
        } finally { page.recycle() }
        val before = pixels(crop)
        val retries = JSONArray()
        val engine = AdvancedTranslationEngine().apply {
            regionRetryObserver = { original, readings, replacement ->
                retries.put(JSONObject().put("original", regionJson(original))
                    .put("readings", JSONArray(readings.map(::regionJson)))
                    .put("replacement", replacement?.let(::regionJson) ?: JSONObject.NULL))
            }
        }
        val report = JSONObject().put("fixture_sha256", SOURCE_SHA256)
            .put("crop", JSONArray(listOf(x, y, width, height))).put("retries", retries)
        try {
            val regions = engine.recognizeScriptAware(crop, AdvancedTranslationEngine.OcrOptions("LATIN", true))
            report.put("regions", JSONArray(regions.map(::regionJson)))
            assertArrayEquals("OCR must preserve original pixels", before, pixels(crop))
            regions.forEach { region ->
                assertTrue("Mapped retry bounds escape crop: ${region.bounds}",
                    region.bounds.left >= 0f && region.bounds.top >= 0f &&
                        region.bounds.right <= width && region.bounds.bottom <= height)
            }
            assertion(regions)
            report.put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            runCatching {
                val directory = File(instrumentation.targetContext.filesDir, "qa/ocr-user-source").apply { mkdirs() }
                File(directory, "$id.json").writeText(report.toString(2))
            }
            crop.recycle()
            engine.close()
        }
    }

    private fun regionJson(region: TranslationRegion): JSONObject = JSONObject()
        .put("source", region.source).put("script", region.recognizerScript)
        .put("confidence", region.recognitionConfidence).put("text_size", region.textSize)
        .put("needs_pixel_retry", OcrSourceQuality.needsPixelRetry(region.source))
        .put("bounds", JSONArray(listOf(region.bounds.left, region.bounds.top, region.bounds.right, region.bounds.bottom)))

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SOURCE_SHA256 = "f05f4a93fac66b985657c07426a79fde3b6bfeb7e433344b61ee53f1d9f0ae16"
    }
}
