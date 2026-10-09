package com.mangalens

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.ChapterOriginalOcrSource
import com.mangalens.engine.OcrBox
import com.mangalens.engine.OcrOriginalSourceChangedException
import com.mangalens.engine.OcrSourceResolutionProof
import com.mangalens.engine.OcrSourceResolutionRequest
import com.mangalens.engine.awaitOcrCompletion
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual private-file/decoder/pixel ownership checks, independent of recognition accuracy. */
@RunWith(AndroidJUnit4::class)
class OcrOriginalRegionLeaseTest {
    @Test fun privateOriginalCropKeepsPixelsAndMapsIntoTheBoundedPage() = runBlocking {
        withFixture { file, bitmap ->
            val proof = proof(file)
            var borrowed: Bitmap? = null
            var reads = 0
            val source = ChapterOriginalOcrSource(proof, file.parentFile!!) { proof }
            val result = source.withCrop(OcrSourceResolutionRequest(OcrBox(6f, 40f, 18f, 52f), 24, 100, maxScale = 1f)) { crop ->
                borrowed = crop.bitmap; reads++
                assertEquals(bitmap.getPixel(24, 160), crop.bitmap.getPixel(0, 0))
                assertEquals(bitmap.getPixel(71, 207), crop.bitmap.getPixel(crop.bitmap.width - 1, crop.bitmap.height - 1))
                val mapped = requireNotNull(crop.mapToPage(OcrBox(0f, 0f, crop.bitmap.width.toFloat(), crop.bitmap.height.toFloat())))
                assertEquals(OcrBox(6f, 40f, 18f, 52f), mapped)
                assertEquals(10f, crop.textSizeToPage(40f), .001f)
                assertFalse(crop.bitmap.isRecycled)
                "verified private pixels"
            }
            assertEquals("verified private pixels", result); assertEquals(1, reads)
            assertTrue(requireNotNull(borrowed).isRecycled)
            assertEquals(proof.sourceSha256, sha256(file.readBytes()))
        }
    }

    @Test fun changedBytesDimensionsAndPrivateDirectoryNeverReachTheConsumer() = runBlocking {
        withFixture { file, _ ->
            val expected = proof(file)
            val originalBytes = file.readBytes()
            val request = OcrSourceResolutionRequest(OcrBox(6f, 40f, 18f, 52f), 24, 100)
            var reads = 0
            suspend fun rejected(candidate: OcrSourceResolutionProof, directory: File) {
                try {
                    ChapterOriginalOcrSource(candidate, directory) { candidate }.withCrop(request) { reads++ }
                    fail("Unqualified original crop was consumed")
                } catch (_: OcrOriginalSourceChangedException) { }
            }
            // A valid PNG with extra bytes remains decodable, but is no longer this pinned source.
            file.appendBytes(byteArrayOf(7))
            rejected(expected, file.parentFile!!)
            file.writeBytes(originalBytes)
            rejected(expected.copy(originalWidth = 97), file.parentFile!!)
            rejected(expected, File(file.parentFile, "unrelated"))
            assertEquals(0, reads)
            assertEquals(expected.sourceSha256, sha256(file.readBytes()))
        }
    }

    @Test fun cancellationCannotRecycleAnOriginalCropBeforeHeldNativeReturn() = runBlocking {
        withFixture { file, _ ->
            val expected = proof(file)
            val complete = CompletableDeferred<(Result<String>) -> Unit>()
            val image = AtomicReference<Bitmap>()
            val started = CompletableDeferred<Unit>()
            val job = launch {
                ChapterOriginalOcrSource(expected, file.parentFile!!) { expected }
                    .withCrop(OcrSourceResolutionRequest(OcrBox(6f, 40f, 18f, 52f), 24, 100)) { crop ->
                        image.set(crop.bitmap)
                        awaitOcrCompletion<String> { callback -> complete.complete(callback); started.complete(Unit) }
                    }
            }
            withTimeout(5000) { started.await() }
            job.cancel(); yield()
            assertFalse(job.isCompleted); assertFalse(image.get().isRecycled)
            complete.await()(Result.success("actual return boundary")); job.join()
            assertTrue(image.get().isRecycled)
            assertEquals(expected.sourceSha256, sha256(file.readBytes()))
        }
    }

    private suspend fun withFixture(block: suspend (File, Bitmap) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "qa/ocr-original-lease-${UUID.randomUUID()}").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(96, 400, Bitmap.Config.ARGB_8888)
        val colors = IntArray(96 * 400) { index -> 0xff000000.toInt() or ((index * 127) and 0x00ffffff) }
        bitmap.setPixels(colors, 0, 96, 0, 0, 96, 400)
        val file = File(directory, "source.png")
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            block(file, bitmap)
            val after = IntArray(colors.size).also { bitmap.getPixels(it, 0, 96, 0, 0, 96, 400) }
            assertArrayEquals("Original caller pixels were changed", colors, after)
        } finally { bitmap.recycle(); directory.deleteRecursively() }
    }

    private fun proof(file: File) = OcrSourceResolutionProof("a".repeat(32), "b".repeat(32), "c".repeat(32), 0,
        file.canonicalPath, sha256(file.readBytes()), 96, 400, 24, 100)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
