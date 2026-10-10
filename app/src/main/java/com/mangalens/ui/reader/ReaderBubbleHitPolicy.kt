package com.mangalens.ui.reader

import com.mangalens.core.translation.SavedMangaLettering

/** Selection names the original saved entry; a personal render must not replace native evidence. */
internal data class ReaderBubbleTap(val pageIndex: Int, val letteringIndex: Int, val expectedNative: SavedMangaLettering)
internal data class ReaderBubbleHitTarget(val tap: ReaderBubbleTap, val imageWidth: Int, val imageHeight: Int)

/** Canvas typesetting scales uniformly from its actual sampled image width. No source crop is derived here. */
internal object ReaderBubbleHitPolicy {
    fun hit(targetsInDrawOrder: List<ReaderBubbleHitTarget>, canvasWidth: Float, canvasHeight: Float,
        x: Float, y: Float): ReaderBubbleTap? {
        if (!canvasWidth.isFinite() || !canvasHeight.isFinite() || canvasWidth <= 0f || canvasHeight <= 0f ||
            !x.isFinite() || !y.isFinite() || x < 0f || y < 0f || x >= canvasWidth || y >= canvasHeight) return null
        // The last rendered region owns an overlap, matching the real Canvas draw order.
        return targetsInDrawOrder.asReversed().firstOrNull { target ->
            val native = target.tap.expectedNative
            if (target.tap.pageIndex < 0 || target.tap.letteringIndex < 0 || target.imageWidth <= 0 || target.imageHeight <= 0 ||
                !native.size.isFinite() || native.size <= 0f || native.left < 0 || native.top < 0 ||
                native.right <= native.left || native.bottom <= native.top ||
                native.right > target.imageWidth || native.bottom > target.imageHeight) false
            else {
                val ratio = canvasWidth / target.imageWidth
                val localX = x / ratio; val localY = y / ratio
                localX >= native.left && localX < native.right && localY >= native.top && localY < native.bottom
            }
        }?.tap
    }
}
