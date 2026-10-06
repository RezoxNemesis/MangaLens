package com.mangalens.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class LocalSourceLanguage { JAPANESE, KOREAN, CHINESE, SPANISH, FRENCH, HINDI, ENGLISH, UNKNOWN }

data class TranslationRegion(
    val source: String,
    val translated: String,
    val bounds: RectF,
    val sourceLanguage: LocalSourceLanguage,
    val textColor: Int,
    val backgroundColor: Int,
    val textSize: Float,
    val confidence: Float = 1f
)

class AdvancedTranslationEngine(private val context: Context? = null) {

    suspend fun recognize(bitmap: Bitmap): List<TranslationRegion> {
        val recognizer = createRecognizer("LATIN")
        return try {
            mergeDialogueRegions(bitmap, recognizeWith(recognizer, bitmap, "LATIN"))
        } finally {
            recognizer.close()
        }
    }

    private suspend fun recognizeWith(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        script: String
    ): List<TranslationRegion> = suspendCancellableCoroutine { continuation ->
        val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
        task.addOnSuccessListener { result ->
            if (!continuation.isActive) return@addOnSuccessListener
            val regions = buildList {
                result.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        val box = line.boundingBox ?: return@forEach
                        val source = sanitize(line.text)
                        if (source.length < 2 || source.none { it.isLetterOrDigit() }) return@forEach
                        val bounds = RectF(box)
                        val background = sampleBackground(bitmap, bounds)
                        val confidence = recognitionQuality(source, script)
                        if (confidence < 0.22f) return@forEach
                        add(
                            TranslationRegion(
                                source = source,
                                translated = source,
                                bounds = bounds,
                                sourceLanguage = detect(source),
                                textColor = contrastText(background),
                                backgroundColor = background,
                                textSize = max(12f, box.height() * 0.72f),
                                confidence = confidence
                            )
                        )
                    }
                }
            }
            continuation.resume(regions)
        }.addOnFailureListener { failure ->
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }

    suspend fun recognizeFast(bitmap: Bitmap): List<TranslationRegion> {
        val configured = configuredScript()
        if (configured != "AUTO") return recognizeSingle(bitmap, configured)

        val latin = recognizeSingle(bitmap, "LATIN", merge = false)
        val latinSignal = latin.sumOf {
            (it.source.count(Char::isLetterOrDigit) * it.confidence).toDouble()
        }
        if (latinSignal >= 22.0 && latin.count { it.confidence >= 0.7f } >= 1) {
            return mergeDialogueRegions(bitmap, deduplicate(latin))
        }

        val all = ArrayList<TranslationRegion>(latin.size + 12)
        all += latin
        for (script in listOf("JAPANESE", "KOREAN", "CHINESE", "DEVANAGARI")) {
            try {
                all += recognizeSingle(bitmap, script, merge = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A missing/temporarily unavailable model must not kill live subtitles.
            }
        }
        return mergeDialogueRegions(bitmap, deduplicate(all))
    }

    suspend fun recognizeScriptAware(bitmap: Bitmap): List<TranslationRegion> {
        val script = configuredScript()
        val highAccuracy = context?.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
            ?.getBoolean("high_accuracy", true) ?: true

        if (script != "AUTO") return recognizeSingle(bitmap, script)
        if (!highAccuracy) return recognizeFast(bitmap)

        val all = ArrayList<TranslationRegion>()
        for (candidate in listOf("LATIN", "DEVANAGARI", "JAPANESE", "KOREAN", "CHINESE")) {
            try {
                all += recognizeSingle(bitmap, candidate, merge = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Keep the other OCR models usable if one model fails.
            }
        }
        return mergeDialogueRegions(bitmap, deduplicate(all))
    }

    private suspend fun recognizeSingle(
        bitmap: Bitmap,
        script: String,
        merge: Boolean = true
    ): List<TranslationRegion> {
        val recognizer = createRecognizer(script)
        return try {
            val raw = recognizeWith(recognizer, bitmap, script)
            val cleaned = deduplicate(raw)
            if (merge) mergeDialogueRegions(bitmap, cleaned) else cleaned
        } finally {
            recognizer.close()
        }
    }

    private fun configuredScript(): String =
        context?.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
            ?.getString("script", "AUTO")
            ?.uppercase(Locale.ROOT)
            ?: "AUTO"

    private fun createRecognizer(script: String): TextRecognizer = when (script.uppercase(Locale.ROOT)) {
        "DEVANAGARI" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        "CHINESE" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "JAPANESE" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "KOREAN" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Fuses results from multiple script recognizers spatially. The old implementation selected
     * whichever recognizer returned the most characters, which allowed noisy OCR to beat the
     * correct recognizer. Here each region is scored for script plausibility and competing regions
     * that occupy the same pixels are resolved independently.
     */
    private fun deduplicate(regions: List<TranslationRegion>): List<TranslationRegion> {
        if (regions.size < 2) return regions
        val selected = mutableListOf<TranslationRegion>()
        regions.sortedWith(
            compareByDescending<TranslationRegion> { it.confidence }
                .thenByDescending { it.source.count(Char::isLetterOrDigit) }
        ).forEach { candidate ->
            val collision = selected.indexOfFirst { existing ->
                intersectionOverUnion(candidate.bounds, existing.bounds) >= 0.38f ||
                    containment(candidate.bounds, existing.bounds) >= 0.68f
            }
            if (collision < 0) {
                selected += candidate
            } else {
                val old = selected[collision]
                val candidateScore = candidate.confidence * candidate.source.count(Char::isLetterOrDigit)
                val oldScore = old.confidence * old.source.count(Char::isLetterOrDigit)
                if (candidateScore > oldScore * 1.08f) selected[collision] = candidate
            }
        }
        return selected.sortedWith(compareBy<TranslationRegion> { it.bounds.top }.thenBy { it.bounds.left })
    }

    /**
     * OCR engines return individual lines. Translating those lines independently is a major source
     * of broken Hindi/English sentences. Nearby lines that geometrically behave like one speech
     * bubble are merged before translation so the translator receives the complete utterance.
     */
    private fun mergeDialogueRegions(
        bitmap: Bitmap,
        regions: List<TranslationRegion>
    ): List<TranslationRegion> {
        if (regions.size < 2) return regions
        val ordered = regions.sortedWith(compareBy<TranslationRegion> { it.bounds.top }.thenBy { it.bounds.left })
        val groups = mutableListOf<MutableList<TranslationRegion>>()

        for (region in ordered) {
            val best = groups
                .map { group -> group to mergeAffinity(group, region) }
                .filter { it.second > 0f }
                .maxByOrNull { it.second }
                ?.first
            if (best == null) groups += mutableListOf(region) else best += region
        }

        return groups.map { group ->
            if (group.size == 1) return@map group.first()
            val sorted = group.sortedWith(compareBy<TranslationRegion> { it.bounds.top }.thenBy { it.bounds.left })
            val union = RectF(
                sorted.minOf { it.bounds.left },
                sorted.minOf { it.bounds.top },
                sorted.maxOf { it.bounds.right },
                sorted.maxOf { it.bounds.bottom }
            )
            val source = sorted.joinToString(" ") { it.source }
                .replace(Regex("\\s+([,.!?;:])"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
            val background = sampleBackground(bitmap, union)
            TranslationRegion(
                source = source,
                translated = source,
                bounds = union,
                sourceLanguage = detect(source),
                textColor = contrastText(background),
                backgroundColor = background,
                textSize = sorted.map { it.textSize }.average().toFloat().coerceAtLeast(12f),
                confidence = sorted.map { it.confidence }.average().toFloat()
            )
        }.sortedWith(compareBy<TranslationRegion> { it.bounds.top }.thenBy { it.bounds.left })
    }

    private fun mergeAffinity(group: List<TranslationRegion>, candidate: TranslationRegion): Float {
        val last = group.last()
        val union = RectF(
            group.minOf { it.bounds.left },
            group.minOf { it.bounds.top },
            group.maxOf { it.bounds.right },
            group.maxOf { it.bounds.bottom }
        )
        val typicalHeight = group.map { it.bounds.height() }.average().toFloat().coerceAtLeast(1f)
        val candidateHeight = candidate.bounds.height().coerceAtLeast(1f)
        val heightRatio = candidateHeight / typicalHeight
        if (heightRatio !in 0.48f..2.1f) return 0f

        val sameLine = verticalOverlap(last.bounds, candidate.bounds) >= 0.48f
        if (sameLine) {
            val gap = candidate.bounds.left - last.bounds.right
            if (gap in -typicalHeight..(typicalHeight * 2.1f)) return 2.4f
        }

        val verticalGap = candidate.bounds.top - union.bottom
        if (verticalGap < -typicalHeight * 0.35f || verticalGap > max(typicalHeight, candidateHeight) * 1.05f) return 0f

        val overlap = horizontalOverlap(union, candidate.bounds)
        val centerDelta = abs(candidate.bounds.centerX() - union.centerX())
        val centerTolerance = max(union.width(), candidate.bounds.width()) * 0.38f
        if (overlap < 0.22f && centerDelta > centerTolerance) return 0f

        val languageCompatible = last.sourceLanguage == candidate.sourceLanguage ||
            last.sourceLanguage == LocalSourceLanguage.UNKNOWN ||
            candidate.sourceLanguage == LocalSourceLanguage.UNKNOWN
        if (!languageCompatible) return 0f

        return 1f + overlap + (1f - (verticalGap / (typicalHeight * 1.05f)).coerceIn(0f, 1f))
    }

    fun render(bitmap: Bitmap, regions: List<TranslationRegion>): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        regions.forEach { region ->
            val padding = max(2f, region.bounds.height() * 0.08f)
            val box = RectF(
                (region.bounds.left - padding).coerceAtLeast(0f),
                (region.bounds.top - padding).coerceAtLeast(0f),
                (region.bounds.right + padding).coerceAtMost(output.width.toFloat()),
                (region.bounds.bottom + padding).coerceAtMost(output.height.toFloat())
            )
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = region.backgroundColor }
            canvas.drawRoundRect(box, box.height() * .12f, box.height() * .12f, background)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = contrastText(region.backgroundColor)
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            var size = region.textSize.coerceIn(11f, 96f)
            var lines: List<String>
            var lineHeight: Float
            do {
                paint.textSize = size
                lines = wrap(region.translated, paint, box.width() * .92f)
                lineHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent
                if (lineHeight * lines.size <= box.height() * .94f || size <= 10f) break
                size *= .90f
            } while (true)

            val startY = box.centerY() - (lines.size - 1) * lineHeight / 2f -
                (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
            lines.forEachIndexed { index, value ->
                canvas.drawText(value, box.centerX(), startY + index * lineHeight, paint)
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

    private fun sanitize(value: String): String =
        value.replace(Regex("[\\u0000-\\u001F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun recognitionQuality(text: String, script: String): Float {
        val meaningful = text.count { it.isLetterOrDigit() }
        if (meaningful < 2) return 0f
        val meaningfulRatio = meaningful.toFloat() / text.length.coerceAtLeast(1)
        val replacementPenalty = text.count { it == '�' || it == '□' || it == '�' }.toFloat() /
            text.length.coerceAtLeast(1)
        val scriptFit = scriptFit(text, script)
        val lengthSignal = (meaningful / 8f).coerceIn(.45f, 1f)
        return (meaningfulRatio * .32f + scriptFit * .53f + lengthSignal * .15f - replacementPenalty * .8f)
            .coerceIn(0f, 1f)
    }

    private fun scriptFit(text: String, script: String): Float {
        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty()) return .35f
        val matches = letters.count { ch ->
            when (script.uppercase(Locale.ROOT)) {
                "DEVANAGARI" -> ch in '\u0900'..'\u097f'
                "JAPANESE" -> ch in '\u3040'..'\u30ff' || ch in '\u4e00'..'\u9fff'
                "KOREAN" -> ch in '\uac00'..'\ud7af'
                "CHINESE" -> ch in '\u3400'..'\u9fff'
                else -> ch.code < 0x0250
            }
        }
        return (matches.toFloat() / letters.size).coerceIn(0f, 1f)
    }

    private fun contrastText(background: Int): Int {
        val luminance = luminance(background)
        return if (luminance > 0.56f) Color.BLACK else Color.WHITE
    }

    /**
     * Samples around the OCR box instead of the glyph centre. The previous implementation often
     * sampled a black letter as the "background", producing black translation rectangles on white
     * speech bubbles.
     */
    private fun sampleBackground(bitmap: Bitmap, bounds: RectF): Int {
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = bounds.right.toInt().coerceIn(0, bitmap.width - 1)
        val bottom = bounds.bottom.toInt().coerceIn(0, bitmap.height - 1)
        val padX = max(2, ((right - left) * .08f).toInt())
        val padY = max(2, ((bottom - top) * .18f).toInt())
        val xs = listOf(left, (left + right) / 2, right)
        val ys = listOf(top, (top + bottom) / 2, bottom)
        val samples = mutableListOf<Int>()

        xs.forEach { x ->
            samples += bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), (top - padY).coerceIn(0, bitmap.height - 1))
            samples += bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), (bottom + padY).coerceIn(0, bitmap.height - 1))
        }
        ys.forEach { y ->
            samples += bitmap.getPixel((left - padX).coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            samples += bitmap.getPixel((right + padX).coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
        }

        if (samples.isEmpty()) return Color.WHITE
        val sorted = samples.sortedBy(::luminance)
        val trimmed = if (sorted.size > 6) sorted.drop(2).dropLast(2) else sorted
        return Color.rgb(
            trimmed.map(Color::red).average().toInt().coerceIn(0, 255),
            trimmed.map(Color::green).average().toInt().coerceIn(0, 255),
            trimmed.map(Color::blue).average().toInt().coerceIn(0, 255)
        )
    }

    private fun luminance(color: Int): Float =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f

    private fun wrap(value: String, paint: Paint, width: Float): List<String> {
        if (value.isBlank()) return emptyList()
        val lines = mutableListOf<String>()
        var line = ""
        value.split(Regex("\\s+")).forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > width && line.isNotEmpty()) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun containment(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val smaller = min(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else intersection / smaller
    }

    private fun horizontalOverlap(a: RectF, b: RectF): Float {
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        if (overlap <= 0f) return 0f
        return overlap / min(a.width(), b.width()).coerceAtLeast(1f)
    }

    private fun verticalOverlap(a: RectF, b: RectF): Float {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (overlap <= 0f) return 0f
        return overlap / min(a.height(), b.height()).coerceAtLeast(1f)
    }

    fun close() = Unit
}
