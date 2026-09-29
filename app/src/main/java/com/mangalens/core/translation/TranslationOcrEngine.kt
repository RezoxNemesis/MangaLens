package com.mangalens.core.translation

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

enum class OcrScript { AUTO, LATIN, DEVANAGARI, CHINESE, JAPANESE, KOREAN }

data class OcrRuntimeSettings(
    val script: OcrScript = OcrScript.AUTO,
    val preferHighAccuracy: Boolean = true,
    val preserveStyle: Boolean = true,
    val maxTranslatedCharsPerRegion: Int = 240
)

data class StyledTextRegion(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val centerX: Float,
    val centerY: Float,
    val fontSizePx: Float,
    val textColor: Int
)

class TranslationOcrEngine(
    private val settings: OcrRuntimeSettings = OcrRuntimeSettings()
) {
    suspend fun recognize(bitmap: Bitmap): List<StyledTextRegion> {
        val script = if (settings.script == OcrScript.AUTO) detectScript(bitmap) else settings.script
        val recognizer = createRecognizer(script)
        return try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result.textBlocks.flatMap { block ->
                            block.lines.mapNotNull { line ->
                                line.boundingBox?.let { box ->
                                    StyledTextRegion(
                                        text = line.text.trim(),
                                        left = box.left,
                                        top = box.top,
                                        right = box.right,
                                        bottom = box.bottom,
                                        centerX = box.exactCenterX(),
                                        centerY = box.exactCenterY(),
                                        fontSizePx = box.height().toFloat().coerceAtLeast(12f),
                                        textColor = sampleTextColor(bitmap, box.left, box.top)
                                    )
                                }
                            }
                        })
                    }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
    }

    private fun createRecognizer(script: OcrScript): TextRecognizer = when (script) {
        OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun detectScript(bitmap: Bitmap): OcrScript {
        val stepX = (bitmap.width / 32).coerceAtLeast(1)
        val stepY = (bitmap.height / 32).coerceAtLeast(1)
        var latin = 0
        var devanagari = 0
        var cjk = 0
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val code = bitmap.getPixel(x, y)
                if (Color.alpha(code) == 0) continue
                val r = Color.red(code)
                val g = Color.green(code)
                val b = Color.blue(code)
                if (maxOf(r, g, b) - minOf(r, g, b) > 30) latin++ else devanagari += 0
                if (r + g + b < 180) cjk++
            }
        }
        return when {
            cjk > latin * 2 -> OcrScript.AUTO
            devanagari > latin -> OcrScript.DEVANAGARI
            else -> OcrScript.LATIN
        }
    }

    fun inpaintRegion(bitmap: Bitmap, region: StyledTextRegion): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val left = region.left.coerceIn(0, output.width - 2)
        val top = region.top.coerceIn(0, output.height - 2)
        val right = region.right.coerceIn(left + 1, output.width - 1)
        val bottom = region.bottom.coerceIn(top + 1, output.height - 1)
        val background = sampleBackground(output, left, top, right, bottom)
        android.graphics.Canvas(output).drawRoundRect(
            left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
            (right - left) * .08f, (bottom - top) * .08f,
            android.graphics.Paint().apply { color = background }
        )
        return output
    }

    fun renderTranslatedText(canvas: android.graphics.Canvas, region: StyledTextRegion, translatedText: String) {
        val safe = translatedText.take(settings.maxTranslatedCharsPerRegion)
        val width = (region.right - region.left).coerceAtLeast(20).toFloat()
        val targetSize = region.fontSizePx.coerceIn(12f, 96f)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = region.textColor
            textSize = targetSize
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        }
        val lines = wrap(safe, paint, width * .92f)
        val lineHeight = -(paint.ascent() + paint.descent()) * 1.12f
        val totalHeight = lineHeight * lines.size
        val startY = region.centerY - totalHeight / 2f - (paint.ascent() + paint.descent()) / 2f
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, region.centerX, startY + index * lineHeight, paint)
        }
    }

    private fun wrap(text: String, paint: android.graphics.Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isBlank()) current = candidate
            else { lines += current; current = word }
        }
        if (current.isNotBlank()) lines += current
        return lines
    }

    fun normalizeHinglish(text: String): String {
        val common = mapOf(
            "kya" to "क्या", "hai" to "है", "haan" to "हाँ", "nahi" to "नहीं",
            "nahin" to "नहीं", "kyun" to "क्यों", "kaise" to "कैसे", "main" to "मैं",
            "mai" to "मैं", "tum" to "तुम", "aap" to "आप", "mera" to "मेरा",
            "meri" to "मेरी", "mujhe" to "मुझे", "bahut" to "बहुत", "acha" to "अच्छा",
            "achha" to "अच्छा", "theek" to "ठीक"
        )
        return text.split(Regex("\\s+")).joinToString(" ") { token ->
            common[token.lowercase()] ?: token
        }
    }

    private fun sampleTextColor(bitmap: Bitmap, x: Int, y: Int): Int =
        bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))

    private fun sampleBackground(bitmap: Bitmap, l: Int, t: Int, r: Int, b: Int): Int {
        val points = listOf(bitmap.getPixel(l, t), bitmap.getPixel(r, t), bitmap.getPixel(l, b), bitmap.getPixel(r, b))
        return Color.rgb(
            points.map(Color::red).average().toInt(),
            points.map(Color::green).average().toInt(),
            points.map(Color::blue).average().toInt()
        )
    }

    fun close() = Unit
}
