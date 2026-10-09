package com.mangalens

internal data class ReaderMarkerBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)

internal object ReaderMarkerPixels {
    fun bounds(pixels: IntArray, width: Int, height: Int, color: Int): ReaderMarkerBounds? {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        fun channel(value: Int, shift: Int) = (value ushr shift) and 255
        val shifts = intArrayOf(16, 8, 0)
        val target = shifts.map { channel(color, it) }
        require(target.all { it == 0 || it == 255 } && 0 in target && 255 in target)
        val columns = DoubleArray(width)
        val rows = DoubleArray(height)

        // Fixture markers are opaque primary colors on white. Filtering mixes white
        // into their edge; the near-exact RGB mask measured only the saturated interior.
        // The 50% coverage contour measures the actual rectangle boundary instead.
        pixels.forEachIndexed { index, pixel ->
            var coverage = 1.0
            for (position in shifts.indices) {
                val value = channel(pixel, shifts[position])
                if (target[position] == 255) {
                    if (value <= 240) { coverage = 0.0; break } // Black lettering is not colored artwork.
                } else coverage = minOf(coverage, (255 - value) / 255.0)
            }
            val x = index % width
            val y = index / width
            columns[x] = maxOf(columns[x], coverage)
            rows[y] = maxOf(rows[y], coverage)
        }
        val horizontal = edges(columns) ?: return null
        val vertical = edges(rows) ?: return null
        return ReaderMarkerBounds(horizontal.first, vertical.first, horizontal.second, vertical.second)
    }

    private fun edges(coverage: DoubleArray): Pair<Double, Double>? {
        val first = coverage.indexOfFirst { it >= .5 }
        val last = coverage.indexOfLast { it >= .5 }
        // A clipped marker has no measurable complete edge and cannot prove geometry.
        if (first <= 0 || last < first || last >= coverage.lastIndex) return null
        val left = first - .5 + (.5 - coverage[first - 1]) / (coverage[first] - coverage[first - 1])
        val right = last + .5 + (coverage[last] - .5) / (coverage[last] - coverage[last + 1])
        return left to right
    }
}
