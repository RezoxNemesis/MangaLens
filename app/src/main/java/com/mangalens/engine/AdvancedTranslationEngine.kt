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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val lineBounds: List<RectF> = listOf(bounds),
    val recognitionConfidence: Float = 0f
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
                                block.lines.mapNotNull { it.boundingBox?.let(::RectF) },
                                block.lines.map { it.confidence }.filter { it.isFinite() && it > 0f }.average().toFloat().let { if (it.isFinite()) it else 0f })
                        }
                    }
                    if (continuation.isActive) continuation.resume(mergeLikelySameBalloon(regions))
                }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }

    /**
     * ML Kit can split one speech balloon into two text blocks. Translating those blocks
     * independently is what creates the "one normal line + one microscopic paragraph"
     * failure seen on real webtoon pages. Merge only tightly stacked, centre-aligned blocks
     * with comparable lettering before translation and typesetting.
     */
    internal fun mergeLikelySameBalloon(regions: List<TranslationRegion>): List<TranslationRegion> {
        if (regions.size < 2) return regions
        val sorted = regions.sortedWith(compareBy<TranslationRegion> { it.bounds.top }.thenBy { it.bounds.left })
        val merged = mutableListOf<TranslationRegion>()

        fun compatible(a: TranslationRegion, b: TranslationRegion): Boolean {
            if (a.sourceLanguage != b.sourceLanguage &&
                a.sourceLanguage != LocalSourceLanguage.UNKNOWN &&
                b.sourceLanguage != LocalSourceLanguage.UNKNOWN) return false

            val maxText = maxOf(a.textSize, b.textSize).coerceAtLeast(8f)
            val minText = minOf(a.textSize, b.textSize).coerceAtLeast(1f)
            if (minText / maxText < .62f) return false

            val gap = b.bounds.top - a.bounds.bottom
            if (gap < -maxText * .30f || gap > maxText * .95f) return false

            val minWidth = minOf(a.bounds.width(), b.bounds.width()).coerceAtLeast(1f)
            val centreDistance = kotlin.math.abs(a.bounds.centerX() - b.bounds.centerX())
            val overlap = minOf(a.bounds.right, b.bounds.right) - maxOf(a.bounds.left, b.bounds.left)
            val aligned = centreDistance <= minWidth * .34f + maxText * .35f
            val overlapping = overlap >= minWidth * .40f
            if (!aligned || !overlapping) return false

            val lineCount = a.lineBounds.size + b.lineBounds.size
            if (lineCount > 7) return false

            val unionTop = minOf(a.bounds.top, b.bounds.top)
            val unionBottom = maxOf(a.bounds.bottom, b.bounds.bottom)
            val tallest = maxOf(a.bounds.height(), b.bounds.height()).coerceAtLeast(1f)
            return unionBottom - unionTop <= tallest * 2.75f + maxText
        }

        fun combine(a: TranslationRegion, b: TranslationRegion): TranslationRegion {
            val source = listOf(a.source.trim(), b.source.trim()).filter(String::isNotBlank).joinToString("\n")
            val charsA = a.source.length.coerceAtLeast(1)
            val charsB = b.source.length.coerceAtLeast(1)
            val confidence = when {
                a.recognitionConfidence <= 0f -> b.recognitionConfidence
                b.recognitionConfidence <= 0f -> a.recognitionConfidence
                else -> (a.recognitionConfidence * charsA + b.recognitionConfidence * charsB) / (charsA + charsB)
            }
            return TranslationRegion(
                source = source,
                translated = source,
                bounds = RectF(
                    minOf(a.bounds.left, b.bounds.left),
                    minOf(a.bounds.top, b.bounds.top),
                    maxOf(a.bounds.right, b.bounds.right),
                    maxOf(a.bounds.bottom, b.bounds.bottom)
                ),
                sourceLanguage = detect(source),
                textColor = a.textColor,
                backgroundColor = a.backgroundColor,
                textSize = (a.textSize * charsA + b.textSize * charsB) / (charsA + charsB),
                lineBounds = (a.lineBounds + b.lineBounds).sortedWith(compareBy<RectF> { it.top }.thenBy { it.left }),
                recognitionConfidence = confidence
            )
        }

        for (region in sorted) {
            val previous = merged.lastOrNull()
            if (previous != null && compatible(previous, region)) {
                merged[merged.lastIndex] = combine(previous, region)
            } else {
                merged += region
            }
        }
        return merged
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
                    } else {
                        val previous = output[duplicate]
                        val clipped = margin < maxOf(2f, region.textSize * .12f)
                        val previousClipped = margins[duplicate] < maxOf(2f, previous.textSize * .12f)
                        val strongerConfidence = shifted.recognitionConfidence > previous.recognitionConfidence + .10f
                        val fullerCoverage = shifted.bounds.width() * shifted.bounds.height() >
                            previous.bounds.width() * previous.bounds.height() * 1.3f &&
                            shifted.recognitionConfidence >= previous.recognitionConfidence - .15f
                        // Keep a complete first reading unless evidence improves it. Extra OCR letters aren't quality.
                        if ((previousClipped && !clipped) ||
                            (previousClipped == clipped && (strongerConfidence || fullerCoverage))) {
                            output[duplicate] = shifted
                            margins[duplicate] = margin
                        }
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
            var best = results.maxByOrNull(::recognitionScore) ?: emptyList()
            // A larger pile of hallucinated letters is not a better OCR result. Retry faint/small
            // lettering at a bounded higher resolution, then map geometry back to the source.
            if (highAccuracy && (best.isEmpty() || best.any { it.textSize < 24f })) {
                currentCoroutineContext().ensureActive()
                val factor = minOf(2f, 2560f / bitmap.width, 4096f / bitmap.height)
                if (factor > 1.1f) {
                    val enhanced = Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true)
                    try {
                        val retry = createRecognizer(if (script == "AUTO") dominantScript(best) else script)
                        val reread = try { recognizeWith(retry, enhanced) } finally { retry.close() }
                        val mapped = reread.map { region -> region.copy(
                            bounds = RectF(region.bounds.left / factor, region.bounds.top / factor,
                                region.bounds.right / factor, region.bounds.bottom / factor),
                            lineBounds = region.lineBounds.map { RectF(it.left / factor, it.top / factor, it.right / factor, it.bottom / factor) },
                            textSize = region.textSize / factor
                        ) }
                        if (recognitionScore(mapped) > recognitionScore(best)) best = mapped
                    } finally { enhanced.recycle() }
                }
            }
            val accepted = if (script == "AUTO") best.filter(::isPlausibleRegion) else best
            accepted.sortedWith(compareBy<TranslationRegion> { it.bounds.top }.thenBy { it.bounds.left })
        } finally {
            candidates.forEach { it.close() }
        }
    }

    private fun recognitionScore(regions: List<TranslationRegion>): Double = regions.sumOf { region ->
        val letters = region.source.count(Char::isLetterOrDigit)
        val confidence = region.recognitionConfidence.takeIf { it > 0f } ?: .65f
        val garbage = region.source.count { !it.isLetterOrDigit() && !it.isWhitespace() && it !in ".,!?…:;\"'‘’“”()[]-—、。！？「」" }
        val implausible = if (isPlausibleRegion(region)) 0.0 else 12.0
        letters.coerceAtMost(240) * confidence.toDouble() - garbage * 2.0 - implausible
    }

    /**
     * ML Kit occasionally interprets decorative borders/runes as a short Latin word.
     * Keep real dialogue permissive, but reject low-confidence garbage and narrow vertical
     * Latin pseudo-words that produce floating replacement labels over character artwork.
     */
    private fun isPlausibleRegion(region: TranslationRegion): Boolean {
        val value = region.source.trim()
        if (value.isBlank()) return false
        val letters = value.count(Char::isLetterOrDigit)
        if (letters == 0) return false
        val confidence = region.recognitionConfidence
        if (region.sourceLanguage == LocalSourceLanguage.ENGLISH &&
            confidence > 0f && confidence < .30f) return false
        if (region.textSize < 6f) return false

        val acceptedPunctuation = ".,!?…:;\"'‘’“”()[]-—、。！？「」~"
        val readable = value.count {
            it.isLetterOrDigit() ||
                it.isWhitespace() ||
                it in acceptedPunctuation ||
                it.category == kotlin.text.CharCategory.NON_SPACING_MARK ||
                it.category == kotlin.text.CharCategory.COMBINING_SPACING_MARK ||
                it.category == kotlin.text.CharCategory.ENCLOSING_MARK
        }
        if (readable < value.length * .70f) return false

        val singleToken = value.none(Char::isWhitespace)
        val verticalLatinPseudoWord =
            region.sourceLanguage == LocalSourceLanguage.ENGLISH &&
                singleToken &&
                value.length >= 4 &&
                region.bounds.height() > region.bounds.width() * 1.20f
        if (verticalLatinPseudoWord) return false

        return !(value.length <= 2 && confidence in .0001f..0.54f)
    }

    private fun dominantScript(regions: List<TranslationRegion>): String = when (
        regions.groupBy { it.sourceLanguage }.maxByOrNull { entry -> entry.value.sumOf { it.source.length } }?.key
    ) {
        LocalSourceLanguage.JAPANESE -> "JAPANESE"
        LocalSourceLanguage.KOREAN -> "KOREAN"
        LocalSourceLanguage.CHINESE -> "CHINESE"
        LocalSourceLanguage.HINDI -> "DEVANAGARI"
        else -> "LATIN"
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
