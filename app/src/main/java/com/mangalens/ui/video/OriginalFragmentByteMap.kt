package com.mangalens.ui.video

import com.mangalens.download.OriginalFragmentPlan
import java.io.IOException

/** Encoded byte positions only: durations never stand in for byte lengths. */
internal class OriginalFragmentByteMap(plan: OriginalFragmentPlan) {
    private val lengths = plan.captured().fragments.map { it.expectedBytes ?: it.rangeStart?.let { start -> it.rangeEndExclusive!! - start } }.toMutableList()
    data class Position(val fragment: Int, val offset: Long)
    @Synchronized fun observe(index: Int, bytes: Long) {
        if (bytes !in 1..OriginalFragmentPlan.MAX_FRAGMENT_BYTES || lengths[index]?.let { it != bytes } == true)
            throw IOException("Selected fragment length changed. Resolve the source again.")
        lengths[index] = bytes
    }
    @Synchronized fun length(index: Int): Long? = lengths[index]
    @Synchronized fun total(): Long? = if (lengths.any { it == null }) null else lengths.fold(0L) { total, length -> Math.addExact(total, length!!) }
    @Synchronized fun locate(position: Long): Position? {
        require(position >= 0L)
        var remaining = position
        for (index in lengths.indices) {
            val length = lengths[index] ?: return if (remaining == 0L) Position(index, 0L) else null
            if (remaining < length) return Position(index, remaining)
            remaining -= length
        }
        return if (remaining == 0L) Position(lengths.size, 0L) else null
    }
}
