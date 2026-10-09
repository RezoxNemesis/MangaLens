package com.mangalens.core.reader

/** Source geometry stays unchanged; only temporary software decode regions are bounded. */
internal object MangaImagePolicy {
    const val MAX_DECODE_SIDE = 2048
    const val MAX_DECODE_PIXELS = 4_194_304L
    private const val MAX_SOURCE_PIXELS = 100_000_000L

    data class Dimensions(val width: Int, val height: Int)
    data class Region(val top: Int, val bottom: Int) {
        val height: Int get() = bottom - top
    }

    /** Android region decoding supports static JPEG/PNG/WebP, not imported GIF. */
    fun supportsRegionFormat(mimeType: String?): Boolean = mimeType in setOf("image/jpeg", "image/png", "image/webp")

    fun dimensions(width: Int, height: Int): Dimensions? =
        if (width > 0 && height > 0 && width.toLong() * height <= MAX_SOURCE_PIXELS) Dimensions(width, height) else null

    /** Stable tile boundaries prevent a new decode on every scroll pixel. */
    fun visibleRegions(size: Dimensions, firstRow: Int, lastRowExclusive: Int): List<Region> {
        if (lastRowExclusive <= firstRow || firstRow >= size.height || lastRowExclusive <= 0) return emptyList()
        val top = firstRow.coerceIn(0, size.height - 1)
        val bottom = lastRowExclusive.coerceIn(top + 1, size.height)
        val first = (top / MAX_DECODE_SIDE - 1).coerceAtLeast(0)
        val last = ((bottom - 1) / MAX_DECODE_SIDE + 1).coerceAtMost((size.height - 1) / MAX_DECODE_SIDE)
        return (first..last).map { index ->
            Region(index * MAX_DECODE_SIDE, minOf(size.height, (index + 1) * MAX_DECODE_SIDE))
        }
    }

    /** Power-of-two region decoding never allocates a native full-strip bitmap. */
    fun sampleSize(width: Int, height: Int, requestedWidth: Int): Int {
        require(dimensions(width, height) != null && requestedWidth > 0)
        val maxWidth = requestedWidth.coerceAtMost(MAX_DECODE_SIDE)
        var sample = 1
        fun sampled(value: Int): Long = (value.toLong() + sample - 1) / sample
        while (sampled(width) > maxWidth || sampled(height) > MAX_DECODE_SIDE ||
            sampled(width) * sampled(height) > MAX_DECODE_PIXELS) sample *= 2
        return sample
    }

    fun coverRegion(size: Dimensions): Region =
        Region(0, minOf(size.height.toLong(), (size.width.toLong() * 100 + 68) / 69).toInt())
}
