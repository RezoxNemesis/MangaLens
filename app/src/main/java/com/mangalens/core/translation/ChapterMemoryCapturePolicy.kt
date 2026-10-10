package com.mangalens.core.translation

import com.mangalens.core.translation.memory.CapturedSeriesMemoryPacket

/** A stable owned replay preserves captured absence as well as a captured packet. */
internal object ChapterMemoryCapturePolicy {
    suspend fun forStart(configuration: ChapterTranslationConfig, chapterId: String, ownerRequestId: String?, forceReprocess: Boolean,
        existing: List<ChapterTranslationTask>, capture: suspend (ChapterTranslationConfig) -> CapturedSeriesMemoryPacket?): ChapterTranslationConfig {
        val config = configuration.normalized()
        if (config.memoryPacket != null) return config
        if (ownerRequestId != null && !forceReprocess) {
            val owned = existing.filter { it.chapterId == chapterId && it.ownerRequestId == ownerRequestId &&
                it.config.copy(memoryPacket = null) == config.copy(memoryPacket = null) }
            check(owned.size <= 1) { "The captured request has multiple memory identities. Resume its exact task." }
            owned.singleOrNull()?.let { return it.config }
        }
        if (!config.localRefinement) return config
        return config.copy(memoryPacket = capture(config)).normalized()
    }
}
