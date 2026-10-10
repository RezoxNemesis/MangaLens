package com.mangalens.core.translation.inpainting

import java.security.MessageDigest

internal class LaMaTensorInput internal constructor(val width: Int, val height: Int,
    internal val original: IntArray, internal val selected: BooleanArray,
    internal val image: FloatArray, internal val mask: FloatArray, val maskSha256: String)

/** Fixed square is padding, not a stretched page. Every unmasked original pixel stays byte-identical. */
internal object LaMaTensorPlan {
    fun prepare(width: Int, height: Int, original: IntArray, selected: BooleanArray,
        checkpoint: () -> Unit = {}): LaMaTensorInput {
        require(width in 1..LaMaReconstructionPin.EDGE && height in 1..LaMaReconstructionPin.EDGE)
        require(original.size == width * height && selected.size == original.size && selected.any { it })
        require(original.all { it ushr 24 == 255 }) { "Transparent artwork is not supported by this experimental RGB pack." }
        val plane = LaMaReconstructionPin.PIXELS
        val image = FloatArray(LaMaReconstructionPin.FLOATS)
        val mask = FloatArray(plane)
        val digest = MessageDigest.getInstance("SHA-256")
        for (y in 0 until LaMaReconstructionPin.EDGE) {
            if (y % 16 == 0) checkpoint()
            for (x in 0 until LaMaReconstructionPin.EDGE) {
                val color = original[reflected(y, height) * width + reflected(x, width)]
                val i = y * LaMaReconstructionPin.EDGE + x
                image[i] = (color ushr 16 and 255) / 255f
                image[plane + i] = (color ushr 8 and 255) / 255f
                image[2 * plane + i] = (color and 255) / 255f
                val admitted = x < width && y < height && selected[y * width + x]
                mask[i] = if (admitted) 1f else 0f
                digest.update(if (admitted) 1.toByte() else 0.toByte())
            }
        }
        checkpoint()
        return LaMaTensorInput(width, height, original.clone(), selected.clone(), image, mask,
            digest.digest().joinToString("") { "%02x".format(it) })
    }

    fun composite(input: LaMaTensorInput, output: FloatArray, checkpoint: () -> Unit = {}): IntArray {
        require(output.size == LaMaReconstructionPin.FLOATS)
        // Validate the whole returned plane, including padding; malformed native output fails closed.
        output.forEachIndexed { index, value ->
            if (index % 8192 == 0) checkpoint()
            require(value.isFinite() && value in 0f..255f) { "The repair pack returned invalid pixels." }
        }
        val result = input.original.clone(); val plane = LaMaReconstructionPin.PIXELS
        for (y in 0 until input.height) {
            if (y % 16 == 0) checkpoint()
            for (x in 0 until input.width) {
                val target = y * input.width + x
                if (!input.selected[target]) continue
                val at = y * LaMaReconstructionPin.EDGE + x
                fun channel(offset: Int) = (output[offset + at] + .5f).toInt().coerceIn(0, 255)
                result[target] = (input.original[target] and -0x1000000) or
                    (channel(0) shl 16) or (channel(plane) shl 8) or channel(2 * plane)
            }
        }
        checkpoint(); return result
    }

    private fun reflected(index: Int, length: Int): Int {
        if (length == 1) return 0
        val period = length * 2 - 2; val at = index % period
        return if (at < length) at else period - at
    }
}
