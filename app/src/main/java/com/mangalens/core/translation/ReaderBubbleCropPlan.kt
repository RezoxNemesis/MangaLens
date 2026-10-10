package com.mangalens.core.translation

import com.mangalens.core.translation.memory.MemoryRegionBounds
import com.mangalens.core.translation.memory.MemorySourceProof

/** Pure original-bound preview budget. It creates neither an OCR proof nor a decoded bitmap. */
internal class ReaderBubbleCropPlan private constructor(
    val bounds: MemoryRegionBounds,
    val sample: Int,
    val predictedWidth: Int,
    val predictedHeight: Int
) {
    fun acceptsActualDecode(width: Int, height: Int): Boolean =
        width in maxOf(1, (bounds.right - bounds.left) / sample)..predictedWidth &&
            height in maxOf(1, (bounds.bottom - bounds.top) / sample)..predictedHeight &&
            width.toLong() * height <= MAX_PREVIEW_PIXELS && maxOf(width, height) <= MAX_PREVIEW_EDGE

    companion object {
        const val MAX_PREVIEW_PIXELS = 1_000_000L
        const val MAX_PREVIEW_EDGE = 1280
        fun create(source: MemorySourceProof): ReaderBubbleCropPlan {
            source.validate()
            val width = source.bounds.right - source.bounds.left
            val height = source.bounds.bottom - source.bounds.top
            var sample = 1
            fun rounded(value: Int): Int = ((value.toLong() + sample - 1) / sample).toInt()
            while (rounded(width).toLong() * rounded(height) > MAX_PREVIEW_PIXELS ||
                maxOf(rounded(width), rounded(height)) > MAX_PREVIEW_EDGE) sample *= 2
            return ReaderBubbleCropPlan(source.bounds, sample, rounded(width), rounded(height))
        }
    }
}
