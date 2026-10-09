package com.mangalens.engine

/** Diagnostic hypotheses; no vocabulary, language model or expected fixture phrase is encoded. */
internal data class OcrPixelVariantSpec(
    val label: String, val maxScale: Float,
    val clockwiseDegrees: Float = 0f, val shearX: Float = 0f,
    val grayscale: Boolean = false, val contrast: Float = 1f
)

internal val originalPixelDiagnosticVariants = listOf(
    OcrPixelVariantSpec("original", 1f),
    OcrPixelVariantSpec("scale3", 3f),
    OcrPixelVariantSpec("gray-contrast", 3f, grayscale = true, contrast = 1.6f),
    OcrPixelVariantSpec("deskew-minus3", 3f, clockwiseDegrees = -3f),
    OcrPixelVariantSpec("deskew-plus3", 3f, clockwiseDegrees = 3f),
    OcrPixelVariantSpec("deslant-plus0.18", 2.5f, shearX = .18f)
)

internal data class OcrPixelPoint(val x: Float, val y: Float)

internal data class OcrPixelAffine(
    val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float
) {
    fun map(x: Float, y: Float) = OcrPixelPoint(a * x + b * y + tx, c * x + d * y + ty)
    fun inverse(x: Float, y: Float): OcrPixelPoint {
        val determinant = a * d - b * c
        val shiftedX = x - tx; val shiftedY = y - ty
        return OcrPixelPoint((d * shiftedX - b * shiftedY) / determinant, (-c * shiftedX + a * shiftedY) / determinant)
    }
}

internal data class OcrPixelVariantPlan(
    val spec: OcrPixelVariantSpec, val sourceWidth: Int, val sourceHeight: Int,
    val outputWidth: Int, val outputHeight: Int, val transform: OcrPixelAffine
) {
    /** Axis-aligned OCR boxes enclose the inverse-mapped corners; outside margins are clipped. */
    fun mapBounds(box: OcrBox): OcrBox? {
        if (!box.valid) return null
        val corners = listOf(transform.inverse(box.left, box.top), transform.inverse(box.right, box.top),
            transform.inverse(box.left, box.bottom), transform.inverse(box.right, box.bottom))
        return OcrBox(corners.minOf { it.x }.coerceIn(0f, sourceWidth.toFloat()),
            corners.minOf { it.y }.coerceIn(0f, sourceHeight.toFloat()),
            corners.maxOf { it.x }.coerceIn(0f, sourceWidth.toFloat()),
            corners.maxOf { it.y }.coerceIn(0f, sourceHeight.toFloat())).takeIf { it.valid }
    }
}

internal fun planOriginalPixelVariant(
    width: Int, height: Int, spec: OcrPixelVariantSpec,
    maxPixels: Int = 1_000_000, maxDimension: Int = 1280
): OcrPixelVariantPlan? {
    if (width <= 0 || height <= 0 || maxPixels <= 0 || maxDimension <= 0 ||
        !spec.maxScale.isFinite() || spec.maxScale !in 1f..3f ||
        !spec.clockwiseDegrees.isFinite() || kotlin.math.abs(spec.clockwiseDegrees) > 5f ||
        !spec.shearX.isFinite() || kotlin.math.abs(spec.shearX) > .25f ||
        !spec.contrast.isFinite() || spec.contrast !in 1f..2.5f) return null
    val radians = Math.toRadians(spec.clockwiseDegrees.toDouble())
    val cosine = kotlin.math.cos(radians); val sine = kotlin.math.sin(radians)
    val a = cosine; val b = cosine * spec.shearX - sine
    val c = sine; val d = sine * spec.shearX + cosine
    val corners = listOf(0.0 to 0.0, width.toDouble() to 0.0, 0.0 to height.toDouble(), width.toDouble() to height.toDouble())
        .map { (x, y) -> (a * x + b * y) to (c * x + d * y) }
    val left = corners.minOf { it.first }; val top = corners.minOf { it.second }
    val extentWidth = corners.maxOf { it.first } - left
    val extentHeight = corners.maxOf { it.second } - top
    fun dimensions(scale: Double) = kotlin.math.ceil(extentWidth * scale).toInt().coerceAtLeast(1) to
        kotlin.math.ceil(extentHeight * scale).toInt().coerceAtLeast(1)
    fun fits(scale: Double): Boolean {
        val (outputWidth, outputHeight) = dimensions(scale)
        return outputWidth <= maxDimension && outputHeight <= maxDimension &&
            outputWidth.toLong() * outputHeight <= maxPixels
    }
    var scale = minOf(spec.maxScale.toDouble(), maxDimension / extentWidth, maxDimension / extentHeight,
        kotlin.math.sqrt(maxPixels / (extentWidth * extentHeight)))
    // Integer rounding can exceed the cap even when continuous geometry fits.
    // A fixed search bounds planning work and retains all source-image corners.
    if (!fits(scale)) {
        var low = 0.0; var high = scale
        repeat(32) {
            val middle = (low + high) * .5
            if (fits(middle)) low = middle else high = middle
        }
        scale = low
    }
    val (outputWidth, outputHeight) = dimensions(scale)
    return OcrPixelVariantPlan(spec, width, height, outputWidth, outputHeight,
        OcrPixelAffine((a * scale).toFloat(), (b * scale).toFloat(), (c * scale).toFloat(),
            (d * scale).toFloat(), (-left * scale).toFloat(), (-top * scale).toFloat()))
}
