package com.mangalens.orez

import java.security.MessageDigest

data class OrezRowWindow(val startRow: Long, val endRow: Long)

internal object OrezCorpusWindowPlanner {
    fun plan(
        totalRows: Long,
        query: String,
        windowSize: Int = 1_024,
        maxWindows: Int = 5
    ): List<OrezRowWindow> {
        if (totalRows <= 0L || maxWindows <= 0) return emptyList()
        val size = windowSize.coerceAtLeast(1).toLong()
        if (totalRows <= size || maxWindows == 1) {
            return listOf(OrezRowWindow(1L, minOf(totalRows, size)))
        }

        val windows = linkedSetOf<OrezRowWindow>()
        windows += OrezRowWindow(1L, minOf(size, totalRows))

        val tailStart = maxOf(1L, totalRows - size + 1L)
        val tail = OrezRowWindow(tailStart, totalRows)

        val middleSlots = (maxWindows - 2).coerceAtLeast(0)
        val maxStart = maxOf(1L, totalRows - size + 1L)
        var salt = 0
        while (windows.size < 1 + middleSlots && salt < middleSlots * 8 + 8) {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest((query.trim().lowercase() + "#" + salt).toByteArray(Charsets.UTF_8))
            var value = 0L
            for (i in 0 until minOf(8, hash.size)) {
                value = (value shl 8) or (hash[i].toLong() and 0xffL)
            }
            val positive = value and Long.MAX_VALUE
            val start = 1L + (positive % maxStart)
            windows += OrezRowWindow(start, minOf(totalRows, start + size - 1L))
            salt++
        }

        windows += tail
        return windows
            .sortedBy { it.startRow }
            .take(maxWindows)
    }
}
