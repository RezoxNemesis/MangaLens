package com.mangalens.ui.reader

import kotlin.math.min

/** Display-only panel fitting. No original-source crop or bubble credential is created. */
internal data class ReaderGuidedTransform(val scale: Float, val x: Float, val y: Float)
internal object ReaderGuidedViewPolicy {
    val WHOLE_PAGE = ReaderPanelRect(0f, 0f, 1f, 1f)
    fun valid(rect: ReaderPanelRect) = listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() } &&
        rect.left >= 0f && rect.top >= 0f && rect.right <= 1f && rect.bottom <= 1f && rect.right > rect.left && rect.bottom > rect.top
    fun transform(viewportWidth: Float, viewportHeight: Float, imageRatio: Float, rect: ReaderPanelRect): ReaderGuidedTransform {
        if (!viewportWidth.isFinite() || !viewportHeight.isFinite() || !imageRatio.isFinite() ||
            viewportWidth <= 0f || viewportHeight <= 0f || imageRatio <= 0f || !valid(rect)) return ReaderGuidedTransform(1f, 0f, 0f)
        val width = min(viewportWidth, viewportHeight * imageRatio)
        val height = width / imageRatio
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return ReaderGuidedTransform(1f, 0f, 0f)
        val factor = min(viewportWidth / (width * (rect.right - rect.left)), viewportHeight / (height * (rect.bottom - rect.top)))
            .coerceIn(1f, 16f)
        val x = (.5f - (rect.left + rect.right) * .5f) * width * factor
        val y = (.5f - (rect.top + rect.bottom) * .5f) * height * factor
        return if (factor.isFinite() && x.isFinite() && y.isFinite()) ReaderGuidedTransform(factor, x, y) else ReaderGuidedTransform(1f, 0f, 0f)
    }
    fun boundedIndex(index: Int, count: Int) = if (count <= 0) 0 else index.coerceIn(0, count - 1)
    /** At the end of estimates, explicit Next/Previous continues to the neighbouring logical page. */
    fun advance(index: Int, count: Int, delta: Int): ReaderGuidedStep {
        require(delta == -1 || delta == 1)
        if (count <= 0) return ReaderGuidedStep(0, delta)
        val target = boundedIndex(index, count) + delta
        return if (target in 0 until count) ReaderGuidedStep(target, 0) else ReaderGuidedStep(boundedIndex(index, count), delta)
    }
}
internal data class ReaderGuidedStep(val panelIndex: Int, val pageDelta: Int)
