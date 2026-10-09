package com.mangalens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mangalens.engine.AdvancedTranslationEngine
import com.mangalens.engine.LocalSourceLanguage
import com.mangalens.engine.OcrBox
import com.mangalens.engine.OcrReading
import com.mangalens.engine.OcrRecognizerSession
import com.mangalens.engine.OcrSourceQuality
import com.mangalens.engine.TranslationRegion
import com.mangalens.engine.awaitOcrCompletion
import com.mangalens.engine.chooseOcrRetryReadingGroup
import com.mangalens.engine.composeOcrRetryReading
import com.mangalens.engine.planContextualOcrRetry
import com.mangalens.engine.planOcrRetryWithScale
import com.mangalens.engine.renderContextualOcrRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor

/** Original-pixel geometry experiment. Unchanged UserSourceOcrQualityTest remains the quality gate. */
@RunWith(AndroidJUnit4::class)
class UserSourceOcrContextualDiagnosticTest {
    @Test fun comparesAnnotationTightAndContextualCropFromActualNativeBaseline() = runBlocking {
        inspect("annotation", 315, 4200, 405, 160)
    }

    @Test fun comparesApologyTightAndContextualCropFromActualNativeBaseline() = runBlocking {
        inspect("apology", 200, 6150, 500, 350)
    }

