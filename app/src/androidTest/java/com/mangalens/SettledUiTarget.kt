package com.mangalens

internal data class UiActionBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val visible: Boolean get() = right > left && bottom > top
}

/** A physical action owns fresh bounds only after the expected UI/layout is briefly stable. */
internal class SettledUiTarget {
    private var previous: UiActionBounds? = null
    private var sinceMs: Long = 0

    fun observe(bounds: UiActionBounds?, ready: Boolean, nowMs: Long): Boolean {
        if (!ready || bounds?.visible != true) {
            previous = null
            return false
        }
        if (bounds != previous || nowMs < sinceMs) {
            previous = bounds
            sinceMs = nowMs
            return false
        }
        return nowMs - sinceMs >= 100L
    }
}
