package com.mangalens.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
    val recognitionConfidence: Float = 0f,
    val recognizerScript: String = ""
)

class AdvancedTranslationEngine(private val context: Context? = null) {
    data class OcrOptions(val script: String = "AUTO", val highAccuracy: Boolean = true)

    internal var tileObserver: ((Int, List<TranslationRegion>) -> Unit)? = null
    internal var regionRetryObserver: ((TranslationRegion, List<TranslationRegion>, TranslationRegion?) -> Unit)? = null
    suspend fun recognize(bitmap: Bitmap): List<TranslationRegion> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizeWith(recognizer, bitmap)
        } finally {
            recognizer.close()
        }
    }

    private suspend fun recognizeWith(
        recognizer: TextRecognizer, bitmap: Bitmap, script: String = "LATIN", mergeBlocks: Boolean = true
    ): List<TranslationRegion> {
        val result = awaitOcrCompletion<Text> { complete ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnCompleteListener(ocrCallbackExecutor) { task ->
                    complete(when {
                        task.isSuccessful -> Result.success(task.result)
                        task.isCanceled -> Result.failure(CancellationException("Native OCR task was cancelled"))
                        else -> Result.failure(task.exception ?: IllegalStateException("Native OCR failed without a cause"))
                    })
                }
        }
        val regions = mutableListOf<TranslationRegion>()
        result.textBlocks.forEach { block ->
            val box = block.boundingBox
            val source = block.text.trim()
            if (box != null && source.isNotBlank()) {
                val lines = block.lines.mapNotNull { line -> line.boundingBox?.let { line to RectF(it) } }
                val confidence = block.lines.map { it.confidence }.filter { it.isFinite() && it > 0f }
                    .average().toFloat().let { if (it.isFinite()) it else 0f }
                val descriptor = OcrReading(source, script, confidence,
                    OcrBox(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()),
                    lineBounds = lines.map { (_, bounds) -> OcrBox(bounds.left, bounds.top, bounds.right, bounds.bottom) })
                val vertical = isVerticalOcr(descriptor)
                val lineOrder = if (vertical) orderVerticalOcrLines(descriptor.lineBounds) else lines.indices.toList()
                val ordered = lineOrder.map(lines::get)
                val rawReading = if (vertical && lines.size == block.lines.size && ordered.isNotEmpty())
                    ordered.joinToString("\n") { it.first.text.trim() } else source
                val reading = OcrSourceQuality.normalizeLatinSource(rawReading)
                val size = ordered.map { if (vertical) it.second.width() else it.second.height() }
                    .average().toFloat().takeIf(Float::isFinite)?.coerceAtLeast(12f) ?: 12f
                val detected = detect(reading)
                val language = if (script == "JAPANESE" && detected == LocalSourceLanguage.CHINESE) LocalSourceLanguage.JAPANESE else detected
                regions += TranslationRegion(reading, reading, RectF(box), language, Color.BLACK, Color.WHITE,
                    size, ordered.map { it.second }, confidence, script)
            }
        }
        return if (mergeBlocks) mergeLikelySameBalloon(regions) else regions
    }

    /**
     * ML Kit can split one speech balloon into two text blocks. Translating those blocks
     * independently is what creates the "one normal line + one microscopic paragraph"
     * failure seen on real webtoon pages. Merge only tightly stacked, centre-aligned blocks
     * with comparable lettering before translation and typesetting.
     */
    internal fun mergeLikelySameBalloon(regions: List<TranslationRegion>): List<TranslationRegion> {
        if (regions.size < 2) return regions
        val sorted = orderOcrReadings(regions.map(::readingCandidate)).map(regions::get)
        val merged = mutableListOf<TranslationRegion>()

        fun compatible(a: TranslationRegion, b: TranslationRegion): Boolean {
            if (a.sourceLanguage != b.sourceLanguage &&
                a.sourceLanguage != LocalSourceLanguage.UNKNOWN &&
                b.sourceLanguage != LocalSourceLanguage.UNKNOWN) return false

            val maxText = maxOf(a.textSize, b.textSize).coerceAtLeast(8f)
            val minText = minOf(a.textSize, b.textSize).coerceAtLeast(1f)
            if (minText / maxText < .62f) return false

            val readingA = readingCandidate(a)
            val readingB = readingCandidate(b)
            if (isVerticalOcr(readingA) || isVerticalOcr(readingB)) return canMergeVerticalOcr(readingA, readingB)

            val fragment = minOf(a.source.trim().length, b.source.trim().length) <= 18 ||
                minOf(a.lineBounds.size, b.lineBounds.size) <= 1
            val gap = b.bounds.top - a.bounds.bottom
            val maxGap = maxText * if (fragment) 1.45f else .95f
            if (gap < -maxText * .30f || gap > maxGap) return false

            val minWidth = minOf(a.bounds.width(), b.bounds.width()).coerceAtLeast(1f)
            val centreDistance = kotlin.math.abs(a.bounds.centerX() - b.bounds.centerX())
            val overlap = minOf(a.bounds.right, b.bounds.right) - maxOf(a.bounds.left, b.bounds.left)
            val aligned = centreDistance <= minWidth * (if (fragment) .44f else .34f) + maxText * .35f
            val overlapping = overlap >= minWidth * (if (fragment) .28f else .40f)
            if (!aligned || !overlapping) return false

            val lineCount = a.lineBounds.size + b.lineBounds.size
            if (lineCount > if (fragment) 9 else 7) return false

            val unionTop = minOf(a.bounds.top, b.bounds.top)
            val unionBottom = maxOf(a.bounds.bottom, b.bounds.bottom)
            val tallest = maxOf(a.bounds.height(), b.bounds.height()).coerceAtLeast(1f)
            return unionBottom - unionTop <= tallest * (if (fragment) 3.4f else 2.75f) + maxText
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
            val lines = a.lineBounds + b.lineBounds
            val orderedLines = if (isVerticalOcr(readingCandidate(a)))
                orderVerticalOcrLines(lines.map { OcrBox(it.left, it.top, it.right, it.bottom) }).map(lines::get)
                else lines.sortedWith(compareBy<RectF> { it.top }.thenBy { it.left })
            return TranslationRegion(
                source = source,
                translated = source,
                bounds = RectF(
                    minOf(a.bounds.left, b.bounds.left),
                    minOf(a.bounds.top, b.bounds.top),
                    maxOf(a.bounds.right, b.bounds.right),
                    maxOf(a.bounds.bottom, b.bounds.bottom)
                ),
                sourceLanguage = if (a.sourceLanguage == b.sourceLanguage) a.sourceLanguage else detect(source),
                textColor = a.textColor,
                backgroundColor = a.backgroundColor,
                textSize = (a.textSize * charsA + b.textSize * charsB) / (charsA + charsB),
                lineBounds = orderedLines,
                recognitionConfidence = confidence,
                recognizerScript = a.recognizerScript.ifBlank { b.recognizerScript }
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
        val configured = currentPrefsSnapshot().script.uppercase(Locale.ROOT)
        val recognizer = createRecognizer(if (configured == "AUTO") "LATIN" else configured)
        return try {
            recognizeWith(recognizer, bitmap, if (configured == "AUTO") "LATIN" else configured)
        } finally {
            recognizer.close()
        }
    }

    private fun currentPrefsSnapshot(): OcrOptions {
        val prefs = context?.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        return OcrOptions(prefs?.getString("script", "AUTO") ?: "AUTO", prefs?.getBoolean("high_accuracy", true) ?: true)
    }

    suspend fun recognizeScriptAware(bitmap: Bitmap, options: OcrOptions = currentPrefsSnapshot()): List<TranslationRegion> {
        val session = OcrRecognizerSession(::createRecognizer) { it.close() }
        return try { recognizePage(bitmap, options, session) } finally { session.close() }
    }

    private suspend fun recognizePage(
        bitmap: Bitmap, options: OcrOptions, session: OcrRecognizerSession<TextRecognizer>
    ): List<TranslationRegion> {
        // Long webtoon pages must keep glyph resolution; use overlapping vertical OCR tiles.
        currentCoroutineContext().ensureActive()
        if (bitmap.height <= 2048) return recognizeTile(bitmap, options, session)
        val output = mutableListOf<TranslationRegion>()
        val margins = mutableListOf<Float>()
        var y = 0
        while (y < bitmap.height) {
            currentCoroutineContext().ensureActive()
            val height = minOf(2048, bitmap.height - y)
            val tile = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, height)
            try {
                val recognized = recognizeTile(tile, options, session)
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
            } finally { if (tile !== bitmap) tile.recycle() }
            if (y + height >= bitmap.height) break
            y += 1792
        }
        // Tile-local merging cannot join an OCR block split across the tile boundary.
        // Dedupe first, then apply the same conservative balloon grouping in page space.
        return mergeLikelySameBalloon(output.sortedBy { it.bounds.top })
    }

    private suspend fun recognizeTile(
        bitmap: Bitmap, options: OcrOptions, session: OcrRecognizerSession<TextRecognizer>
    ): List<TranslationRegion> {
        val script = options.script.uppercase(Locale.ROOT)
        val highAccuracy = options.highAccuracy
        val scripts = if (script != "AUTO") listOf(script) else if (!highAccuracy) listOf("LATIN") else
            listOf("LATIN", "DEVANAGARI", "CHINESE", "JAPANESE", "KOREAN")
        val candidates = readOcrScripts(scripts, { it },
            { readingScript -> session.read(readingScript) { recognizeWith(it, bitmap, readingScript, mergeBlocks = false) } }, { },
            onFailure = { failedScript, failure -> Log.w("MangaLensOCR", "$failedScript recognizer failed", failure) })
            .flatMap { it.second }
        val fused = if (script == "AUTO") chooseOcrReadings(candidates.map(::readingCandidate), allowWeakForRetry = highAccuracy)
            .map(candidates::get) else candidates
        val grouped = mergeLikelySameBalloon(fused)
        val best = when {
            !highAccuracy -> grouped
            grouped.isEmpty() -> retryEmptyTile(bitmap, script, session)
            else -> retryWeakRegions(bitmap, grouped, script, session)
        }
        val accepted = if (script == "AUTO") best.filter(::isPlausibleRegion) else best
        return orderOcrReadings(accepted.map(::readingCandidate)).map(accepted::get)
    }

    private fun readingCandidate(region: TranslationRegion): OcrReading = OcrReading(
        source = region.source, script = region.recognizerScript.ifBlank { dominantScript(listOf(region)) },
        confidence = region.recognitionConfidence,
        bounds = OcrBox(region.bounds.left, region.bounds.top, region.bounds.right, region.bounds.bottom),
        plausible = isPlausibleRegion(region), textSize = region.textSize,
        lineBounds = region.lineBounds.map { OcrBox(it.left, it.top, it.right, it.bottom) },
        retryable = isPlausibleRegion(region, allowLowConfidenceForRetry = true)
    )

    private fun mapRetryReading(region: TranslationRegion, plan: OcrRetryPlan): TranslationRegion {
        fun mapBox(box: RectF): RectF {
            val mapped = plan.map(OcrBox(box.left, box.top, box.right, box.bottom))
            return RectF(mapped.left, mapped.top, mapped.right, mapped.bottom)
        }
        return region.copy(bounds = mapBox(region.bounds), lineBounds = region.lineBounds.map(::mapBox),
            textSize = region.textSize / minOf(plan.scaleX, plan.scaleY))
    }

    private suspend fun retryEmptyTile(
        bitmap: Bitmap, configuredScript: String, session: OcrRecognizerSession<TextRecognizer>
    ): List<TranslationRegion> {
        currentCoroutineContext().ensureActive()
        val plan = planOcrRetry(OcrBox(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            bitmap.width, bitmap.height, 16f, maxPixels = 4_000_000, maxDimension = 2560) ?: return emptyList()
        val enhanced = Bitmap.createScaledBitmap(bitmap, plan.scaledWidth, plan.scaledHeight, true)
        try {
            val script = if (configuredScript == "AUTO") "LATIN" else configuredScript
            val reread = session.read(script) { recognizeWith(it, enhanced, script) }
            return reread.map { mapRetryReading(it, plan) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("MangaLensOCR", "Empty-tile retry failed", failure)
            return emptyList()
        } finally { if (enhanced !== bitmap) enhanced.recycle() }
    }

    private suspend fun retryWeakRegions(
        bitmap: Bitmap, regions: List<TranslationRegion>, configuredScript: String,
        session: OcrRecognizerSession<TextRecognizer>
    ): List<TranslationRegion> {
        val output = regions.toMutableList()
        for (index in chooseOcrRetryTargets(regions.map(::readingCandidate))) {
            currentCoroutineContext().ensureActive()
            val original = regions[index]
            val candidate = readingCandidate(original)
            val uncertainSource = OcrSourceQuality.needsPixelRetry(original.source)
            val plan = planOcrRetryWithScale(candidate.bounds, bitmap.width, bitmap.height, original.textSize,
                maxPixels = 1_000_000, maxDimension = 1280,
                maxScale = if (uncertainSource) 3f else 2f) ?: continue
            val crop = Bitmap.createBitmap(bitmap, plan.crop.left.toInt(), plan.crop.top.toInt(), plan.crop.width.toInt(), plan.crop.height.toInt())
            try {
                val enhanced = Bitmap.createScaledBitmap(crop, plan.scaledWidth, plan.scaledHeight, true)
                try {
                    val script = if (configuredScript == "AUTO") candidate.script else configuredScript
                    val reread = session.read(script) { recognizeWith(it, enhanced, script) }
                    val mapped = reread.map { mapRetryReading(it, plan) }
                    val selected = chooseOcrRetryReadingGroup(candidate, mapped.map(::readingCandidate))
                    if (selected.isNotEmpty()) {
                        val parts = selected.map(mapped::get)
                        val combined = composeOcrRetryReading(parts.map(::readingCandidate))
                        output[index] = parts.first().copy(source = combined.source, translated = combined.source,
                            bounds = RectF(combined.bounds.left, combined.bounds.top, combined.bounds.right, combined.bounds.bottom),
                            lineBounds = parts.flatMap { it.lineBounds }, textSize = combined.textSize,
                            recognitionConfidence = combined.confidence)
                    }
                    regionRetryObserver?.invoke(original, mapped, output[index].takeIf { it !== original })
                } finally { if (enhanced !== crop) enhanced.recycle() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // An optional crop retry must not discard the first reading or other bubbles.
                Log.w("MangaLensOCR", "Region retry failed; retaining the first reading", failure)
            } finally { if (crop !== bitmap) crop.recycle() }
        }
        return output
    }

    /**
     * ML Kit occasionally interprets decorative borders/runes as a short Latin word.
     * Keep real dialogue permissive, but reject low-confidence garbage and narrow vertical
     * Latin pseudo-words that produce floating replacement labels over character artwork.
     */
    private fun isPlausibleRegion(region: TranslationRegion, allowLowConfidenceForRetry: Boolean = false): Boolean {
        val value = region.source.trim()
        if (value.isBlank()) return false
        val letters = value.count(Char::isLetterOrDigit)
        if (letters == 0) return false
        val confidence = region.recognitionConfidence
        if (!allowLowConfidenceForRetry && region.sourceLanguage == LocalSourceLanguage.ENGLISH &&
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

        return !(!allowLowConfidenceForRetry && value.length <= 2 && confidence in .0001f..0.54f)
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
        val prepared = mutableListOf<Triple<TranslationRegion, android.graphics.Rect,
            com.mangalens.core.translation.MangaLettering.Style>>()
        try {
            // Erase all source lettering before adding translations. Retain only metadata,
            // not every reconstructed bitmap on a potentially very long webtoon page.
            regions.forEach { region ->
                val patch = com.mangalens.core.translation.MangaLettering.prepare(
                    output, region.bounds, region.lineBounds, region.source
                )
                try {
                    com.mangalens.core.translation.MangaLettering.drawBackground(canvas, patch)
                    prepared += Triple(region, android.graphics.Rect(patch.bounds), patch.style)
                } finally {
                    patch.background.recycle()
                }
            }
            prepared.forEach { (region, bounds, style) ->
                com.mangalens.core.translation.MangaLettering.drawText(canvas, bounds, style, region.translated)
            }
            return output
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
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

private val ocrCallbackExecutor = Executor { it.run() }
private val cjkScripts = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
private fun isCjkCharacter(character: Char): Boolean = Character.UnicodeScript.of(character.code) in cjkScripts

/** The native task retains its input image until completion, even after its owner is cancelled. */
internal suspend fun <T> awaitOcrCompletion(register: ((Result<T>) -> Unit) -> Unit): T {
    currentCoroutineContext().ensureActive()
    val result = withContext(NonCancellable) {
        suspendCoroutine<Result<T>> { continuation ->
            try {
                register { continuation.resume(it) }
            } catch (failure: Exception) {
                continuation.resume(Result.failure(failure))
            }
        }
    }
    currentCoroutineContext().ensureActive()
    return result.getOrThrow()
}

internal suspend fun <R, T> readOcrScripts(
    scripts: List<String>,
    open: (String) -> R,
    recognize: suspend (R) -> List<T>,
    close: (R) -> Unit,
    onFailure: (String, Exception) -> Unit = { _, _ -> }
): List<Pair<String, List<T>>> {
    val failures = mutableListOf<Exception>()
    val results = mutableListOf<Pair<String, List<T>>>()
    for (script in scripts) {
        currentCoroutineContext().ensureActive()
        val resource = try {
            open(script)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += failure
            onFailure(script, failure)
            continue
        }
        try {
            results += script to recognize(resource)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += failure
            onFailure(script, failure)
        } finally {
            close(resource)
        }
    }
    if (results.isEmpty() && failures.isNotEmpty()) throw failures.first()
    return results
}

internal data class OcrBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
    val valid: Boolean get() = width > 0f && height > 0f &&
        listOf(left, top, right, bottom, area).all(Float::isFinite)

    fun intersectionArea(other: OcrBox): Float =
        (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f) *
            (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
}

internal data class OcrReading(
    val source: String,
    val script: String,
    val confidence: Float,
    val bounds: OcrBox,
    val plausible: Boolean = true,
    val textSize: Float = 24f,
    val lineBounds: List<OcrBox> = listOf(bounds),
    val retryable: Boolean = plausible
)

internal fun ocrReadingQuality(reading: OcrReading): Double {
    val letters = reading.source.filter(Char::isLetter)
    if (letters.isEmpty()) return if (reading.source.any(Char::isDigit)) .3 else Double.NEGATIVE_INFINITY
    val script = reading.script.uppercase(Locale.ROOT)
    val affinity = letters.sumOf { character ->
        val native = Character.UnicodeScript.of(character.code)
        when {
            script == "LATIN" && native == Character.UnicodeScript.LATIN -> 1.0
            script == "DEVANAGARI" && native == Character.UnicodeScript.DEVANAGARI -> 1.0
            script == "KOREAN" && native == Character.UnicodeScript.HANGUL -> 1.0
            script == "CHINESE" && native == Character.UnicodeScript.HAN -> 1.0
            script == "JAPANESE" && native in cjkScripts -> 1.0
            native == Character.UnicodeScript.LATIN -> .75
            else -> 0.0
        }
    } / letters.length
    val confidence = reading.confidence.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f) ?: .55f
    val sourcePenalty = if (OcrSourceQuality.needsPixelRetry(reading.source)) .30 else 0.0
    return confidence * .70 + affinity * .30 - sourcePenalty
}

internal fun chooseOcrReadings(readings: List<OcrReading>, allowWeakForRetry: Boolean = false): List<Int> {
    val accepted = mutableListOf<Int>()
    val ranked = readings.indices.filter {
        readings[it].bounds.valid && (readings[it].plausible || (allowWeakForRetry && readings[it].retryable))
    }
        .sortedByDescending { ocrReadingQuality(readings[it]) }
    for (index in ranked) {
        val box = readings[index].bounds
        val duplicate = accepted.any { previous ->
            val other = readings[previous].bounds
            val smaller = minOf(box.area, other.area)
            val comparable = maxOf(box.area, other.area) <= smaller * 4f
            comparable && box.intersectionArea(other) > smaller * .55f
        }
        if (!duplicate) accepted += index
    }
    return accepted.sorted()
}

internal data class OcrRetryPlan(val crop: OcrBox, val scaledWidth: Int, val scaledHeight: Int) {
    val scaleX: Float get() = scaledWidth / crop.width
    val scaleY: Float get() = scaledHeight / crop.height

    fun map(box: OcrBox): OcrBox = OcrBox(
        crop.left + box.left / scaleX, crop.top + box.top / scaleY,
        crop.left + box.right / scaleX, crop.top + box.bottom / scaleY
    )
}

internal fun planOcrRetry(
    bounds: OcrBox, imageWidth: Int, imageHeight: Int, textSize: Float,
    maxPixels: Int = 1_000_000, maxDimension: Int = 1280
): OcrRetryPlan? = planOcrRetryWithScale(bounds, imageWidth, imageHeight, textSize, maxPixels, maxDimension, 2f)

internal fun planOcrRetryWithScale(
    bounds: OcrBox, imageWidth: Int, imageHeight: Int, textSize: Float,
    maxPixels: Int, maxDimension: Int, maxScale: Float
): OcrRetryPlan? {
    if (!bounds.valid || imageWidth <= 0 || imageHeight <= 0 || maxPixels <= 0 || maxDimension <= 0 ||
        !maxScale.isFinite() || maxScale <= 1f) return null
    val padding = (textSize.takeIf(Float::isFinite) ?: 16f).coerceIn(8f, 40f) * .65f + 4f
    val crop = OcrBox(
        kotlin.math.floor(bounds.left - padding).coerceAtLeast(0f),
        kotlin.math.floor(bounds.top - padding).coerceAtLeast(0f),
        kotlin.math.ceil(bounds.right + padding).coerceAtMost(imageWidth.toFloat()),
        kotlin.math.ceil(bounds.bottom + padding).coerceAtMost(imageHeight.toFloat())
    )
    if (!crop.valid) return null
    val factor = minOf(maxScale, maxDimension / crop.width, maxDimension / crop.height,
        kotlin.math.sqrt(maxPixels / crop.area))
    return if (factor > 1.1f) OcrRetryPlan(crop, (crop.width * factor).toInt(), (crop.height * factor).toInt()) else null
}

internal fun chooseOcrRetryTargets(readings: List<OcrReading>): List<Int> = readings.indices
    .filter { readings[it].bounds.valid && (readings[it].textSize < 22f || readings[it].confidence in .0001f..0.68f ||
        OcrSourceQuality.needsPixelRetry(readings[it].source)) }
    .sortedBy { ocrReadingQuality(readings[it]) }
    .take(6)

internal fun isVerticalOcr(reading: OcrReading): Boolean {
    if (reading.source.count(::isCjkCharacter) < 2) return false
    val lines = reading.lineBounds.filter { it.valid }.ifEmpty { listOf(reading.bounds).filter { it.valid } }
    if (lines.isEmpty()) return false
    val totalArea = lines.sumOf { it.area.toDouble() }
    if (lines.filter { it.height > it.width * 1.35f }.sumOf { it.area.toDouble() } > totalArea * .60) return true
    val squareGlyphs = lines.count { it.height / it.width in .8f..1.3f }
    val horizontalSpread = lines.maxOf { (it.left + it.right) * .5f } - lines.minOf { (it.left + it.right) * .5f }
    return lines.size >= 3 && squareGlyphs >= lines.size * .75f &&
        reading.bounds.height > reading.bounds.width * 1.6f && horizontalSpread < lines.minOf { it.width } * .65f
}

internal fun orderOcrReadings(readings: List<OcrReading>): List<Int> {
    val rtl = readings.any { reading ->
        (reading.script == "JAPANESE" && reading.source.any(::isCjkCharacter)) || isVerticalOcr(reading)
    }
    val byTop = readings.indices.sortedWith(compareBy<Int> { readings[it].bounds.top }.thenBy { readings[it].bounds.left })
    val rows = mutableListOf<MutableList<Int>>()
    for (index in byTop) {
        val box = readings[index].bounds
        val row = rows.lastOrNull()
        val anchor = row?.firstOrNull()?.let { readings[it].bounds }
        val overlap = anchor?.let { minOf(box.bottom, it.bottom) - maxOf(box.top, it.top) } ?: 0f
        if (anchor != null && minOf(box.height, anchor.height) > 0f &&
            maxOf(box.height, anchor.height) <= minOf(box.height, anchor.height) * 3f &&
            overlap >= minOf(box.height, anchor.height) * .5f) row += index
        else rows += mutableListOf(index)
    }
    return rows.flatMap { row -> row.sortedWith(compareBy<Int> {
        if (rtl) -readings[it].bounds.right else readings[it].bounds.left
    }.thenBy { readings[it].bounds.top }) }
}

internal fun canMergeVerticalOcr(right: OcrReading, left: OcrReading): Boolean {
    val verticalA = isVerticalOcr(right)
    val verticalB = isVerticalOcr(left)
    if (!verticalA && !verticalB) return false
    val maxText = maxOf(right.textSize, left.textSize).coerceAtLeast(8f)
    val minText = minOf(right.textSize, left.textSize).coerceAtLeast(1f)
    if (minText / maxText < .70f || right.lineBounds.size + left.lineBounds.size > 8) return false
    // A block may become identifiable as a column only after several single glyphs merge.
    // Continue that same column instead of stranding its remaining one-character fragments.
    val fragment = if (!verticalA) right else if (!verticalB) left else null
    val glyphFragment = fragment == null || (fragment.source.count(::isCjkCharacter) in 1..2 &&
        fragment.bounds.width <= maxText * 1.6f && fragment.bounds.height <= maxText * 1.6f)
    val xOverlap = minOf(right.bounds.right, left.bounds.right) - maxOf(right.bounds.left, left.bounds.left)
    val stackedGap = left.bounds.top - right.bounds.bottom
    if (glyphFragment && xOverlap >= minOf(right.bounds.width, left.bounds.width) * .70f &&
        stackedGap in -maxText * .15f..maxText * .95f) return true
    if (!verticalA || !verticalB) return false
    val gap = right.bounds.left - left.bounds.right
    val overlap = minOf(right.bounds.bottom, left.bounds.bottom) - maxOf(right.bounds.top, left.bounds.top)
    return gap in -maxText * .15f..maxText * .85f &&
        overlap >= minOf(right.bounds.height, left.bounds.height) * .78f
}

internal fun orderVerticalOcrLines(lines: List<OcrBox>): List<Int> {
    val byRight = lines.indices.sortedByDescending { lines[it].right }
    val columns = mutableListOf<MutableList<Int>>()
    for (index in byRight) {
        val box = lines[index]
        val column = columns.lastOrNull()
        val anchor = column?.firstOrNull()?.let(lines::get)
        val overlap = anchor?.let { minOf(box.right, it.right) - maxOf(box.left, it.left) } ?: 0f
        if (anchor != null && overlap >= minOf(box.width, anchor.width) * .5f) column += index
        else columns += mutableListOf(index)
    }
    return columns.flatMap { column -> column.sortedBy { lines[it].top } }
}

