package com.mangalens.engine

import android.graphics.Bitmap

/** Crop ownership remains with the provider until consume has passed real native completion. */
internal interface OcrOriginalRegionSource {
    suspend fun <T> withCrop(request: OcrSourceResolutionRequest, consume: suspend (OcrOriginalRegionCrop) -> T): T?
}

internal data class OcrOriginalRegionCrop(val bitmap: Bitmap, val plan: OcrSourceResolutionPlan,
    val decodedCropWidth: Int, val decodedCropHeight: Int, val pixels: OcrPixelVariantPlan) {
    fun mapToPage(box: OcrBox): OcrBox? = pixels.mapBounds(box)?.let {
        plan.mapToPage(it, decodedCropWidth, decodedCropHeight)
    }

    fun textSizeToPage(size: Float): Float = plan.textSizeToPage(size / pixels.transform.a, decodedCropWidth, decodedCropHeight)
}