    private suspend fun inspect(id: String, x: Int, y: Int, width: Int, height: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = File(InstrumentationRegistry.getArguments().getString("real_manhwa_ocr_fixture")
            ?: "/sdcard/Download/mangalens-qa/user-fixtures/manhwa-reader002.jpg")
        assertTrue("Supply the recorded original JPEG: $fixture", fixture.isFile)
        assertEquals(SOURCE_SHA256, sha256(fixture.readBytes()))
        val page = requireNotNull(BitmapFactory.decodeFile(fixture.absolutePath))
        val crop = try {
            assertEquals(720, page.width); assertEquals(9170, page.height)
            Bitmap.createBitmap(page, x, y, width, height)
        } finally { page.recycle() }
        val before = pixels(crop)
        val output = File(instrumentation.targetContext.filesDir, "qa/ocr-user-source-contextual/$id.json")
        val observations = JSONArray()
        val report = JSONObject().put("fixture_sha256", SOURCE_SHA256)
            .put("crop", JSONArray(listOf(x, y, width, height)))
            .put("measurement_only", true).put("full_page_quality_proven", false)
            .put("selection_measurement_only", true).put("max_variant_pixels", 1_000_000)
            .put("observations", observations).put("status", "running")
        fun persist() { output.parentFile?.mkdirs(); output.writeText(report.toString(2)) }
        val engine = AdvancedTranslationEngine()
        val session = OcrRecognizerSession({ _: String -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }) { it.close() }
        try {
            persist()
            val baseline = readNative(session, crop, engine)
            report.put("baseline", nativeJson(baseline)).put("baseline_input_size", JSONArray(listOf(width, height)))
            persist()
            for ((index, region) in baseline.regions.withIndex()) {
                currentCoroutineContext().ensureActive()
                val original = descriptor(region)
                if (!OcrSourceQuality.needsPixelRetry(original.source)) continue
                val neighbors = baseline.regions.indices.filter { it != index }.map { descriptor(baseline.regions[it]) }
                val tight = planOcrRetryWithScale(original.bounds, width, height, original.textSize,
                    maxPixels = 1_000_000, maxDimension = 1280, maxScale = 3f)
                val contextual = planContextualOcrRetry(original, width, height, neighbors.map { it.bounds })
                for (label in listOf("tight-region-scale", "contextual-scale")) {
                    val bounds = if (label == "tight-region-scale") tight?.crop else contextual?.crop
                    if (bounds == null) {
                        observations.put(JSONObject().put("region_index", index).put("variant", label).put("status", "no_bounded_plan"))
                        persist(); continue
                    }
                    val source = Bitmap.createBitmap(crop, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
                    try {
                        val image = if (label == "tight-region-scale") Bitmap.createScaledBitmap(source,
                            requireNotNull(tight).scaledWidth, tight.scaledHeight, true)
                        else renderContextualOcrRetry(source, requireNotNull(contextual).pixels)
                        val observation = JSONObject().put("region_index", index).put("variant", label)
                            .put("original", regionJson(region)).put("input_crop", boxJson(bounds))
                            .put("input_size", JSONArray(listOf(image.width, image.height)))
                        observations.put(observation)
                        try {
                            assertTrue(image.width <= 1280 && image.height <= 1280)
                            assertTrue(image.width.toLong() * image.height <= 1_000_000L)
                            val fresh = readNative(session, image, engine)
                            val mapped = fresh.regions.mapNotNull { item ->
                                fun map(box: RectF): RectF? {
                                    val input = OcrBox(box.left, box.top, box.right, box.bottom)
                                    val result = if (label == "tight-region-scale") requireNotNull(tight).map(input)
                                        else requireNotNull(contextual).map(input) ?: return null
                                    return RectF(result.left, result.top, result.right, result.bottom)
                                }
                                val mappedBounds = map(item.bounds) ?: return@mapNotNull null
                                assertTrue(mappedBounds.left >= 0f && mappedBounds.top >= 0f &&
                                    mappedBounds.right <= width && mappedBounds.bottom <= height)
                                item.copy(bounds = mappedBounds, lineBounds = item.lineBounds.mapNotNull(::map),
                                    textSize = item.textSize / if (label == "tight-region-scale")
                                        minOf(requireNotNull(tight).scaleX, tight.scaleY) else requireNotNull(contextual).pixels.transform.a)
                            }
                            val selected = chooseOcrRetryReadingGroup(original, mapped.map(::descriptor), neighbors)
                            observation.put("native", nativeJson(fresh))
                                .put("mapped_regions", JSONArray(mapped.map { mappedRegion ->
                                    regionJson(mappedRegion).put("bounds_page", boxJson(OcrBox(mappedRegion.bounds.left + x,
                                        mappedRegion.bounds.top + y, mappedRegion.bounds.right + x, mappedRegion.bounds.bottom + y)))
                                }))
                                .put("selected_indices", JSONArray(selected))
                                .put("replacement", if (selected.isEmpty()) JSONObject.NULL else
                                    composeOcrRetryReading(selected.map { descriptor(mapped[it]) }).source)
                                .put("status", "observed")
                            if (contextual != null && label == "contextual-scale") observation.put("uniform_scale", contextual.pixels.transform.a)
                        } catch (failure: Throwable) {
                            observation.put("status", "interrupted").put("failure", failure.toString().take(600))
                            throw failure
                        } finally {
                            // Native completion is the ownership barrier, including coroutine cancellation.
                            if (image !== source) image.recycle()
                            assertArrayEquals("Derived crop changed original pixels", before, pixels(crop))
                            persist()
                        }
                    } finally { if (source !== crop) source.recycle() }
                }
            }
            assertEquals(SOURCE_SHA256, sha256(fixture.readBytes()))
            report.put("status", "diagnostic_complete")
        } catch (failure: Throwable) {
            report.put("status", "interrupted").put("failure", failure.toString().take(600))
            throw failure
        } finally {
            try { session.close() } finally { engine.close(); crop.recycle(); persist() }
        }
    }

    private data class NativeReading(val raw: String, val regions: List<TranslationRegion>, val elapsedMs: Long)

    private suspend fun readNative(session: OcrRecognizerSession<com.google.mlkit.vision.text.TextRecognizer>,
        bitmap: Bitmap, engine: AdvancedTranslationEngine): NativeReading {
        val began = SystemClock.elapsedRealtime()
        val text = session.read("LATIN") { recognizer ->
            awaitOcrCompletion<Text> { complete ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(Executor { it.run() }) { task ->
                    complete(when {
                        task.isSuccessful -> runCatching { requireNotNull(task.result) }
                        task.isCanceled -> Result.failure(CancellationException("Native OCR cancelled"))
                        else -> Result.failure(task.exception ?: IllegalStateException("Native OCR failed"))
                    })
                }
            }
        }
        val regions = text.textBlocks.mapNotNull { block ->
            val bounds = block.boundingBox ?: return@mapNotNull null
            if (block.text.isBlank()) return@mapNotNull null
            val lines = block.lines.mapNotNull { line -> line.boundingBox?.let { RectF(it) } }
            val confidence = block.lines.map { it.confidence }.filter { it.isFinite() && it > 0f }.average()
                .takeIf(Double::isFinite)?.toFloat() ?: 0f
            val size = lines.map { it.height() }.average().takeIf(Double::isFinite)?.toFloat()?.coerceAtLeast(12f) ?: 12f
            val source = OcrSourceQuality.normalizeLatinSource(block.text.trim())
            TranslationRegion(source, source, RectF(bounds), engine.detect(source), Color.BLACK, Color.WHITE,
                size, lines, confidence, "LATIN")
        }
        return NativeReading(text.text, engine.mergeLikelySameBalloon(regions), SystemClock.elapsedRealtime() - began)
    }

    private fun descriptor(region: TranslationRegion) = OcrReading(region.source, "LATIN", region.recognitionConfidence,
        OcrBox(region.bounds.left, region.bounds.top, region.bounds.right, region.bounds.bottom),
        plausible = region.sourceLanguage != LocalSourceLanguage.ENGLISH || region.recognitionConfidence == 0f || region.recognitionConfidence >= .30f,
        textSize = region.textSize, lineBounds = region.lineBounds.map { OcrBox(it.left, it.top, it.right, it.bottom) })
    private fun regionJson(region: TranslationRegion) = JSONObject().put("source", region.source)
        .put("confidence", region.recognitionConfidence).put("text_size", region.textSize)
        .put("needs_pixel_retry", OcrSourceQuality.needsPixelRetry(region.source))
        .put("bounds_crop", boxJson(OcrBox(region.bounds.left, region.bounds.top, region.bounds.right, region.bounds.bottom)))
    private fun nativeJson(reading: NativeReading) = JSONObject().put("raw_source", reading.raw)
        .put("regions", JSONArray(reading.regions.map(::regionJson))).put("elapsed_ms", reading.elapsedMs)
    private fun boxJson(box: OcrBox) = JSONArray(listOf(box.left, box.top, box.right, box.bottom))
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object {
        const val SOURCE_SHA256 = "f05f4a93fac66b985657c07426a79fde3b6bfeb7e433344b61ee53f1d9f0ae16"
    }
}
