package com.mangalens.core.translation

import kotlin.math.abs

internal class MangaReconstructionDeferredException : IllegalStateException("The original artwork could not be reconstructed safely. Retry this region or keep the original.")

/** Bounded interpolation of clean neighbours; never a trained artwork reconstruction model. */
internal object MangaGlyphReconstructionV2 {
    fun reconstruct(original: IntArray, masked: BooleanArray, width: Int, height: Int,
        uniformSurface: Boolean, surfaceColor: Int, fallback: Int, search: Int, cancellationCheck: () -> Unit = {}): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == original.size.toLong() && masked.size == original.size)
        require(search in 1..48)
        val result = original.clone()
        fun clean(x: Int, y: Int): Int {
            if (x !in 0 until width || y !in 0 until height) return -1
            val i = y * width + x
            return if (masked[i] || (uniformSurface && distance(original[i], surfaceColor) > 52)) -1 else i
        }
        // Scratch arrays are reused for every pixel; no boxed per-pixel candidate allocations.
        val dx = intArrayOf(-1, 1, 0, 0, -1, 1, -1, 1)
        val dy = intArrayOf(0, 0, -1, 1, -1, 1, 1, -1)
        val indices = IntArray(8); val distances = IntArray(8)
        for (y in 0 until height) {
            if (y % 16 == 0) cancellationCheck()
            for (x in 0 until width) {
                val index = y * width + x
                if (!masked[index]) continue
                indices.fill(-1); distances.fill(0)
                for (axis in 0..7) for (d in 1..search) {
                    val found = clean(x + dx[axis] * d, y + dy[axis] * d)
                    if (found >= 0) { indices[axis] = found; distances[axis] = d; break }
                }
                var selected = -1; var selectedScore = Int.MAX_VALUE
                // Prefer a complete low-discontinuity axis. Distance weighting preserves a
                // sloping local surface instead of filling a glyph band with equal midpoints.
                for (axis in 0..3) {
                    val a = axis * 2; val b = a + 1
                    if (indices[a] < 0 || indices[b] < 0) continue
                    val score = distance(original[indices[a]], original[indices[b]]) + distances[a] + distances[b]
                    if (score < selectedScore) { selected = a; selectedScore = score }
                }
                if (selected >= 0) {
                    val a = selected; val b = a + 1
                    result[index] = interpolate(original[indices[a]], original[indices[b]], distances[a], distances[b])
                } else {
                    var nearest = -1; var cost = Int.MAX_VALUE
                    for (axis in 0..7) if (indices[axis] >= 0) {
                        val value = distances[axis] + distance(original[indices[axis]], fallback) / 4
                        if (value < cost) { nearest = indices[axis]; cost = value }
                    }
                    if (nearest < 0 && !uniformSurface) throw MangaReconstructionDeferredException()
                    result[index] = if (nearest < 0) fallback else original[nearest]
                }
            }
        }
        // No indiscriminate smoothing across a drawing edge or into untouched artwork.
        return result
    }
    private fun channel(c: Int, shift: Int) = c ushr shift and 255
    private fun distance(a: Int, b: Int) = abs(channel(a, 16) - channel(b, 16)) + abs(channel(a, 8) - channel(b, 8)) + abs(channel(a, 0) - channel(b, 0))
    private fun interpolate(a: Int, b: Int, distanceA: Int, distanceB: Int): Int {
        val total = distanceA + distanceB
        fun value(shift: Int) = (channel(a, shift) * distanceB + channel(b, shift) * distanceA + total / 2) / total
        return (value(24) shl 24) or (value(16) shl 16) or (value(8) shl 8) or value(0)
    }
}
