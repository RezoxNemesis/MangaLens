package com.mangalens.ui.reader

/** Sampled display coordinates only. This never creates an original crop or source receipt. */
internal data class ReaderDisplayVisibleArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}
internal data class ReaderDisplayPoint(val x: Float, val y: Float)
internal object ReaderDisplayGeometry {
    fun scale(crop: Float): Float {
        require(crop.isFinite() && crop in 0f..ReaderDisplayOptions.MAX_MARGIN_CROP)
        return 1f / (1f - crop * 2f)
    }
    fun toCanvas(width: Float, height: Float, x: Float, y: Float, crop: Float): ReaderDisplayPoint? {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f ||
            !x.isFinite() || !y.isFinite() || x < 0f || y < 0f || x >= width || y >= height) return null
        val factor = scale(crop)
        return ReaderDisplayPoint((x - width / 2f) / factor + width / 2f, (y - height / 2f) / factor + height / 2f)
    }
    fun visible(width: Float, height: Float, crop: Float, split: Float = 1f): ReaderDisplayVisibleArea {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0 && split.isFinite() && split in 0f..1f)
        scale(crop)
        return ReaderDisplayVisibleArea(width * crop, height * crop,
            width * (crop + (1f - 2f * crop) * split), height * (1f - crop))
    }
}
