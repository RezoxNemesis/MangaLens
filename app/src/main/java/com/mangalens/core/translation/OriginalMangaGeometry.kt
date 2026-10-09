package com.mangalens.core.translation

/** Persisted OCR bounds in the original file's coordinates; renderer coordinates stay separate. */
data class SavedOriginalSourceBounds(val version: Int = 1, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    internal fun validate(width: Int, height: Int) {
        require(version == 1 && width in 1..100_000 && height in 1..100_000 && width.toLong() * height <= 100_000_000L)
        require(left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height)
    }
}

internal object OriginalMangaGeometry {
    fun fromSampled(left: Int, top: Int, right: Int, bottom: Int, sampledWidth: Int, sampledHeight: Int,
        originalWidth: Int, originalHeight: Int): SavedOriginalSourceBounds {
        require(sampledWidth in 1..originalWidth && sampledHeight in 1..originalHeight)
        require(left >= 0 && top >= 0 && right > left && bottom > top && right <= sampledWidth && bottom <= sampledHeight)
        fun floor(value: Int, original: Int, sampled: Int) = (value.toLong() * original / sampled).toInt()
        fun ceil(value: Int, original: Int, sampled: Int) = ((value.toLong() * original + sampled - 1) / sampled).toInt()
        return SavedOriginalSourceBounds(left = floor(left, originalWidth, sampledWidth), top = floor(top, originalHeight, sampledHeight),
            right = ceil(right, originalWidth, sampledWidth), bottom = ceil(bottom, originalHeight, sampledHeight)).also {
            it.validate(originalWidth, originalHeight)
        }
    }
}
