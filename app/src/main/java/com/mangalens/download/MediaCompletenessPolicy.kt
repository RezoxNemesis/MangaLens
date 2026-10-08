package com.mangalens.download

/** Allow frame/audio packet rounding, never a missing multi-minute tail. */
internal object MediaCompletenessPolicy {
    fun tailToleranceUs(durationUs: Long): Long =
        (durationUs / 100).coerceIn(2_000_000L, 10_000_000L)

    fun hasCompleteTail(durationUs: Long, lastSampleUs: Long): Boolean =
        lastSampleUs >= 0L && (durationUs <= 0L || lastSampleUs >= durationUs - tailToleranceUs(durationUs))
}
