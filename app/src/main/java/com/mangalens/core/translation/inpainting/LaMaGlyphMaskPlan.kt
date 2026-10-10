package com.mangalens.core.translation.inpainting

import com.mangalens.core.translation.MangaWritableRect
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Actual original OCR observations, not a semantic classifier or a tap-derived source credential. */
internal data class LaMaObservedText(val source: String, val bounds: MangaWritableRect, val lines: List<MangaWritableRect>)

internal object LaMaGlyphMaskPlan {
    const val MAX_OBSERVATIONS = 4096
    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** A selected reading must bind to the saved native source and its actual sampled original box. */
    fun select(expected: String, selected: MangaWritableRect, observed: List<LaMaObservedText>, checkpoint: () -> Unit = {}): LaMaObservedText? {
        if (observed.isEmpty() || observed.size > MAX_OBSERVATIONS || normalized(expected).isEmpty()) return null
        val expectedReading = normalized(expected)
        val matches = observed.filterIndexed { index, value ->
            if (index % 32 == 0) checkpoint()
            normalized(value.source) == expectedReading && overlap(value.bounds, selected) >= .6
        }
        if (matches.size != 1) return null
        val target = matches.single()
        return target.takeIf { it.lines.isNotEmpty() && it.lines.size <= 256 && it.lines.all { line ->
            line.left >= selected.left && line.top >= selected.top && line.right <= selected.right && line.bottom <= selected.bottom
        } }
    }

    /** Only contrast pixels inside fresh observed lines and saved source bounds are admitted.
     * This is a conservative glyph estimate, not learned segmentation; ambiguous masks decline.
     */
    fun create(width: Int, height: Int, original: IntArray, selected: MangaWritableRect,
        target: LaMaObservedText, observed: List<LaMaObservedText>, nativeNeighbours: List<MangaWritableRect>,
        checkpoint: () -> Unit = {}): BooleanArray? {
        require(width in 1..512 && height in 1..512 && original.size == width * height)
        if (!selected.valid(width, height) || observed.size !in 1..MAX_OBSERVATIONS ||
            nativeNeighbours.size > 256 || target !in observed || target.lines.isEmpty() || target.lines.size > 256) return null
        val neighbours = observed.filter { it !== target }.map { it.bounds } + nativeNeighbours
        if (neighbours.any { !it.valid(width, height) || selected.intersects(it) }) return null
        if (target.lines.any { !it.valid(width, height) || it.left < selected.left || it.top < selected.top ||
                it.right > selected.right || it.bottom > selected.bottom }) return null
        // Bounded summed-area exclusion avoids observations × pixels work in every glyph ring.
        val stride = width + 1; val exclusion = IntArray(stride * (height + 1))
        neighbours.forEachIndexed { index, rect ->
            if (index % 32 == 0) checkpoint()
            exclusion[rect.top * stride + rect.left]++; exclusion[rect.top * stride + rect.right]--
            exclusion[rect.bottom * stride + rect.left]--; exclusion[rect.bottom * stride + rect.right]++
        }
        for (y in 0 until height) {
            if (y % 16 == 0) checkpoint()
            var horizontal = 0
            for (x in 0 until width) {
                horizontal += exclusion[y * stride + x]
                exclusion[y * stride + x] = horizontal + if (y == 0) 0 else exclusion[(y - 1) * stride + x]
            }
        }
        val seed = BooleanArray(original.size)
        for (line in target.lines) {
            checkpoint()
            val histogram = IntArray(256)
            // A ring outside the OCR line estimates local tone without sampling glyphs themselves.
            var count = 0
            for (y in maxOf(0, line.top - 2)..minOf(height - 1, line.bottom + 1)) {
                if (y % 16 == 0) checkpoint()
                for (x in maxOf(0, line.left - 2)..minOf(width - 1, line.right + 1)) {
                    if (x in line.left until line.right && y in line.top until line.bottom) continue
                    if (exclusion[y * stride + x] > 0) continue
                    histogram[luma(original[y * width + x])]++; count++
                }
            }
            if (count < 8) return null
            var cumulative = 0; var background = 0
            while (background < 255 && cumulative + histogram[background] < (count + 1) / 2) { cumulative += histogram[background]; background++ }
            var admitted = 0
            for (y in line.top until line.bottom) {
                if (y % 16 == 0) checkpoint()
                for (x in line.left until line.right) {
                    val color = original[y * width + x]
                    if (color ushr 24 != 255) return null
                    if (abs(luma(color) - background) >= 48) { seed[y * width + x] = true; admitted++ }
                }
            }
            val area = (line.right - line.left) * (line.bottom - line.top)
            if (admitted == 0 || admitted.toLong() * 100 > area.toLong() * 45) return null
        }
        // One sampled-pixel expansion captures antialias fringes, strictly inside source geometry.
        val mask = seed.clone()
        for (y in selected.top until selected.bottom) {
            if (y % 16 == 0) checkpoint()
            for (x in selected.left until selected.right) {
                if (!seed[y * width + x]) continue
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in selected.left until selected.right && ny in selected.top until selected.bottom)
                        mask[ny * width + nx] = true
                }
            }
        }
        checkpoint()
        return mask.takeIf { it.any { bit -> bit } }
    }

    private fun luma(color: Int) = ((color ushr 16 and 255) * 77 + (color ushr 8 and 255) * 150 + (color and 255) * 29) ushr 8
    private fun overlap(a: MangaWritableRect, b: MangaWritableRect): Double {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left); val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0.0
        val area = (a.right.toLong() - a.left) * (a.bottom.toLong() - a.top)
        val other = (b.right.toLong() - b.left) * (b.bottom.toLong() - b.top)
        return if (area <= 0 || other <= 0) 0.0 else w.toDouble() * h / maxOf(area, other)
    }
}
