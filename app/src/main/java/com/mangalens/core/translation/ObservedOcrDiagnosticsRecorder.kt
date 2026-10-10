package com.mangalens.core.translation

import android.graphics.Bitmap
import android.graphics.RectF
import com.mangalens.engine.OcrBox
import com.mangalens.engine.OcrReading
import com.mangalens.engine.TranslationRegion
import com.mangalens.engine.isVerticalOcr
import com.mangalens.ui.reader.ReaderPanelGeometry
import kotlin.math.ceil
import kotlin.math.floor

/** Records only this real original-image OCR pass; it neither classifies dialogue nor changes OCR order. */
internal class ObservedOcrDiagnosticsRecorder(private val regions: List<TranslationRegion>, bitmap: Bitmap,
    private val sourceSha: String, private val version: Int, cancellationCheck: () -> Unit) {
    private val width = bitmap.width; private val height = bitmap.height
    private val boundedRegions = regions.takeIf { it.size <= MangaWritableGeometry.MAX_OBSERVATIONS }.orEmpty()
    private val panels: List<SavedOcrBox>
    private val findings = linkedMapOf<Int, SavedOcrFinding>()
    init {
        cancellationCheck()
        val factor = minOf(1f, 256f / maxOf(width, height))
        val tiny = Bitmap.createScaledBitmap(bitmap, maxOf(1, (width * factor).toInt()), maxOf(1, (height * factor).toInt()), true)
        try {
            val pixels = IntArray(tiny.width * tiny.height)
            tiny.getPixels(pixels, 0, tiny.width, 0, 0, tiny.width, tiny.height)
            cancellationCheck()
            panels = ReaderPanelGeometry.detect(tiny.width, tiny.height, pixels, boundedRegions.any { it.recognizerScript == "JAPANESE" }).map { p ->
                SavedOcrBox(floor(p.left * width).toInt(), floor(p.top * height).toInt(), ceil(p.right * width).toInt().coerceAtMost(width), ceil(p.bottom * height).toInt().coerceAtMost(height))
            }
        } finally { if (tiny !== bitmap) tiny.recycle() }
        boundedRegions.take(SavedPageOcrDiagnostics.MAX_FINDINGS).forEachIndexed { ordinal, region ->
            val bounds = box(region.bounds) ?: return@forEachIndexed
            val lines = region.lineBounds.mapNotNull(::box).take(SavedPageOcrDiagnostics.MAX_LINES)
            val script = region.recognizerScript.takeIf { it in SavedPageOcrDiagnostics.SCRIPTS } ?: "UNKNOWN"
            val vertical = isVerticalOcr(OcrReading(region.source, script, region.recognitionConfidence,
                OcrBox(region.bounds.left, region.bounds.top, region.bounds.right, region.bounds.bottom),
                lineBounds = region.lineBounds.map { OcrBox(it.left, it.top, it.right, it.bottom) }))
            val neighbour = smallKanaNeighbour(ordinal, region)
            val panel = panels.indices.filter { index -> val p = panels[index]
                val intersection = maxOf(0, minOf(p.right, bounds.right) - maxOf(p.left, bounds.left)).toLong() *
                    maxOf(0, minOf(p.bottom, bounds.bottom) - maxOf(p.top, bounds.top))
                intersection * 100 >= (bounds.right - bounds.left).toLong() * (bounds.bottom - bounds.top) * 80
            }.singleOrNull()
            findings[ordinal] = SavedOcrFinding(ordinal, bounds, script,
                region.recognitionConfidence.takeIf { it.isFinite() && it > 0f && it <= 1f }, lines,
                SavedOcrOutcome.OBSERVED, proposedPanel = panel,
                geometryKind = if (neighbour != null) SavedOcrGeometryKind.SMALL_KANA_ADJACENT else if (vertical) SavedOcrGeometryKind.VERTICAL_GEOMETRY else SavedOcrGeometryKind.UNCLASSIFIED_TEXT,
                smallKanaNeighbourOrdinal = neighbour)
        }
    }
    fun outcome(ordinal: Int, value: SavedOcrOutcome, nativeIndex: Int? = null, writable: SavedOcrBox? = null, fittedSize: Float? = null) {
        findings[ordinal]?.let { findings[ordinal] = it.copy(outcome = value, nativeLetteringIndex = nativeIndex, writableBounds = writable, fittedSizeAtOne = fittedSize) }
    }
    fun deferFrom(ordinal: Int, reason: SavedOcrOutcome) { findings.keys.filter { it >= ordinal }.forEach { outcome(it, reason) } }
    fun finish(remainingBytes: Int): SavedPageOcrDiagnostics? = if (regions.size > 4096) null else
        SavedOcrDiagnosticsCodec.bounded(SavedPageOcrDiagnostics(sourceSha, width, height, version, regions.size, findings.values.toList(), panels), remainingBytes)
    private fun box(rect: RectF): SavedOcrBox? {
        if (!rect.left.isFinite() || !rect.top.isFinite() || !rect.right.isFinite() || !rect.bottom.isFinite() || rect.width() <= 0f || rect.height() <= 0f) return null
        return SavedOcrBox(floor(rect.left).toInt().coerceIn(0, width), floor(rect.top).toInt().coerceIn(0, height),
            ceil(rect.right).toInt().coerceIn(0, width), ceil(rect.bottom).toInt().coerceIn(0, height)).takeIf { it.valid(width, height) }
    }
    private fun smallKanaNeighbour(ordinal: Int, region: TranslationRegion): Int? {
        val letters = region.source.filter(Char::isLetter)
        if (letters.isEmpty() || letters.length > 12 || !letters.all { it in '\u3040'..'\u30ff' }) return null
        return boundedRegions.indices.filter { it != ordinal }.filter { index -> val other = boundedRegions[index]
            if (!other.source.any { it in '\u4e00'..'\u9fff' } || region.textSize > other.textSize * .6f) return@filter false
            val horizontal = region.bounds.bottom <= other.bounds.top + other.textSize * .2f &&
                other.bounds.top - region.bounds.bottom <= other.textSize * .7f && region.bounds.centerX() in other.bounds.left..other.bounds.right
            val vertical = region.bounds.left >= other.bounds.right - other.textSize * .2f &&
                region.bounds.left - other.bounds.right <= other.textSize * .7f && region.bounds.centerY() in other.bounds.top..other.bounds.bottom
            horizontal || vertical
        }.singleOrNull()
    }
}
