package com.mangalens

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mangalens.core.translation.ChapterOriginalOcrSource
import com.mangalens.engine.OcrBox
import com.mangalens.engine.OcrSourceQuality
import com.mangalens.engine.OcrSourceResolutionProof
import com.mangalens.engine.OcrSourceResolutionRequest
import com.mangalens.engine.awaitOcrCompletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor

/** Actual original JPEG + region decoder + native OCR; diagnostic owner, not full-chapter acceptance. */
@RunWith(AndroidJUnit4::class)
class UserSourceOcrOriginalRegionDiagnosticTest {
    @Test fun measuresVerifiedOriginalAnnotationRegionWithActualBoundedPageMapping() = runBlocking {
        inspect("annotation", 315, 4200, 405, 160)
    }

    @Test fun measuresVerifiedOriginalApologyRegionWithActualBoundedPageMapping() = runBlocking {
        inspect("apology", 200, 6150, 500, 350)
    }

    private suspend fun inspect(id: String, x: Int, y: Int, width: Int, height: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = File(InstrumentationRegistry.getArguments().getString("real_manhwa_ocr_fixture")
            ?: "/sdcard/Download/mangalens-qa/user-fixtures/manhwa-reader002.jpg")
        assertTrue("Supply recorded original JPEG: $fixture", fixture.isFile)
        val originalBytes = fixture.readBytes()
        assertEquals(SOURCE_SHA256, sha256(originalBytes))
        val directory = File(instrumentation.targetContext.filesDir, "qa/ocr-original-source-${UUID.randomUUID()}").apply { mkdirs() }
        val privateSource = File(directory, "source.jpg").apply { writeBytes(originalBytes) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(privateSource.absolutePath, bounds)
        assertEquals(720, bounds.outWidth); assertEquals(9170, bounds.outHeight)
        // Actual decoder dimensions qualify the mapping; do not assume an exact integer scale.
        val bounded = requireNotNull(BitmapFactory.decodeFile(privateSource.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }))
        val proof = OcrSourceResolutionProof("a".repeat(32), "b".repeat(32), "c".repeat(32), 1,
            privateSource.canonicalPath, SOURCE_SHA256, bounds.outWidth, bounds.outHeight, bounded.width, bounded.height)
        val request = OcrSourceResolutionRequest(OcrBox(x * bounded.width.toFloat() / bounds.outWidth,
            y * bounded.height.toFloat() / bounds.outHeight, (x + width) * bounded.width.toFloat() / bounds.outWidth,
            (y + height) * bounded.height.toFloat() / bounds.outHeight), bounded.width, bounded.height)
        bounded.recycle()
        val report = JSONObject().put("fixture_sha256", SOURCE_SHA256).put("source_dimensions", JSONArray(listOf(bounds.outWidth, bounds.outHeight)))
            .put("reader_index", 2).put("canonical_source_index", proof.pageIndex)
            .put("bounded_page_dimensions", JSONArray(listOf(proof.decodedWidth, proof.decodedHeight)))
            .put("requested_original_crop", JSONArray(listOf(x, y, width, height))).put("requested_page_crop", boxJson(request.crop))
            .put("owner_scope", "diagnostic_private_source").put("measurement_only", true)
            .put("full_page_quality_proven", false).put("status", "running")
        val output = File(instrumentation.targetContext.filesDir, "qa/ocr-user-source-original-region/$id.json")
        fun persist() { output.parentFile?.mkdirs(); output.writeText(report.toString(2)) }
        try {
            persist()
            val consumed = ChapterOriginalOcrSource(proof, directory) { proof }.withCrop(request) { crop ->
                report.put("qualified_source_crop", boxJson(crop.plan.sourceCrop)).put("decode_sample", crop.plan.decodeSample)
                    .put("decoded_crop_size", JSONArray(listOf(crop.decodedCropWidth, crop.decodedCropHeight)))
                    .put("native_input_size", JSONArray(listOf(crop.bitmap.width, crop.bitmap.height)))
                    .put("uniform_scale", crop.pixels.transform.a)
                assertTrue(crop.bitmap.width <= 1280 && crop.bitmap.height <= 1280)
                assertTrue(crop.bitmap.width.toLong() * crop.bitmap.height <= 1_000_000)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val began = SystemClock.elapsedRealtime()
                val text = try {
                    awaitOcrCompletion<Text> { complete ->
                        recognizer.process(InputImage.fromBitmap(crop.bitmap, 0)).addOnCompleteListener(Executor { it.run() }) { task ->
                            complete(when {
                                task.isSuccessful -> runCatching { requireNotNull(task.result) }
                                task.isCanceled -> Result.failure(CancellationException("Native OCR cancelled"))
                                else -> Result.failure(task.exception ?: IllegalStateException("Native OCR failed"))
                            })
                        }
                    }
                } finally { recognizer.close(); report.put("native_elapsed_ms", SystemClock.elapsedRealtime() - began) }
                val normalized = OcrSourceQuality.normalizeLatinSource(text.text)
                report.put("raw_source", text.text).put("normalized_source", normalized)
                    .put("needs_pixel_retry", OcrSourceQuality.needsPixelRetry(normalized))
                    .put("blocks", JSONArray(text.textBlocks.map { block ->
                        val box = block.boundingBox?.let { crop.mapToPage(OcrBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())) }
                        val confidence = block.lines.map { it.confidence }.filter { it.isFinite() && it > 0f }.average().takeIf(Double::isFinite) ?: 0.0
                        if (box != null) assertTrue(box.left >= 0f && box.top >= 0f && box.right <= proof.decodedWidth && box.bottom <= proof.decodedHeight)
                        JSONObject().put("source", block.text).put("confidence", confidence).put("bounds_page", box?.let(::boxJson) ?: JSONObject.NULL)
                            .put("bounds_original", box?.let { boxJson(OcrBox(it.left * proof.originalWidth / proof.decodedWidth,
                                it.top * proof.originalHeight / proof.decodedHeight, it.right * proof.originalWidth / proof.decodedWidth,
                                it.bottom * proof.originalHeight / proof.decodedHeight)) } ?: JSONObject.NULL)
                    }))
                true
            }
            assertEquals(true, consumed)
            assertEquals(SOURCE_SHA256, sha256(privateSource.readBytes()))
            assertEquals(SOURCE_SHA256, sha256(fixture.readBytes()))
            report.put("status", "diagnostic_complete")
        } catch (failure: Throwable) {
            report.put("status", "interrupted").put("failure", failure.toString().take(600))
            throw failure
        } finally { persist(); directory.deleteRecursively() }
    }

    private fun boxJson(box: OcrBox) = JSONArray(listOf(box.left, box.top, box.right, box.bottom))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object {
        const val SOURCE_SHA256 = "f05f4a93fac66b985657c07426a79fde3b6bfeb7e433344b61ee53f1d9f0ae16"
    }
}
