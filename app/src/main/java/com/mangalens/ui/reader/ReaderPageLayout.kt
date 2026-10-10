package com.mangalens.ui.reader

/** Maps list ordinals, not remote source URLs or sparse native page indices. */
internal data class ReaderPageLayout(val mode: String, val count: Int, val landscape: Boolean, val spreadRtl: Boolean = false) {
    init { require(count >= 0) }
    val columns: Int get() = if (mode == "spread" && landscape) 2 else 1
    val physicalCount: Int get() = if (count == 0) 0 else ((count.toLong() + columns - 1) / columns).toInt()
    val reversed: Boolean get() = mode == "rtl" || mode == "spread" && spreadRtl
    fun physicalForLogical(page: Int): Int = if (count == 0) 0 else page.coerceIn(0, count - 1) / columns
    fun logicalForPhysical(physical: Int, preferred: Int): Int {
        if (count == 0) return 0
        val first = physical.coerceIn(0, physicalCount - 1) * columns
        return if (preferred in first until minOf(count, first + columns)) preferred else first
    }
    fun ordinals(physical: Int): List<Int> {
        if (count == 0 || physical !in 0 until physicalCount) return emptyList()
        val first = physical * columns
        val values = (first until minOf(count, first + columns)).toList()
        return if (columns == 2 && reversed) values.asReversed() else values
    }
    fun advance(page: Int, delta: Int): Int = if (count == 0) 0 else (page.toLong() + delta.toLong() * columns)
        .coerceIn(0L, (count - 1).toLong()).toInt()
    fun canAdvance(page: Int, delta: Int): Boolean = count > 0 && physicalForLogical(page) + delta in 0 until physicalCount
}
