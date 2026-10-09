package com.mangalens

internal data class HomeSectionSample(val description: String, val bounds: UiActionBounds)
internal enum class HomeOrderObservation { SEEKING, IN_ORDER, REVERSED }

/** Physical traversal may see ordered lazy items in different viewport frames. */
internal class HomeModuleOrderProbe(private val before: String, private val after: String) {
    private var started = false
    private var observedBefore = false
    private var result = HomeOrderObservation.SEEKING

    fun observe(atStart: Boolean, sections: List<HomeSectionSample>): HomeOrderObservation {
        if (!started) {
            if (!atStart) return HomeOrderObservation.SEEKING
            started = true
        }
        if (result != HomeOrderObservation.SEEKING) return result
        for (section in sections.filter { it.bounds.visible }.sortedBy { it.bounds.top }) {
            when (section.description) {
                before -> observedBefore = true
                after -> {
                    result = if (observedBefore) HomeOrderObservation.IN_ORDER else HomeOrderObservation.REVERSED
                    return result
                }
            }
        }
        return result
    }
}

internal class HomeShortcutSearch(private val deadlineMs: Long) {
    fun canContinue(nowMs: Long): Boolean = nowMs < deadlineMs
}

internal fun homeShortcutFullyVisible(target: UiActionBounds?, row: UiActionBounds?, minimumWidthPx: Int): Boolean {
    if (target?.visible != true || row?.visible != true || minimumWidthPx <= 0) return false
    return target.left >= row.left && target.right <= row.right &&
        target.top >= row.top && target.bottom <= row.bottom &&
        target.right - target.left >= minimumWidthPx - 1
}
