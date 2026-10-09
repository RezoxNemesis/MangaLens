package com.mangalens.core.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.mangalens.core.reader.ReaderPromoPolicy
import com.mangalens.engine.AdvancedTranslationEngine
import com.mangalens.engine.LocalSourceLanguage
import com.mangalens.engine.OcrSourceQuality
import com.mangalens.engine.OcrSourceResolutionProof
import com.mangalens.engine.TranslationRegion
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.OrezTranslationEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** The reader's individual-page path and durable chapter workers share this heavy-compute lane. */
object ChapterTranslationComputeLane {
    private val mutex = Mutex()
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/** One page at a time. Only verified lettering and a private PNG path leave this class. */
internal class ChapterPageTranslator(
    private val context: Context,
    private val store: ChapterTranslationStore,
    private val task: ChapterTranslationTask
) : AutoCloseable {
    private val ocr = AdvancedTranslationEngine(context)
    private val translator = TranslationService()
    private val refiner = TranslationOrezRefiner(context)
    private val config = task.config
    private val style = config.style()
    private val memoryScope = "chapter:" + task.chapterId
    private val memoryStyleKey = style.memoryKey + (config.refinementRequest?.let {
        ":refinement:" + TranslationRefinementPolicy.hash(TranslationRefinementRequestCodec.identity(it)).take(20)
    } ?: "")

    suspend fun translate(page: ChapterTranslationPage, chapterContext: String): ChapterTranslationPage {
        checkActive()
        val source = store.sourceFile(page) ?: error("Page ${page.index} is not available locally. Retry its source.")
        check(page.sourceSha256 != null && ChapterTranslationStore.sha256(source) == page.sourceSha256) {
            "Source page changed. Resume to translate the current page."
        }
        val decoded = decodeBounded(source) ?: error("Unable to decode source page ${page.index}.")
        val bitmap = decoded.bitmap
        var generated: File? = null
        try {
            val proof = OcrSourceResolutionProof(task.id, task.generation, task.chapterId, page.index,
                source.canonicalPath, requireNotNull(page.sourceSha256), decoded.originalWidth, decoded.originalHeight,
                bitmap.width, bitmap.height)
            val originalSource = ChapterOriginalOcrSource(proof, source.parentFile!!) {
                checkActive()
                val current = store.get(task.id)
                val currentPage = current?.pages?.firstOrNull { it.index == page.index }
                proof.takeIf { current?.generation == task.generation && current.chapterId == task.chapterId &&
                    current.config == task.config && currentPage?.sourceSha256 == proof.sourceSha256 &&
                    currentPage.sourcePath?.let { store.sourceFile(currentPage)?.canonicalPath } == proof.sourcePath }
            }
            // The engine waits for each active native ML Kit task to finish before cancellation
            // reaches this finally block, so it cannot read a prematurely recycled source/tile.
            val regions = ocr.recognizeScriptAware(bitmap, AdvancedTranslationEngine.OcrOptions(config.ocrScript, config.highAccuracy), originalSource)
            checkActive()
            if (regions.isEmpty()) return if (page.lettering.isNotEmpty()) page.copy(
                status = ChapterTranslationPageStatus.PARTIAL,
                error = "No new readable text was detected. Earlier successful bubbles were kept.")
            else page.copy(status = ChapterTranslationPageStatus.NO_TEXT, cleanedPath = null, cleanedSha256 = null,
                imageWidth = 0, imageHeight = 0, lettering = emptyList(), rejectedRegions = 0, error = null)

            if (page.lettering.isEmpty() && ReaderPromoPolicy.isLikelyPromo(regions.joinToString("\n") { it.source })) {
                return page.copy(status = ChapterTranslationPageStatus.PROMO, cleanedPath = null, cleanedSha256 = null,
                    imageWidth = 0, imageHeight = 0, lettering = emptyList(), rejectedRegions = 0, error = null)
            }

            val lettering = restoreSuccessfulBubbles(bitmap, page).toMutableList()
            val retainedCount = lettering.size
            val canvas = Canvas(bitmap)
            var rejected = 0
            var transientFailures = 0
            var firstError: String? = null
            for ((position, region) in regions.withIndex()) {
                checkActive()
                if (lettering.any { matches(it, region) }) continue
                if (lettering.size >= ChapterTranslationStore.MAX_LETTERING || position >= ChapterTranslationStore.MAX_LETTERING) {
                    rejected = (rejected + regions.size - position).coerceAtMost(ChapterTranslationStore.MAX_LETTERING)
                    firstError = firstError ?: "This page exceeds the bounded lettering limit. Successful bubbles were kept."
                    break
                }
                try {
                    require(region.source.length in 1..ChapterTranslationStore.MAX_TEXT_CHARS) { "OCR region is too large to translate safely." }
                    if (OcrSourceQuality.needsPixelRetry(region.source)) throw TranslationQualityException()
                    val localizedDraft = recall(region.source) ?: run {
                        // Edit pinned Hindi before romanization so the exact validated
                        // intermediate remains evidence for the final Roman lettering.
                        val draftTarget = if (HindiRomanization.isTarget(config.targetLanguage) &&
                            (config.localRefinement || style.id in setOf("formal", "custom"))) "hi" else config.targetLanguage
                        val draft = withTimeoutOrNull(90_000L) {
                            translator.translateDraft(region.source, draftTarget, sourceHint(region))
                        } ?: error("On-device translation model could not finish. Check its download and resume.")
                        checkActive()
                        val refined = if (config.localRefinement) {
                            val request = config.refinementRequest
                                ?: error("This older task has no captured refinement model. Start a new translation explicitly.")
                            check(request.enabled && request.pinnedModel != null) { "This task captured no available refinement model. Install a verified pack, then start a new translation explicitly." }
                            val result = refiner.refineCaptured(region.source, draft.text, draftTarget, request, chapterContext)
                            check(TranslationRefinementPolicy.matches(result, region.source, draft.text, draftTarget, request, chapterContext)) {
                                "The captured local refinement model could not finish (${result.status.name.lowercase()}). Retry this task; its model stays pinned."
                            }
                            if (style.id !in setOf("natural", "faithful") && !TranslationQualityPolicy.isUsable(region.source, result.text, draftTarget))
                                throw TranslationQualityException()
                            result.text
                        } else ""
                        val selected = TranslationQualityPolicy.chooseDraft(region.source, draft, refined, draftTarget, style)
                        if (draftTarget != config.targetLanguage) HinglishTranslationOutput.fromHindiDraft(region.source, selected.text, style) else selected
                    }
                    val localized = localizedDraft.text
                    require(localized.length in 1..ChapterTranslationStore.MAX_TEXT_CHARS) { "Translated lettering is too large to save safely." }
                    checkActive()
                    val bounds = boundedSourceBounds(region.bounds, bitmap.width, bitmap.height)
                    val patch = MangaLettering.prepare(bitmap, region.bounds, region.lineBounds, region.source, config.preserveStyle)
                    try {
                        // The cumulative clean page prevents later overlapping patches from
                        // bringing an earlier source glyph back into the recovered surface.
                        canvas.drawBitmap(patch.background, patch.bounds.left.toFloat(), patch.bounds.top.toFloat(), null)
                        lettering += SavedMangaLettering(region.source, localized, patch.bounds.left, patch.bounds.top,
                            patch.bounds.right, patch.bounds.bottom, patch.style.family, patch.style.face, patch.style.color,
                            patch.style.size, patch.style.alignment.name, bounds.left, bounds.top, bounds.right, bounds.bottom,
                            savedHindiDraft = localizedDraft.hindiDraft,
                            originalSourceBounds = OriginalMangaGeometry.fromSampled(bounds.left, bounds.top, bounds.right, bounds.bottom,
                                bitmap.width, bitmap.height, decoded.originalWidth, decoded.originalHeight))
                    } finally { patch.background.recycle() }
                    remember(region.source, localizedDraft)
                    transientFailures = 0
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (deferred: com.mangalens.core.compute.ResourcePausedException) {
                    throw deferred
                } catch (quality: TranslationQualityException) {
                    rejected++
                    firstError = firstError ?: quality.message
                } catch (failure: Exception) {
                    rejected++
                    firstError = firstError ?: failure.message ?: "One bubble could not be translated."
                    // Do not download/reload a failed model independently for hundreds of bubbles.
                    // A manual resume retries the unfinished page and retains its successes.
                    if (++transientFailures >= 3) {
                        rejected = (rejected + regions.size - position - 1).coerceAtMost(ChapterTranslationStore.MAX_LETTERING)
                        break
                    }
                }
            }
            // A retry that misses an earlier rejected region must not report that missing
            // source dialogue as successfully translated just because its neighbours re-OCRed.
            rejected = maxOf(rejected, (page.rejectedRegions - (lettering.size - retainedCount)).coerceAtLeast(0))
            if (rejected > 0 && firstError == null) firstError = "Some earlier source bubbles still need a successful retry."
            checkActive()
            if (lettering.isEmpty()) return page.copy(status = ChapterTranslationPageStatus.FAILED, cleanedPath = null,
                cleanedSha256 = null, imageWidth = 0, imageHeight = 0, lettering = emptyList(),
                rejectedRegions = rejected.coerceAtMost(ChapterTranslationStore.MAX_LETTERING),
                error = (firstError ?: "No bubble passed translation quality checks. Original text was preserved.").take(ChapterTranslationStore.MAX_ERROR_CHARS))

            generated = store.createOutputFile(task.id, task.generation, page.index)
            val checksum = persistCleanSurface(bitmap, generated)
            checkActive()
            return page.copy(status = if (rejected == 0) ChapterTranslationPageStatus.COMPLETED else ChapterTranslationPageStatus.PARTIAL,
                cleanedPath = generated.absolutePath, cleanedSha256 = checksum, imageWidth = bitmap.width, imageHeight = bitmap.height,
                lettering = lettering.toList(), originalWidth = decoded.originalWidth, originalHeight = decoded.originalHeight, rejectedRegions = rejected.coerceAtMost(ChapterTranslationStore.MAX_LETTERING),
                error = firstError?.take(ChapterTranslationStore.MAX_ERROR_CHARS))
        } catch (failure: Throwable) {
            generated?.let { store.discardUnreferenced(it.absolutePath) }
            throw failure
        } finally { bitmap.recycle() }
    }

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (!store.isCurrent(task.id, task.generation)) throw CancellationException("Translation generation was paused, cancelled or replaced.")
    }

    private fun restoreSuccessfulBubbles(bitmap: Bitmap, page: ChapterTranslationPage): List<SavedMangaLettering> {
        val retained = page.lettering.filter {
            TranslationQualityPolicy.isUsable(it.source, it.translated, config.targetLanguage, it.savedHindiDraft)
        }
        if (retained.isEmpty()) return emptyList()
        val path = page.cleanedPath ?: error("Earlier successful bubbles have no cleaned page surface.")
        val previous = BitmapRegionDecoder.newInstance(path, false)
            ?: error("Earlier cleaned page is unreadable. Resume to rebuild this page.")
        try {
            check(previous.width == page.imageWidth && previous.height == page.imageHeight) {
                "Saved lettering dimensions do not match its cleaned page."
            }
            val scaleX = bitmap.width.toFloat() / page.imageWidth.coerceAtLeast(1)
            val scaleY = bitmap.height.toFloat() / page.imageHeight.coerceAtLeast(1)
            val updated = retained.map { scale(it, scaleX, scaleY, bitmap.width, bitmap.height) }
            val canvas = Canvas(bitmap)
            updated.zip(retained).forEach { (text, original) ->
                val sourceRect = Rect(original.left, original.top, original.right, original.bottom)
                var sample = 1
                val maxPatchPixels = minOf(2_000_000L, bitmap.width.toLong() * bitmap.height)
                while ((sourceRect.width().toLong() / sample) * (sourceRect.height() / sample) > maxPatchPixels) sample *= 2
                val crop = previous.decodeRegion(sourceRect, BitmapFactory.Options().apply {
                    inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
                }) ?: error("Saved cleaned lettering region could not be decoded.")
                try {
                    canvas.drawBitmap(crop, null, Rect(text.left, text.top, text.right, text.bottom), null)
                } finally { crop.recycle() }
            }
            return updated
        } finally { previous.recycle() }
    }

    private fun scale(text: SavedMangaLettering, x: Float, y: Float, width: Int, height: Int): SavedMangaLettering {
        val l = (text.left * x).roundToInt().coerceIn(0, width - 1)
        val t = (text.top * y).roundToInt().coerceIn(0, height - 1)
        val sl = (text.sourceLeft * x).roundToInt().coerceIn(0, width - 1)
        val st = (text.sourceTop * y).roundToInt().coerceIn(0, height - 1)
        return text.copy(left = l, top = t, right = (text.right * x).roundToInt().coerceIn(l + 1, width),
            bottom = (text.bottom * y).roundToInt().coerceIn(t + 1, height), size = (text.size * minOf(x, y)).coerceAtLeast(1f),
            sourceLeft = sl, sourceTop = st, sourceRight = (text.sourceRight * x).roundToInt().coerceIn(sl + 1, width),
            sourceBottom = (text.sourceBottom * y).roundToInt().coerceIn(st + 1, height))
    }

    private fun matches(text: SavedMangaLettering, region: TranslationRegion): Boolean {
        if (text.source.replace(Regex("\\s+"), " ").trim() != region.source.replace(Regex("\\s+"), " ").trim()) return false
        val previous = RectF(text.sourceLeft.toFloat(), text.sourceTop.toFloat(), text.sourceRight.toFloat(), text.sourceBottom.toFloat())
        val intersection = RectF(previous)
        return intersection.intersect(region.bounds) && intersection.width() * intersection.height() >=
            minOf(previous.width() * previous.height(), region.bounds.width() * region.bounds.height()) * .5f
    }

    private fun boundedSourceBounds(bounds: RectF, width: Int, height: Int): Rect {
        require(bounds.left.isFinite() && bounds.top.isFinite() && bounds.right.isFinite() && bounds.bottom.isFinite() &&
            bounds.width() > 0f && bounds.height() > 0f) { "OCR region has invalid geometry." }
        val left = floor(bounds.left).toInt().coerceIn(0, width - 1)
        val top = floor(bounds.top).toInt().coerceIn(0, height - 1)
        return Rect(left, top, ceil(bounds.right).toInt().coerceIn(left + 1, width), ceil(bounds.bottom).toInt().coerceIn(top + 1, height))
    }

    private fun decodeBounded(file: File) = OriginalMangaPageDecoder.decode(context, file)

    private fun persistCleanSurface(bitmap: Bitmap, output: File): String {
        check(output.parentFile!!.usableSpace >= maxOf(8L * 1024 * 1024, bitmap.width.toLong() * bitmap.height * 4L)) {
            "Not enough private storage for the cleaned page. Completed pages were kept."
        }
        val temporary = File(output.parentFile, output.name + ".part")
        try {
            FileOutputStream(temporary).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Unable to encode cleaned page." }
                stream.fd.sync()
            }
            check(temporary.length() in 1..ChapterTranslationStore.MAX_SURFACE_BYTES) { "Cleaned page exceeds the safe storage limit." }
            val hash = ChapterTranslationStore.sha256(temporary)
            check(temporary.renameTo(output)) { "Unable to atomically publish cleaned page." }
            return hash
        } finally { temporary.delete() }
    }

