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
    val textSize: Float,
    val lineBounds: List<RectF> = listOf(bounds)
)

class AdvancedTranslationEngine(private val context: Context? = null) {
    internal var tileObserver: ((Int, List<TranslationRegion>) -> Unit)? = null
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
                    if (!continuation.isActive) return@addOnSuccessListener
                    val regions = mutableListOf<TranslationRegion>()
                    result.textBlocks.forEach { block ->
                        val box = block.boundingBox
                        val source = block.text.trim()
                        if (box != null && source.isNotBlank()) {
                            regions += TranslationRegion(source, source, RectF(box), detect(source),
                                Color.BLACK, Color.WHITE,
                                block.lines.mapNotNull { it.boundingBox?.height()?.toFloat() }.average().toFloat().coerceAtLeast(12f),
                                block.lines.mapNotNull { it.boundingBox?.let(::RectF) })
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
        // Long webtoon pages must keep glyph resolution; use overlapping vertical OCR tiles.
        if (bitmap.height <= 2048) return recognizeTile(bitmap)
        val output = mutableListOf<TranslationRegion>()
        val margins = mutableListOf<Float>()
        var y = 0
        while (y < bitmap.height) {
            val height = minOf(2048, bitmap.height - y)
            val tile = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, height)
            try {
                val recognized = recognizeTile(tile)
                tileObserver?.invoke(y, recognized)
                recognized.forEach { region ->
                    val margin = minOf(region.bounds.top, height - region.bounds.bottom).coerceAtLeast(0f)
                    val shifted = region.copy(bounds = RectF(region.bounds).apply { offset(0f, y.toFloat()) },
                        lineBounds = region.lineBounds.map { RectF(it).apply { offset(0f, y.toFloat()) } })
                    val duplicate = output.indexOfFirst { previous ->
                        val intersection = RectF(previous.bounds)
                        intersection.intersect(shifted.bounds) && intersection.width() * intersection.height() >
                            minOf(previous.bounds.width() * previous.bounds.height(), shifted.bounds.width() * shifted.bounds.height()) * .5f
                    }
                    if (duplicate < 0) {
                        output += shifted
                        margins += margin
                    } else if (margin > margins[duplicate] ||
                        (margin == margins[duplicate] && shifted.source.length > output[duplicate].source.length)) {
                        // Central detections retain full glyph context. Longer edge results can be OCR hallucinations.
                        output[duplicate] = shifted
                        margins[duplicate] = margin
                    }
                }
            } finally { tile.recycle() }
            if (y + height >= bitmap.height) break
            y += 1792
        }
        return output.sortedBy { it.bounds.top }
    }

    private suspend fun recognizeTile(bitmap: Bitmap): List<TranslationRegion> {
        val prefs = context?.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        val script = prefs?.getString("script", "AUTO") ?: "AUTO"
        val highAccuracy = prefs?.getBoolean("high_accuracy", true) ?: true
        val candidates = if (script != "AUTO") listOf(createRecognizer(script)) else if (!highAccuracy) listOf(createRecognizer("LATIN")) else listOf(
            createRecognizer("LATIN"), createRecognizer("DEVANAGARI"),
            createRecognizer("CHINESE"), createRecognizer("JAPANESE"), createRecognizer("KOREAN")
        )
        return try {
            val failures = mutableListOf<Throwable>()
            val results = candidates.map { recognizer ->
                try {
                    recognizeWith(recognizer, bitmap)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failures += failure
                    emptyList()
                }
            }
            if (failures.size == candidates.size) throw failures.first()
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
            val patch = com.mangalens.core.translation.MangaLettering.prepare(bitmap, region.bounds, region.lineBounds, region.source)
            try { com.mangalens.core.translation.MangaLettering.draw(canvas, patch, region.translated) }
            finally { patch.background.recycle() }
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

    fun close() = Unit
}
