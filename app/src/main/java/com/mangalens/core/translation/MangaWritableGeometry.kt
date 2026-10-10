package com.mangalens.core.translation

/** Integer sampled-original geometry. This carries no source/publication authority. */
internal data class MangaWritableRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun valid(width: Int, height: Int): Boolean = left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height
    fun intersects(other: MangaWritableRect): Boolean = left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

/** Neighbour exclusion is geometric, not a bubble or SFX classifier. */
internal object MangaWritableGeometry {
    const val MAX_OBSERVATIONS = 4096
    const val MAX_PROTECTED_RECTS = MAX_OBSERVATIONS + ChapterTranslationStore.MAX_LETTERING
    fun limit(source: MangaWritableRect, neighbours: List<MangaWritableRect>, width: Int, height: Int): MangaWritableRect? {
        require(width > 0 && height > 0 && neighbours.size <= MAX_PROTECTED_RECTS)
        if (!source.valid(width, height)) return null
        var left = 0; var top = 0; var right = width; var bottom = height
        for (other in neighbours) {
            if (!other.valid(width, height) || source.intersects(other)) return null
            // Every separating plane lies outside the observed source box. Long arithmetic
            // also handles large synthetic dimensions without overflowing midpoint sums.
            if (other.right <= source.left) left = maxOf(left, ((other.right.toLong() + source.left) / 2).toInt())
            if (other.left >= source.right) right = minOf(right, ((other.left.toLong() + source.right) / 2).toInt())
            if (other.bottom <= source.top) top = maxOf(top, ((other.bottom.toLong() + source.top) / 2).toInt())
            if (other.top >= source.bottom) bottom = minOf(bottom, ((other.top.toLong() + source.bottom) / 2).toInt())
        }
        return MangaWritableRect(left, top, right, bottom).takeIf { it.valid(width, height) &&
            it.left <= source.left && it.top <= source.top && it.right >= source.right && it.bottom >= source.bottom }
    }
}
