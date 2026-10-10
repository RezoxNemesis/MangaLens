package com.mangalens.core.translation.inpainting

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import com.mangalens.core.compute.*
import com.mangalens.core.translation.*
import com.mangalens.core.translation.memory.MemoryRegionBounds
import com.mangalens.engine.AdvancedTranslationEngine
import com.mangalens.engine.TranslationRegion
import com.mangalens.ui.reader.acquireReaderBubblePreviewOnIo
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

internal interface SavedBubbleArtworkRepair {
    val model: LaMaModelManager
    suspend fun preview(inspection: ReaderBubbleInspection, owner: NativeComputePrecondition): ReaderLaMaRepairPreview
}

/** Transient display only: no native checkpoint, original image or personal journal can be written. */
internal class ReaderLaMaRepairPreview internal constructor(val original: Bitmap, val proposed: Bitmap,
    val sourceSha256: String, val maskSha256: String, val selectedPixels: Int, private val current: () -> Boolean,
    private val retainFailedImages: (LaMaNativeCloseUnproven) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    @Volatile private var deliveryCurrent: (() -> Boolean)? = null
    internal fun bindDeliveryCurrent(check: () -> Boolean) { check(deliveryCurrent == null); deliveryCurrent = check }
    fun isCurrent() = !closed.get() && current() && deliveryCurrent?.invoke() == true
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try { proposed.recycle() } catch (problem: Throwable) { failure = problem }
        try { original.recycle() } catch (problem: Throwable) { if (failure == null) failure = problem else failure.addSuppressed(problem) }
        failure?.let { retainFailedImages(LaMaNativeCloseUnproven(listOf(original, proposed), this, it)) }
    }
}

