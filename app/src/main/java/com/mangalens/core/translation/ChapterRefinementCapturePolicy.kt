package com.mangalens.core.translation

/** Capture occurs before durable dispatch. Replay/resume keep their original request. */
internal object ChapterRefinementCapturePolicy {
    suspend fun forStart(configuration: ChapterTranslationConfig, chapterId: String, ownerRequestId: String?,
        forceReprocess: Boolean, existing: List<ChapterTranslationTask>,
        capture: suspend (TranslationStyleProfile) -> TranslationRefinementRequest): ChapterTranslationConfig {
        val config = configuration.normalized()
        if (!config.localRefinement || config.refinementRequest != null) return config
        if (ownerRequestId != null && !forceReprocess) {
            val owned = existing.filter { it.chapterId == chapterId && it.ownerRequestId == ownerRequestId &&
                it.config.copy(refinementRequest = null) == config.copy(refinementRequest = null) }
            check(owned.size <= 1) { "The captured request has multiple refinement identities. Resume its exact task." }
            owned.singleOrNull()?.let { return it.config }
        }
        return config.copy(refinementRequest = capture(config.style())).normalized()
    }
}
