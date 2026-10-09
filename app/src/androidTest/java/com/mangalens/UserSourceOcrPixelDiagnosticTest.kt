package com.mangalens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mangalens.engine.OcrBox
import com.mangalens.engine.OcrPixelVariantPlan
import com.mangalens.engine.OcrRecognizerSession
import com.mangalens.engine.OcrSourceQuality
import com.mangalens.engine.awaitOcrCompletion
import com.mangalens.engine.originalPixelDiagnosticVariants
import com.mangalens.engine.planOriginalPixelVariant
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
import java.util.Locale
import java.util.concurrent.Executor

/** Measurement only: preserves every native hypothesis; does not change production selection. */
@RunWith(AndroidJUnit4::class)
class UserSourceOcrPixelDiagnosticTest {
    @Test fun recordsOriginalAnnotationPixelHypotheses() = runBlocking {
        inspectOriginalPixels("annotation", 315, 4200, 405, 160)
    }

    @Test fun recordsOriginalApologyPixelHypotheses() = runBlocking {
        inspectOriginalPixels("apology", 200, 6150, 500, 350)
    }

    private suspend fun inspectOriginalPixels(id: String, x: Int, y: Int, width: Int, height: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val fixture = File(args.getString("real_manhwa_ocr_fixture")
            ?: "/sdcard/Download/mangalens-qa/user-fixtures/manhwa-reader002.jpg")
        assertTrue("Supply the recorded original JPEG through real_manhwa_ocr_fixture: $fixture", fixture.isFile)
        assertEquals(SOURCE_SHA256, sha256(fixture.readBytes()))
        val requested = args.getString("ocr_pixel_variants")?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.toSet()
        if (requested != null) assertTrue("Unknown pixel hypothesis: $requested",
            requested.isNotEmpty() && originalPixelDiagnosticVariants.map { it.label }.containsAll(requested))
        val variants = originalPixelDiagnosticVariants.filter { requested == null || it.label in requested }
        val page = requireNotNull(BitmapFactory.decodeFile(fixture.absolutePath))
        val crop = try {
            assertEquals(720, page.width); assertEquals(9170, page.height)
            Bitmap.createBitmap(page, x, y, width, height)
        } finally { page.recycle() }
        val before = pixels(crop)
        val observations = JSONArray()
        val report = JSONObject().put("fixture_sha256", SOURCE_SHA256)
            .put("crop", JSONArray(listOf(x, y, width, height)))
            .put("measurement_only", true).put("production_selection_changed", false)
            .put("max_variant_pixels", 1_000_000).put("max_variant_dimension", 1280)
            .put("observations", observations).put("status", "running")
        val output = File(instrumentation.targetContext.filesDir, "qa/ocr-user-source-pixels/$id.json")
        fun persist() { output.parentFile?.mkdirs(); output.writeText(report.toString(2)) }
        val session = OcrRecognizerSession({ _: String -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }) { it.close() }
        try {
            persist()
            for (spec in variants) {
                currentCoroutineContext().ensureActive()
                val plan = requireNotNull(planOriginalPixelVariant(width, height, spec))
                // Borrow the original crop for the baseline, with no resampling or color conversion.
                val image = if (spec.label == "original") crop else renderVariant(crop, plan)
                val observation = JSONObject().put("variant", spec.label)
                    .put("output_size", JSONArray(listOf(plan.outputWidth, plan.outputHeight)))
                    .put("clockwise_degrees", spec.clockwiseDegrees).put("shear_x", spec.shearX)
                    .put("grayscale", spec.grayscale).put("contrast", spec.contrast)
                    .put("transform", JSONArray(listOf(plan.transform.a, plan.transform.b, plan.transform.c,
                        plan.transform.d, plan.transform.tx, plan.transform.ty)))
                val began = SystemClock.elapsedRealtime()
                try {
                    assertTrue(image.width <= 1280 && image.height <= 1280)
                    assertTrue(image.width.toLong() * image.height <= 1_000_000L)
                    val text = session.read("LATIN") { recognizer ->
                        awaitOcrCompletion<Text> { completed ->
                            recognizer.process(InputImage.fromBitmap(image, 0))
                                .addOnCompleteListener(Executor { it.run() }) { task ->
                                    completed(when {
                                        task.isSuccessful -> runCatching { requireNotNull(task.result) }
                                        task.isCanceled -> Result.failure(CancellationException("Native OCR was cancelled"))
                                        else -> Result.failure(task.exception ?: IllegalStateException("Native OCR failed"))
                                    })
                                }
                        }
                    }
                    val source = OcrSourceQuality.normalizeLatinSource(text.text)
                    observation.put("raw_source", text.text).put("normalized_source", source)
                        .put("needs_pixel_retry", OcrSourceQuality.needsPixelRetry(source))
                        .put("blocks", JSONArray(text.textBlocks.map { block ->
                            val mapped = block.boundingBox?.let { plan.mapBounds(OcrBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())) }
                            if (mapped != null) assertTrue(mapped.left >= 0f && mapped.top >= 0f && mapped.right <= width && mapped.bottom <= height)
                            val confidence = block.lines.map { it.confidence }.filter { it.isFinite() && it > 0f }
                            JSONObject().put("source", block.text)
                                .put("confidence", confidence.average().takeIf(Double::isFinite) ?: 0.0)
                                .put("bounds_crop", mapped?.let(::boundsJson) ?: JSONObject.NULL)
                                .put("bounds_page", mapped?.let { boundsJson(OcrBox(it.left + x, it.top + y, it.right + x, it.bottom + y)) } ?: JSONObject.NULL)
                        }))
                        .put("manual_reference_anchors_present", referenceAnchorsPresent(id, source))
                        .put("status", "observed")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    observation.put("status", "native_error").put("failure", failure.toString().take(600))
                } finally {
                    // awaitOcrCompletion retains this image and the recognizer through native return.
                    observation.put("elapsed_ms", SystemClock.elapsedRealtime() - began)
                    observations.put(observation)
                    if (image !== crop) image.recycle()
                    assertArrayEquals("A pixel hypothesis changed the original crop", before, pixels(crop))
                    persist()
                }
            }
            assertEquals(variants.size, observations.length())
            assertEquals(SOURCE_SHA256, sha256(fixture.readBytes()))
            report.put("status", "diagnostic_complete")
        } catch (failure: Throwable) {
            report.put("status", "interrupted").put("failure", failure.toString().take(600))
            throw failure
        } finally {
            try { session.close() } finally { crop.recycle(); persist() }
        }
    }

    private fun renderVariant(source: Bitmap, plan: OcrPixelVariantPlan): Bitmap {
        val output = Bitmap.createBitmap(plan.outputWidth, plan.outputHeight, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (plan.spec.grayscale || plan.spec.contrast != 1f) {
                val matrix = ColorMatrix().apply { if (plan.spec.grayscale) setSaturation(0f) }
                val contrast = plan.spec.contrast; val shift = 128f * (1f - contrast)
                matrix.postConcat(ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, shift,
                    0f, contrast, 0f, 0f, shift, 0f, 0f, contrast, 0f, shift, 0f, 0f, 0f, 1f, 0f)))
                paint.colorFilter = ColorMatrixColorFilter(matrix)
            }
            val t = plan.transform
            val geometry = Matrix().apply { setValues(floatArrayOf(t.a, t.b, t.tx, t.c, t.d, t.ty, 0f, 0f, 1f)) }
            Canvas(output).apply { drawColor(Color.WHITE); drawBitmap(source, geometry, paint) }
            return output
        } catch (failure: Throwable) { output.recycle(); throw failure }
    }

    /** These test-only reference checks never feed the recognizer or select a hypothesis. */
    private fun referenceAnchorsPresent(id: String, source: String): Boolean {
        if (OcrSourceQuality.needsPixelRetry(source)) return false
        val value = source.uppercase(Locale.ROOT)
        return if (id == "annotation") Regex("OTHER\\s+WORLD").containsMatchIn(value) && "AKALIFA" in value
        else "YEORUM" in value && "SORRY" in value && Regex("I['’]M").containsMatchIn(value) && Regex("\\bLATE\\b").containsMatchIn(value)
    }

    private fun boundsJson(box: OcrBox) = JSONArray(listOf(box.left, box.top, box.right, box.bottom))
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object {
        const val SOURCE_SHA256 = "f05f4a93fac66b985657c07426a79fde3b6bfeb7e433344b61ee53f1d9f0ae16"
    }
}