/** Real optional CPU preview using separately decoded, held and verified original pixels. */
internal class ReaderLaMaArtworkService(private val context: Context,
    private val factory: LaMaCpuSessionFactory? = null) : SavedBubbleArtworkRepair {
    override val model: LaMaModelManager by lazy { LaMaModelManager.shared(context) }

    override suspend fun preview(inspection: ReaderBubbleInspection, owner: NativeComputePrecondition): ReaderLaMaRepairPreview =
        acquireReaderBubblePreviewOnIo {
            withContext(owner) {
            val captured = model.captureInstalled() ?: error("Download and verify the optional repair pack first.")
            val caller = currentCoroutineContext()
            suspend fun current(waited: Boolean = true) {
                caller.ensureActive(); owner.validate(waited)
                check(model.isCurrent(captured)) { "The installed repair pack changed. Reopen this selection." }
                ResourceGovernorRuntime.shared.requireNativeEntry(ResourceWorkKind.INTERACTIVE)
            }
            current(); requireMemory()
            val source = inspection.source ?: error("Retranslate this page to establish original coordinates.")
            val page = inspection.proof.page
            val patch = ReaderSfxRestorationPlan.originalPatch(page, inspection.letteringIndex, inspection.expectedNative)
                ?: error("This writable patch overlaps another saved region. Keep the original or retry OCR.")
            // Context comes solely from actual original patch/dimensions; no tap or display crop is trusted.
            val halo = maxOf(8, minOf(96, maxOf(patch.right - patch.left, patch.bottom - patch.top) / 8))
            val bounds = MemoryRegionBounds(maxOf(0, patch.left - halo), maxOf(0, patch.top - halo),
                minOf(source.imageWidth, patch.right + halo), minOf(source.imageHeight, patch.bottom + halo))
            val contextSource = source.copy(bounds = bounds).also { it.validate() }
            var lease: NativeComputeAdmission.Lease? = null
            var original: LaMaOriginalPixels? = null; var session: LaMaCpuSession? = null; var proposed: Bitmap? = null; var originalPreview: Bitmap? = null
            var transferred = false; var unsafe: LaMaNativeCloseUnproven? = null
            try {
                current()
                original = LaMaOriginalPixels.open(File(context.filesDir, "chapters"), contextSource) { current() }
                    ?: error("The verified original region is unavailable. Repair this page or reopen it.")
                val held = requireNotNull(original)
                val selected = held.sampled(source.bounds) ?: error("This source region cannot be sampled safely.")
                val width = held.bitmap.width; val height = held.bitmap.height
                val pixels = IntArray(width * height); held.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val nativeNeighbours = page.lettering.mapIndexedNotNull { index, letter ->
                    if (index == inspection.letteringIndex) null else OriginalMangaGeometry.fromSampled(letter.left, letter.top,
                        letter.right, letter.bottom, page.imageWidth, page.imageHeight, source.imageWidth, source.imageHeight).let {
                        held.sampled(MemoryRegionBounds(it.left, it.top, it.right, it.bottom))
                    }
                }
                current()
                val engine = AdvancedTranslationEngine(context)
                val readings = try { ChapterTranslationComputeLane.withLock { current(); engine.recognizeSavedRegionAlternatives(held.bitmap,
                    AdvancedTranslationEngine.OcrOptions(inspection.proof.task.config.ocrScript, inspection.proof.task.config.highAccuracy)) }
                    }
                    finally { engine.close() }
                current(); check(held.isCurrent()) { "The original source changed during OCR." }
                // Include every actual observation from every invoked recognizer, before choosing a target.
                val rawObserved = readings.flatten().also { require(it.size in 1..LaMaGlyphMaskPlan.MAX_OBSERVATIONS) {
                    "A complete bounded OCR neighbourhood is unavailable. Keep the original or retry OCR." } }
                val job = caller[Job]; val checkpoint = { job?.ensureActive(); check(model.isCurrent(captured)) { "The optional repair pack changed." } }
                require(rawObserved.sumOf { it.source.length.toLong() } <= 256 * 1024L) { "The observed neighbourhood text exceeds the preview budget." }
                val observed = rawObserved.mapIndexed { index, region ->
                    if (index % 16 == 0) checkpoint(); observation(region, width, height)
                }.distinct()
                val target = LaMaGlyphMaskPlan.select(inspection.expectedNative.source, selected, observed, checkpoint)
                    ?: error("Fresh original OCR does not identify this saved bubble unambiguously. Retry OCR or keep the original.")
                val mask = LaMaGlyphMaskPlan.create(width, height, pixels, selected, target, observed, nativeNeighbours, checkpoint)
                    ?: error("No conservative glyph-only mask is available for this artwork. Keep the original or retry OCR.")
                val input = LaMaTensorPlan.prepare(width, height, pixels, mask, checkpoint)
                current(); check(held.isCurrent()); requireMemory(needsWeights = true, needsSession = true)
                val weights = model.readForPreview(captured) { requireMemory(needsWeights = true, needsSession = true) }
                val granted = NativeComputeAdmission.shared.acquire(NativeComputeAdmission.Priority.INTERACTIVE) { caller.isActive && model.isCurrent(captured) }
                    ?: throw CancellationException("Repair preview owner closed.")
                lease = granted
                current(granted.waited); check(held.isCurrent()) { "The original source changed while awaiting the CPU engine." }
                current(); requireMemory(needsSession = true)
                val actualFactory = factory ?: OrtLaMaSessionFactory { requireMemory(needsSession = true) }
                val opened = try { actualFactory.open(weights, checkpoint) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { model.recordRuntimeCheck(captured, false); throw failure }
                session = opened; model.recordRuntimeCheck(captured, true)
                current(); check(held.isCurrent()); requireMemory()
                val output = opened.repair(input)
                // Await real run return and real close even if the selecting coroutine was cancelled.
                opened.close(); session = null
                current(); check(held.isCurrent()) { "The original source changed during the repair preview." }
                val result = LaMaTensorPlan.composite(input, output, checkpoint)
                proposed = Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
                originalPreview = Bitmap.createBitmap(input.original, width, height, Bitmap.Config.ARGB_8888)
                current(); check(held.isCurrent())
                // Decoder/FD cleanup is proved under admission before UI receives two independent images.
                held.close(); original = null
                current()
                ReaderLaMaRepairPreview(requireNotNull(originalPreview), requireNotNull(proposed), source.sourceSha256,
                    input.maskSha256, mask.count { it }, { model.isCurrent(captured) },
                    { model.retainUnprovenClose(it, null) }).also { transferred = true }
            } catch (problem: LaMaNativeCloseUnproven) { unsafe = problem; throw problem }
            finally {
                if (unsafe == null) try { session?.close() } catch (problem: Throwable) {
                    unsafe = if (problem is LaMaNativeCloseUnproven) problem else
                        LaMaNativeCloseUnproven(listOfNotNull(session), this@ReaderLaMaArtworkService, problem)
                }
                if (!transferred) {
                    for (resource in listOfNotNull(proposed, originalPreview, original)) try {
                        when (resource) { is Bitmap -> resource.recycle(); is LaMaOriginalPixels -> resource.close() }
                    } catch (problem: Throwable) {
                        val prior = unsafe
                        unsafe = LaMaNativeCloseUnproven(listOfNotNull(original, originalPreview, proposed, prior) + prior?.retained.orEmpty(),
                            this@ReaderLaMaArtworkService, problem)
                    }
                }
                if (unsafe != null) model.retainUnprovenClose(requireNotNull(unsafe), lease) else lease?.close()
                unsafe?.let { throw it }
            }
            }
        } ?: error("The selected repair preview is unavailable.")

    private fun observation(region: TranslationRegion, width: Int, height: Int): LaMaObservedText {
        fun rect(bounds: android.graphics.RectF): MangaWritableRect {
            require(bounds.left.isFinite() && bounds.top.isFinite() && bounds.right.isFinite() && bounds.bottom.isFinite())
            return MangaWritableRect(floor(bounds.left.toDouble()).toInt(), floor(bounds.top.toDouble()).toInt(),
                ceil(bounds.right.toDouble()).toInt(), ceil(bounds.bottom.toDouble()).toInt()).also { require(it.valid(width, height)) }
        }
        require(region.source.isNotBlank() && region.source.length <= 4096 && region.lineBounds.size in 1..256)
        return LaMaObservedText(region.source, rect(region.bounds), region.lineBounds.map(::rect))
    }
    private fun requireMemory(needsWeights: Boolean = false, needsSession: Boolean = false) {
        ResourceGovernorRuntime.shared.requireNativeEntry(ResourceWorkKind.INTERACTIVE)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo(); manager.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        if (!LaMaMemoryBudget.permits(runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()), info.availMem,
                info.threshold, info.lowMemory, needsWeights, needsSession)) throw ResourcePausedException(
            "This experimental pack needs its 208 MB Java weight array and estimated native headroom. Close other work or use the original/procedural page.")
    }
}
