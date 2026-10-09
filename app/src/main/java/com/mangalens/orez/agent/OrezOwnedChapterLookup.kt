package com.mangalens.orez.agent

import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.core.translation.ChapterTranslationTask

internal object OrezOwnedChapterLookup {
    suspend fun refresh(native: ChapterTranslationStore, taskId: String, ownerRequestId: String,
        expectedGeneration: String? = null, verifyScope: suspend (ChapterTranslationTask) -> Unit): ChapterTranslationTask? {
        val captured = native.get(taskId)?.takeIf { it.ownerRequestId == ownerRequestId &&
            (expectedGeneration == null || it.generation == expectedGeneration) } ?: return null
        verifyScope(captured)
        return native.refresh(taskId, captured.generation)?.also {
            require(it.ownerRequestId == ownerRequestId && it.generation == captured.generation) { "Native translation was replaced during validation." }
            verifyScope(it)
        }
    }
}
