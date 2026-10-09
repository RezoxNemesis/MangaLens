package com.mangalens.orez.agent

import com.mangalens.ui.video.SubtitleGenerationStore
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleSourceIdentity
import com.mangalens.ui.video.canTrustSubtitleSource

/** Receipt inspection may repair exports, so it needs the same owner/generation boundary as controls. */
internal object OrezOwnedSubtitleLookup {
    suspend fun refresh(native: SubtitleGenerationStore, taskId: String, ownerRequestId: String,
        expectedGeneration: String? = null, verifyScope: suspend (SubtitleGenerationTask) -> Unit): SubtitleGenerationTask? {
        val captured = native.get(taskId)?.takeIf {
            it.ownerRequestId == ownerRequestId && (expectedGeneration == null || it.generation == expectedGeneration)
        } ?: return null
        verifyScope(captured)
        return native.refresh(taskId, captured.generation)?.also {
            require(it.ownerRequestId == ownerRequestId && it.generation == captured.generation) {
                "The native subtitle request changed while its receipt was being refreshed."
            }
            verifyScope(it)
        }
    }

    suspend fun confirmSource(native: SubtitleGenerationStore, captured: SubtitleGenerationTask, fresh: SubtitleSourceIdentity,
        verifyScope: suspend (SubtitleGenerationTask) -> Unit): SubtitleGenerationTask? {
        verifyScope(captured)
        require(captured.source.source == fresh.source && canTrustSubtitleSource(captured.source, fresh)) {
            "The selected playable source is unavailable or changed after its receipt was captured."
        }
        return native.confirmValidated(captured.id, captured.generation)?.also { confirmed ->
            require(confirmed.ownerRequestId == captured.ownerRequestId && confirmed.generation == captured.generation)
            verifyScope(confirmed)
        }
    }
}
