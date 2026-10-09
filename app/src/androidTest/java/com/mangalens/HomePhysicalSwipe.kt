package com.mangalens

import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class HomeSwipeDirection { UP, DOWN, RIGHT }
internal data class HomePhysicalSwipe(val startX: Int, val startY: Int, val endX: Int, val endY: Int, val steps: Int) {
    val plannedMs: Long get() = steps * 5L
}

/** A single physical swipe; accessibility scroll-event waiting is left to fresh-frame admission. */
internal fun homePhysicalSwipe(bounds: UiActionBounds, direction: HomeSwipeDirection, speedPx: Int,
    remainingMs: Long): HomePhysicalSwipe? {
    if (!bounds.visible || speedPx <= 0) return null
    val width = bounds.right - bounds.left; val height = bounds.bottom - bounds.top
    if (width < 8 || height < 8) return null
    val centerX = bounds.left + width / 2; val centerY = bounds.top + height / 2
    val distance = ((if (direction == HomeSwipeDirection.RIGHT) width else height) * .45f).roundToInt()
    if (distance < 4) return null
    val low = distance / 2; val high = distance - low
    val steps = ceil(distance * 200.0 / speedPx).toInt().coerceIn(4, 100)
    val swipe = when (direction) {
        HomeSwipeDirection.RIGHT -> HomePhysicalSwipe(centerX + high, centerY, centerX - low, centerY, steps)
        HomeSwipeDirection.DOWN -> HomePhysicalSwipe(centerX, centerY + high, centerX, centerY - low, steps)
        HomeSwipeDirection.UP -> HomePhysicalSwipe(centerX, centerY - low, centerX, centerY + high, steps)
    }
    return swipe.takeIf { remainingMs > it.plannedMs + 200L }
}
