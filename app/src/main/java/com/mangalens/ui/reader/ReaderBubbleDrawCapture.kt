package com.mangalens.ui.reader

/** Actual positioned Canvas metadata. These sampled display pixels never confer source-crop proof. */
internal data class ReaderBubbleCanvasFrame(val left: Float, val top: Float, val width: Float, val height: Float)

/** Updated after drawContent; selection retains the original native list used for that exact paint. */
internal class ReaderBubbleDrawCapture(private val targets: List<ReaderBubbleHitTarget>) {
    private data class Painted(val width: Float, val height: Float, val visible: ReaderDisplayVisibleArea)
    @Volatile private var painted: Painted? = null

    fun painted(width: Float, height: Float) {
        painted(width, height, ReaderDisplayVisibleArea(0f, 0f, width, height))
    }
    fun painted(width: Float, height: Float, visible: ReaderDisplayVisibleArea) {
        painted = if (width.isFinite() && height.isFinite() && width > 0f && height > 0f &&
            listOf(visible.left, visible.top, visible.right, visible.bottom).all { it.isFinite() } &&
            visible.left >= 0f && visible.top >= 0f && visible.right <= width && visible.bottom <= height &&
            visible.right >= visible.left && visible.bottom > visible.top) Painted(width, height, visible) else null
    }

    fun hit(frame: ReaderBubbleCanvasFrame?, x: Float, y: Float): ReaderBubbleTap? = hit(frame, x, y, 0f)
    fun hit(frame: ReaderBubbleCanvasFrame?, x: Float, y: Float, marginCrop: Float): ReaderBubbleTap? {
        val draw = painted ?: return null
        val positioned = frame ?: return null
        if (!positioned.left.isFinite() || !positioned.top.isFinite() || positioned.width != draw.width || positioned.height != draw.height)
            return null
        val point = ReaderDisplayGeometry.toCanvas(draw.width, draw.height, x - positioned.left, y - positioned.top, marginCrop) ?: return null
        if (!draw.visible.contains(point.x, point.y)) return null
        return ReaderBubbleHitPolicy.hit(targets, draw.width, draw.height, point.x, point.y)
    }
}
