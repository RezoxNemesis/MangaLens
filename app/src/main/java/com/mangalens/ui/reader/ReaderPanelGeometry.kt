package com.mangalens.ui.reader

/** Normalized original-image bounds, never a translated-surface or bubble index. */
internal data class ReaderPanelRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Conservative gutter proposals for the explicitly selected guided Reader mode.
 * This is image geometry, not a learned panel detector. Pages without useful pale
 * gutters retain one whole-page proposal; callers can always leave guided mode.
 */
internal object ReaderPanelGeometry {
    const val MAX_SIDE = 256
    const val MAX_PANELS = 24
    private const val MAX_DEPTH = 5
    private const val MIN_SIDE = 4
    private data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }
    private data class Gap(val start: Int, val end: Int)

    fun detect(width: Int, height: Int, argb: IntArray, rightToLeft: Boolean = false): List<ReaderPanelRect> {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE && argb.size == width * height)
        // Integral counts bound both line and content checks, independently of recursion.
        val stride = width + 1
        val dark = IntArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0
            for (x in 0 until width) {
                if (!pale(argb[y * width + x])) row++
                dark[(y + 1) * stride + x + 1] = dark[y * stride + x + 1] + row
            }
        }
        fun count(a: Area): Int = dark[a.bottom * stride + a.right] - dark[a.top * stride + a.right] -
            dark[a.bottom * stride + a.left] + dark[a.top * stride + a.left]
        fun containsArtwork(a: Area): Boolean = a.width >= MIN_SIDE && a.height >= MIN_SIDE &&
            count(a).toLong() * 100 >= a.width.toLong() * a.height * 2
        fun gap(a: Area, horizontal: Boolean): Gap? {
            val begin = if (horizontal) a.top else a.left
            val end = if (horizontal) a.bottom else a.right
            val span = if (horizontal) a.width else a.height
            var run = -1
            val candidates = ArrayList<Gap>()
            // Exclude outside margins: a cut needs real artwork on both sides.
            for (coordinate in begin + MIN_SIDE until end - MIN_SIDE) {
                val line = if (horizontal) Area(a.left, coordinate, a.right, coordinate + 1)
                    else Area(coordinate, a.top, coordinate + 1, a.bottom)
                val blank = count(line).toLong() * 100 <= span.toLong() * 3
                if (blank && run < 0) run = coordinate
                if ((!blank || coordinate == end - MIN_SIDE - 1) && run >= 0) {
                    val stop = if (blank) coordinate + 1 else coordinate
                    if (stop - run >= 2) {
                        val before = if (horizontal) Area(a.left, a.top, a.right, run)
                            else Area(a.left, a.top, run, a.bottom)
                        val after = if (horizontal) Area(a.left, stop, a.right, a.bottom)
                            else Area(stop, a.top, a.right, a.bottom)
                        if (containsArtwork(before) && containsArtwork(after)) candidates += Gap(run, stop)
                    }
                    run = -1
                }
            }
            return candidates.maxWithOrNull(compareBy<Gap> { it.end - it.start }
                .thenBy { minOf(it.start - begin, end - it.end) })
        }
        val areas = ArrayList<Area>()
        fun split(a: Area, depth: Int, allowance: Int) {
            if (depth >= MAX_DEPTH || allowance <= 1) { areas += a; return }
            // Row grouping precedes columns, preserving ordinary comic reading order.
            val horizontal = gap(a, true)
            val vertical = if (horizontal == null) gap(a, false) else null
            if (horizontal == null && vertical == null) { areas += a; return }
            val cut = horizontal ?: checkNotNull(vertical)
            // Retain a little gutter around each panel so its frame is not clipped.
            val first: Area
            val second: Area
            if (horizontal != null) {
                first = Area(a.left, a.top, a.right, cut.start + 1)
                second = Area(a.left, cut.end - 1, a.right, a.bottom)
            } else {
                val left = Area(a.left, a.top, cut.start + 1, a.bottom)
                val right = Area(cut.end - 1, a.top, a.right, a.bottom)
                // A vertical split with different inner row gutters has ambiguous
                // reading order. Keep this area whole rather than silently reading
                // all of one column before panels at the top of the other column.
                if (gap(left, true) != null || gap(right, true) != null) { areas += a; return }
                first = if (rightToLeft) right else left
                second = if (rightToLeft) left else right
            }
            val firstAllowance = allowance / 2
            split(first, depth + 1, firstAllowance)
            split(second, depth + 1, allowance - firstAllowance)
        }
        split(Area(0, 0, width, height), 0, MAX_PANELS)
        return areas.map { ReaderPanelRect(it.left.toFloat() / width, it.top.toFloat() / height,
            it.right.toFloat() / width, it.bottom.toFloat() / height) }
    }

    private fun pale(pixel: Int): Boolean {
        val alpha = pixel ushr 24
        fun overWhite(channel: Int): Int = (channel * alpha + 255 * (255 - alpha) + 127) / 255
        return overWhite((pixel ushr 16) and 255) >= 245 &&
            overWhite((pixel ushr 8) and 255) >= 245 && overWhite(pixel and 255) >= 245
    }
}