    private fun sourceHint(region: TranslationRegion): String? = when (region.sourceLanguage) {
        LocalSourceLanguage.ENGLISH -> "en".takeIf { EnglishDialoguePolicy.shouldHintEnglish(region.source) }
        LocalSourceLanguage.JAPANESE -> "ja"
        LocalSourceLanguage.KOREAN -> "ko"
        LocalSourceLanguage.HINDI -> "hi"
        else -> null // Latin and Han glyphs alone do not identify their spoken language.
    }

    internal suspend fun recall(source: String): TranslationDraft? = try {
        if (config.localRefinement && config.refinementRequest?.pinnedModel == null) null
        else OrezRoomDatabase.get(context).datasets().exactTranslationScoped(source.trim(), config.targetLanguage, memoryStyleKey, memoryScope)
            ?.let { TranslationMemoryCodec.decode(source, it, config.targetLanguage) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    internal suspend fun remember(source: String, translated: TranslationDraft) {
        try {
            val identity = listOf(source.trim(), config.targetLanguage, memoryStyleKey, memoryScope).joinToString("|")
            val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            OrezRoomDatabase.get(context).datasets().upsertTranslation(OrezTranslationEntity(key = key, source = source.trim(),
                target = TranslationMemoryCodec.encode(source, translated, config.targetLanguage),
                targetLanguage = config.targetLanguage, style = memoryStyleKey, scope = memoryScope))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional scoped memory never invalidates a verified page. */ }
    }

    override fun close() { ocr.close(); translator.close(); refiner.close() }
}
