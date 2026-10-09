package com.mangalens.core.translation

import kotlin.math.abs

/** Pixel-only glyph reconstruction; source pixels and the expanded mask remain immutable. */
internal object MangaGlyphReconstruction {
    fun reconstruct(
        original: IntArray,
        masked: BooleanArray,
        width: Int,
        height: Int,
        uniformSurface: Boolean,
        surfaceColor: Int,
        fallback: Int,
        search: Int
    ): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == original.size.toLong() && masked.size == original.size)
        require(search > 0)
        val result = original.clone()

        // An index sentinel avoids nullable boxed candidates in the inner search loop.
        fun cleanIndex(x: Int, y: Int): Int {
            if (x < 0 || x >= width || y < 0 || y >= height) return -1
            val index = y * width + x
            return if (masked[index] || (uniformSurface && distance(original[index], surfaceColor) > 52)) -1 else index
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!masked[index]) continue
                // The previous kernel returned this exact pixel after its entire search.
                // It remains masked for the identical smoothing pass below.
                if (uniformSurface && original[index] == surfaceColor) continue

                var left = -1; var right = -1; var up = -1; var down = -1
                var upLeft = -1; var upRight = -1; var downLeft = -1; var downRight = -1
                var found = 0
                for (d in 1..search) {
                    if (left < 0) { left = cleanIndex(x - d, y); if (left >= 0) found++ }
                    if (right < 0) { right = cleanIndex(x + d, y); if (right >= 0) found++ }
                    if (up < 0) { up = cleanIndex(x, y - d); if (up >= 0) found++ }
                    if (down < 0) { down = cleanIndex(x, y + d); if (down >= 0) found++ }
                    if (upLeft < 0) { upLeft = cleanIndex(x - d, y - d); if (upLeft >= 0) found++ }
                    if (upRight < 0) { upRight = cleanIndex(x + d, y - d); if (upRight >= 0) found++ }
                    if (downLeft < 0) { downLeft = cleanIndex(x - d, y + d); if (downLeft >= 0) found++ }
                    if (downRight < 0) { downRight = cleanIndex(x + d, y + d); if (downRight >= 0) found++ }
                    if (found >= 6) break
                }

                val hasHorizontal = left >= 0 || right >= 0
                val horizontal = when {
                    left >= 0 && right >= 0 -> mix(original[left], original[right], .5f)
                    left >= 0 -> original[left]
                    right >= 0 -> original[right]
                    else -> fallback
                }
                val hasVertical = up >= 0 || down >= 0
                val vertical = when {
                    up >= 0 && down >= 0 -> mix(original[up], original[down], .5f)
                    up >= 0 -> original[up]
                    down >= 0 -> original[down]
                    else -> fallback
                }

                var diagonalCount = 0; var diagonalRed = 0; var diagonalGreen = 0; var diagonalBlue = 0
                if (upLeft >= 0) {
                    val color = original[upLeft]; diagonalCount++
                    diagonalRed += red(color); diagonalGreen += green(color); diagonalBlue += blue(color)
                }
                if (upRight >= 0) {
                    val color = original[upRight]; diagonalCount++
                    diagonalRed += red(color); diagonalGreen += green(color); diagonalBlue += blue(color)
                }
                if (downLeft >= 0) {
                    val color = original[downLeft]; diagonalCount++
                    diagonalRed += red(color); diagonalGreen += green(color); diagonalBlue += blue(color)
                }
                if (downRight >= 0) {
                    val color = original[downRight]; diagonalCount++
                    diagonalRed += red(color); diagonalGreen += green(color); diagonalBlue += blue(color)
                }
                val hasDiagonal = diagonalCount > 0
                val diagonal = if (hasDiagonal) rgb(diagonalRed / diagonalCount, diagonalGreen / diagonalCount, diagonalBlue / diagonalCount) else fallback
                result[index] = when {
                    hasHorizontal && hasVertical && hasDiagonal -> mix(mix(horizontal, vertical, .5f), diagonal, .34f)
                    hasHorizontal && hasVertical ->
                        if (distance(horizontal, vertical) <= 90) mix(horizontal, vertical, .5f)
                        else if (distance(horizontal, fallback) <= distance(vertical, fallback)) horizontal else vertical
                    hasHorizontal && hasDiagonal -> mix(horizontal, diagonal, .35f)
                    hasVertical && hasDiagonal -> mix(vertical, diagonal, .35f)
                    hasHorizontal -> horizontal
                    hasVertical -> vertical
                    hasDiagonal -> diagonal
                    else -> fallback
                }
            }
        }

        // Read only the completed reconstruction pass; smoothing must not feed itself.
        val smoothed = result.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (!masked[index]) continue
                val center = result[index]; val left = result[index - 1]; val right = result[index + 1]
                val up = result[index - width]; val down = result[index + width]
                smoothed[index] = rgb(
                    (red(center) + red(left) + red(right) + red(up) + red(down)) / 5,
                    (green(center) + green(left) + green(right) + green(up) + green(down)) / 5,
                    (blue(center) + blue(left) + blue(right) + blue(up) + blue(down)) / 5
                )
            }
        }
        return smoothed
    }

    private fun red(color: Int) = color shr 16 and 255
    private fun green(color: Int) = color shr 8 and 255
    private fun blue(color: Int) = color and 255
    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
    private fun distance(a: Int, b: Int) = abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))
    private fun mix(a: Int, b: Int, fraction: Float): Int =
        (((a ushr 24) + ((b ushr 24) - (a ushr 24)) * fraction).toInt() shl 24) or
            ((red(a) + (red(b) - red(a)) * fraction).toInt() shl 16) or
            ((green(a) + (green(b) - green(a)) * fraction).toInt() shl 8) or
            (blue(a) + (blue(b) - blue(a)) * fraction).toInt()
}
