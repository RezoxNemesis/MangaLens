package com.mangalens.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class LocalSourceLanguage { JAPANESE, KOREAN, CHINESE, SPANISH, FRENCH, HINDI, ENGLISH, UNKNOWN }

data class TranslationRegion(
    val source: String,
    val translated: String,
    val bounds: RectF,
    val sourceLanguage: LocalSourceLanguage,
    val textColor: Int,
    val backgroundColor: Int,
    val textSize: Float
)

class AdvancedTranslationEngine(private val context: Context? = null) {
    suspend fun recognize(bitmap: Bitmap): List<TranslationRegion> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizeWith(recognizer, bitmap)
        } finally {
            recognizer.close()
        }
    }

    private suspend fun recognizeWith(recognizer: TextRecognizer, bitmap: Bitmap): List<TranslationRegion> =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val regions = mutableListOf<TranslationRegion>()
                    result.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            val box = line.boundingBox ?: return@forEach
                            val source = line.text.trim()
                            if (source.isNotBlank()) {
                                regions += TranslationRegion(
                                    source = source,
                                    translated = source,
                                    bounds = RectF(box),
                                    sourceLanguage = detect(source),
                                    textColor = contrastText(sample(bitmap, box.centerX().toFloat(), box.centerY().toFloat())),
                                    backgroundColor = sample(bitmap, box.centerX().toFloat(), box.centerY().toFloat()),
                                    textSize = maxOf(12f, box.height() * 0.72f)
                                )
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(regions)
                }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }

    suspend fun recognizeFast(bitmap: Bitmap): List<TranslationRegion> {
        val prefs = context?.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        val configured = prefs?.getString("script", "AUTO") ?: "AUTO"
        val recognizer = createRecognizer(if (configured == "AUTO") "LATIN" else configured)
        return try {
            recognizeWith(recognizer, bitmap)
        } finally {
            recognizer.close()
        }
    }

    suspend fun recognizeScriptAware(bitmap: Bitmap): List<TranslationRegion> {
        val prefs = context?.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        val script = prefs?.getString("script", "AUTO") ?: "AUTO"
        val highAccuracy = prefs?.getBoolean("high_accuracy", true) ?: true
        val candidates = if (script != "AUTO" && !highAccuracy) listOf(createRecognizer(script)) else listOf(
            createRecognizer("LATIN"), createRecognizer("DEVANAGARI"),
            createRecognizer("CHINESE"), createRecognizer("JAPANESE"), createRecognizer("KOREAN")
        )
        return try {
            val results = candidates.map { recognizer ->
                try {
                    recognizeWith(recognizer, bitmap)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    emptyList()
                }
            }
            results.maxByOrNull { it.sumOf { region -> region.source.length } } ?: emptyList()
        } finally {
            candidates.forEach { it.close() }
        }
    }

    private fun createRecognizer(script: String): TextRecognizer = when (script.uppercase(Locale.ROOT)) {
        "DEVANAGARI" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        "CHINESE" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "JAPANESE" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "KOREAN" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun render(bitmap: Bitmap, regions: List<TranslationRegion>): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        regions.forEach { region ->
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = region.backgroundColor }
            canvas.drawRoundRect(region.bounds, region.bounds.height() * .18f, region.bounds.height() * .18f, background)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = region.textColor
                textSize = region.textSize
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            val lines = wrap(region.translated, text, region.bounds.width())
            val lineHeight = text.fontMetrics.descent - text.fontMetrics.ascent
            val startY = region.bounds.centerY() - (lines.size - 1) * lineHeight / 2f - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
            lines.forEachIndexed { index, value ->
                canvas.drawText(value, region.bounds.centerX() - text.measureText(value) / 2f, startY + index * lineHeight, text)
            }
        }
        return output
    }

    fun detect(source: String): LocalSourceLanguage {
        val text = source.lowercase(Locale.ROOT)
        return when {
            source.any { it in '\u3040'..'\u30ff' } -> LocalSourceLanguage.JAPANESE
            source.any { it in '\uac00'..'\ud7af' } -> LocalSourceLanguage.KOREAN
            source.any { it in '\u0900'..'\u097f' } -> LocalSourceLanguage.HINDI
            source.any { it in '\u4e00'..'\u9fff' } -> LocalSourceLanguage.CHINESE
            Regex("\\b(el|la|los|las|que|una|por|para)\\b").containsMatchIn(text) -> LocalSourceLanguage.SPANISH
            Regex("\\b(le|les|des|une|avec|pour|est)\\b").containsMatchIn(text) -> LocalSourceLanguage.FRENCH
            text.any(Char::isLetter) -> LocalSourceLanguage.ENGLISH
            else -> LocalSourceLanguage.UNKNOWN
        }
    }

    private fun contrastText(background: Int): Int {
        val luminance = (0.2126f * Color.red(background) + 0.7152f * Color.green(background) + 0.0722f * Color.blue(background)) / 255f
        return if (luminance > 0.55f) Color.BLACK else Color.WHITE
    }

    private fun sample(bitmap: Bitmap, x: Float, y: Float): Int {
        val px = bitmap.getPixel(x.toInt().coerceIn(0, bitmap.width - 1), y.toInt().coerceIn(0, bitmap.height - 1))
        val luminance = (0.2126f * Color.red(px) + 0.7152f * Color.green(px) + 0.0722f * Color.blue(px)) / 255f
        return if (luminance > .72f) Color.WHITE else Color.rgb((Color.red(px) + 255) / 2, (Color.green(px) + 255) / 2, (Color.blue(px) + 255) / 2)
    }

    private fun wrap(value: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        value.split(Regex("\\s+")).forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > width && line.isNotEmpty()) {
                lines += line
                line = word
            } else line = candidate
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    fun close() = Unit
}
