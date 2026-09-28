package com.mangalens.core.translation

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrRegion(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

class OcrInpaintingEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): List<OcrRegion> =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    continuation.resume(result.textBlocks.flatMap { block ->
                        block.lines.flatMap { line ->
                            listOfNotNull(line.boundingBox?.let { box ->
                                OcrRegion(line.text, box.left, box.top, box.right, box.bottom)
                            })
                        }
                    })
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    fun inpaintRegion(bitmap: Bitmap, region: OcrRegion): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val left = region.left.coerceIn(1, output.width - 1)
        val top = region.top.coerceIn(1, output.height - 1)
        val right = region.right.coerceIn(left + 1, output.width - 1)
        val bottom = region.bottom.coerceIn(top + 1, output.height - 1)
        val sampleY = (top - 1).coerceAtLeast(0)
        val pixels = IntArray((right - left).coerceAtLeast(1))
        output.getPixels(pixels, 0, pixels.size, left, sampleY, pixels.size, 1)
        var r = 0
        var g = 0
        var b = 0
        pixels.forEach {
            r += Color.red(it)
            g += Color.green(it)
            b += Color.blue(it)
        }
        val count = pixels.size.coerceAtLeast(1)
        val fill = Color.rgb(r / count, g / count, b / count)
        output.eraseColorRegion(left, top, right, bottom, fill)
        return output
    }

    fun close() {
        recognizer.close()
    }

    private fun Bitmap.eraseColorRegion(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        val canvas = android.graphics.Canvas(this)
        val paint = android.graphics.Paint().apply { this.color = color }
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
    }
}
