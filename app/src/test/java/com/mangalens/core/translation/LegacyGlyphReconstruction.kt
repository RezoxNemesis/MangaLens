// Test-only snapshot of the pre-optimization MangaLettering kernel.
package com.mangalens.core.translation

import kotlin.math.abs

internal object LegacyGlyphReconstruction {
    fun reconstruct(original: IntArray, masked: BooleanArray, width: Int, height: Int,
        uniformSurface: Boolean, surfaceColor: Int, fallback: Int, search: Int): IntArray {
        val result = original.clone()
        fun cleanIndex(x: Int, y: Int): Int? {
            if (x !in 0 until width || y !in 0 until height) return null
            val index = y * width + x
            return index.takeUnless {
                masked[it] || (uniformSurface && distance(original[it], surfaceColor) > 52)
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!masked[index]) continue

                var left: Int? = null
                var right: Int? = null
                var up: Int? = null
                var down: Int? = null
                var upLeft: Int? = null
                var upRight: Int? = null
                var downLeft: Int? = null
                var downRight: Int? = null
                for (d in 1..search) {
                    if (left == null) left = cleanIndex(x - d, y)
                    if (right == null) right = cleanIndex(x + d, y)
                    if (up == null) up = cleanIndex(x, y - d)
                    if (down == null) down = cleanIndex(x, y + d)
                    if (upLeft == null) upLeft = cleanIndex(x - d, y - d)
                    if (upRight == null) upRight = cleanIndex(x + d, y - d)
                    if (downLeft == null) downLeft = cleanIndex(x - d, y + d)
                    if (downRight == null) downRight = cleanIndex(x + d, y + d)
                    if (listOf(left,right,up,down,upLeft,upRight,downLeft,downRight).count { it != null } >= 6) break
                }

                val horizontal = if (left != null && right != null) {
                    mix(original[left], original[right], .5f)
                } else left?.let { original[it] } ?: right?.let { original[it] }
                val vertical = if (up != null && down != null) {
                    mix(original[up], original[down], .5f)
                } else up?.let { original[it] } ?: down?.let { original[it] }

                val diagonalValues = listOfNotNull(upLeft, upRight, downLeft, downRight).map { original[it] }
                val diagonal = diagonalValues.takeIf { it.isNotEmpty() }?.let { values ->
                    LegacyPixelColor.rgb(
                        values.sumOf { LegacyPixelColor.red(it) } / values.size,
                        values.sumOf { LegacyPixelColor.green(it) } / values.size,
                        values.sumOf { LegacyPixelColor.blue(it) } / values.size
                    )
                }
                result[index] = when {
                    // Preserve clean paper, including faint texture. Reconstruction samples
                    // on uniform surfaces are filtered above so glyphs and balloon outlines
                    // cannot turn the erased zone into a grey rectangle.
                    uniformSurface && original[index] == surfaceColor -> original[index]
                    horizontal != null && vertical != null && diagonal != null ->
                        mix(mix(horizontal, vertical, .5f), diagonal, .34f)
                    horizontal != null && vertical != null ->
                        if (distance(horizontal, vertical) <= 90) mix(horizontal, vertical, .5f)
                        else if (distance(horizontal, fallback) <= distance(vertical, fallback)) horizontal else vertical
                    horizontal != null && diagonal != null -> mix(horizontal, diagonal, .35f)
                    vertical != null && diagonal != null -> mix(vertical, diagonal, .35f)
                    horizontal != null -> horizontal
                    vertical != null -> vertical
                    diagonal != null -> diagonal
                    else -> fallback
                }
            }
        }

        // Soften only reconstructed pixels. Clean art and panel borders stay byte-for-byte untouched.
        val smoothed = result.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (!masked[index]) continue
                val neighbours = intArrayOf(
                    result[index], result[index - 1], result[index + 1],
                    result[index - width], result[index + width]
                )
                smoothed[index] = LegacyPixelColor.rgb(
                    neighbours.sumOf { LegacyPixelColor.red(it) } / neighbours.size,
                    neighbours.sumOf { LegacyPixelColor.green(it) } / neighbours.size,
                    neighbours.sumOf { LegacyPixelColor.blue(it) } / neighbours.size
                )
            }
        }
        return smoothed
    }

    private fun mix(a: Int, b: Int, fraction: Float): Int = LegacyPixelColor.argb(
        (LegacyPixelColor.alpha(a) + (LegacyPixelColor.alpha(b) - LegacyPixelColor.alpha(a)) * fraction).toInt(),
        (LegacyPixelColor.red(a) + (LegacyPixelColor.red(b) - LegacyPixelColor.red(a)) * fraction).toInt(),
        (LegacyPixelColor.green(a) + (LegacyPixelColor.green(b) - LegacyPixelColor.green(a)) * fraction).toInt(),
        (LegacyPixelColor.blue(a) + (LegacyPixelColor.blue(b) - LegacyPixelColor.blue(a)) * fraction).toInt()
    )

    private fun distance(a: Int, b: Int) =
        abs(LegacyPixelColor.red(a) - LegacyPixelColor.red(b)) +
            abs(LegacyPixelColor.green(a) - LegacyPixelColor.green(b)) +
            abs(LegacyPixelColor.blue(a) - LegacyPixelColor.blue(b))

}

private object LegacyPixelColor {
    fun alpha(value: Int) = value ushr 24
    fun red(value: Int) = value shr 16 and 255
    fun green(value: Int) = value shr 8 and 255
    fun blue(value: Int) = value and 255
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun rgb(r: Int, g: Int, b: Int) = argb(255, r, g, b)
}
