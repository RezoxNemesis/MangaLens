package com.mangalens.orez.agent

import com.mangalens.ui.video.*
import org.junit.Assert.*
import org.junit.Test

/** Actual native journal/exports must provide an alternate proof, never a made-up speech digest. */
class OrezProviderCaptionEvidenceTest {
    @Test fun providerTrackCompletesWithExactExportsAndNoSpeechModelOrAudioCompletionClaim() = CaptionFixture().use { f ->
        val descriptor = OrezMediaSelection(f.source.source.uri, f.source.source.cacheKey, f.source.source.label,
            f.source.source.headers, resolutionId = f.source.source.sourceResolutionId,
            expectedDurationUs = 60_000_000, providerCaptions = f.inventory)
        val store = f.store(); val task = store.start(f.source, f.config, ownerRequestId = "orez-provider-proof")
        val saved = f.checkpoint(store, task); val proof = requireNotNull(saved.providerCaptionReceipt)
        saved.windows.forEach { window -> window.sourceCues.indices.forEach { index ->
            store.checkpointProviderTarget(task.id, task.generation, window.index, proof,
                SubtitleTranslatedCue(index, window.sourceCues[index].text))
        } }
        val completed = requireNotNull(store.finish(task.id, task.generation))
        val receipt = runCatching { OrezSubtitleNativeEvidence.receipt(completed, descriptor.sourceId, f.directory, descriptor) }.getOrNull()
        assertNotNull("Genuine native provider completion still requires ASR or a fabricated model digest", receipt)
        val actual = requireNotNull(receipt)
        assertEquals("", actual.media.speechModelSha256)
        assertFalse(actual.audioComplete)
        assertNotNull(actual.providerCaptionReceipt)
        assertNotNull(actual.exports)
        OrezSubtitleTools.verifyCompleted(actual)
    }

    @Test fun pureMetadataOrAudioCompletionCannotForgeProviderCaptionCompletion() = CaptionFixture().use { f ->
        val descriptor = OrezMediaSelection(f.source.source.uri, f.source.source.cacheKey, f.source.source.label,
            resolutionId = f.source.source.sourceResolutionId, expectedDurationUs = 60_000_000, providerCaptions = f.inventory)
        val store = f.store(); val task = store.start(f.source, f.config, ownerRequestId = "orez-provider-proof")
        val metadata = runCatching { OrezSubtitleNativeEvidence.metadataReceipt(task, descriptor.sourceId, descriptor) }.getOrNull()
        assertNotNull("Provider candidates cannot even produce honest queued metadata without Whisper", metadata)
        val forged = requireNotNull(metadata).copy(status = OrezNativeSubtitleStatus.COMPLETED,
            windowCount = 1, cueCount = 2, sourceCueCount = 2, pendingTargetCues = 0, durationMs = 60_000, processedMs = 60_000,
            exports = OrezSubtitleExports("a".repeat(64), "b".repeat(64), 100, 100), audioComplete = false)
        assertTrue(runCatching { OrezSubtitleTools.verifyCompleted(forged) }.isFailure)
    }
}
