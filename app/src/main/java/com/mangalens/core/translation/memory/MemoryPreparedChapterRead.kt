package com.mangalens.core.translation.memory

/** IO-only decode result: the delivery lease belongs to these exact chapter and profile incarnations. */
internal class MemoryPreparedChapterRead(val chapter: MemoryChapterSnapshot, val profile: SeriesMemoryProfile?,
    val delivery: MemoryReadDeliveryLease)
