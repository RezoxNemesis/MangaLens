package com.mangalens.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class OcrContextualRetryPlan(val crop: OcrBox, val pixels: OcrPixelVariantPlan) {
    fun map(box: OcrBox): OcrBox? = pixels.mapBounds(box)?.let {
        OcrBox(crop.left + it.left, crop.top + it.top, crop.left + it.right, crop.top + it.bottom)
    }
}

/** One bounded scale of uncertain source with geometric context and no observed neighbor text. */
internal fun planContextualOcrRetry(original: OcrReading, imageWidth: Int, imageHeight: Int,
    neighbors: List<OcrBox> = emptyList()): OcrContextualRetryPlan? {
    val bounds = original.bounds
    if (!OcrSourceQuality.needsPixelRetry(original.source) || !bounds.valid || imageWidth <= 0 || imageHeight <= 0 ||
        bounds.left < 0f || bounds.top < 0f || bounds.right > imageWidth || bounds.bottom > imageHeight) return null
    val text = original.textSize.takeIf { it.isFinite() }?.coerceIn(8f, 64f) ?: 16f
    val padding = max(text * 2.5f, max(bounds.width * .35f, bounds.height * .7f)).coerceIn(16f, 128f)
    val snap = min(text * .5f, 24f)
    var left = floor(bounds.left - padding).coerceAtLeast(0f)
    var top = floor(bounds.top - padding).coerceAtLeast(0f)
    var right = ceil(bounds.right + padding).coerceAtMost(imageWidth.toFloat())
    var bottom = ceil(bounds.bottom + padding).coerceAtMost(imageHeight.toFloat())
    if (left <= snap) left = 0f
    if (top <= snap) top = 0f
    if (imageWidth - right <= snap) right = imageWidth.toFloat()
    if (imageHeight - bottom <= snap) bottom = imageHeight.toFloat()
    for (neighbor in neighbors.filter { it.valid }) {
        val verticalOverlap = min(bounds.bottom, neighbor.bottom) - max(bounds.top, neighbor.top)
        val horizontalOverlap = min(bounds.right, neighbor.right) - max(bounds.left, neighbor.left)
        if (verticalOverlap > min(bounds.height, neighbor.height) * .25f) {
            if (neighbor.right <= bounds.left) left = max(left, ceil((neighbor.right + bounds.left) * .5f))
            if (neighbor.left >= bounds.right) right = min(right, floor((neighbor.left + bounds.right) * .5f))
        }
        if (horizontalOverlap > min(bounds.width, neighbor.width) * .25f) {
            if (neighbor.bottom <= bounds.top) top = max(top, ceil((neighbor.bottom + bounds.top) * .5f))
            if (neighbor.top >= bounds.bottom) bottom = min(bottom, floor((neighbor.top + bounds.bottom) * .5f))
        }
    }
    val crop = OcrBox(left, top, right, bottom)
    if (!crop.valid || crop.intersectionArea(bounds) < bounds.area) return null
    val pixels = planOriginalPixelVariant(crop.width.toInt(), crop.height.toInt(), OcrPixelVariantSpec("contextual-scale", 3f)) ?: return null
    if (pixels.transform.a <= 1.1f) return null
    return OcrContextualRetryPlan(crop, pixels)
}

/** Derived input only. The caller owns it until actual native completion, then recycles it. */
internal fun renderContextualOcrRetry(source: Bitmap, plan: OcrPixelVariantPlan): Bitmap {
    require(source.width == plan.sourceWidth && source.height == plan.sourceHeight)
    require(plan.outputWidth <= 1280 && plan.outputHeight <= 1280 && plan.outputWidth.toLong() * plan.outputHeight <= 1_000_000)
    require(plan.spec.clockwiseDegrees == 0f && plan.spec.shearX == 0f && !plan.spec.grayscale && plan.spec.contrast == 1f)
    val output = Bitmap.createBitmap(plan.outputWidth, plan.outputHeight, Bitmap.Config.ARGB_8888)
    try {
        val affine = plan.transform
        val matrix = Matrix().apply { setValues(floatArrayOf(affine.a, affine.b, affine.tx, affine.c, affine.d, affine.ty, 0f, 0f, 1f)) }
        Canvas(output).apply { drawColor(Color.WHITE); drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        return output
    } catch (failure: Throwable) { output.recycle(); throw failure }
}
