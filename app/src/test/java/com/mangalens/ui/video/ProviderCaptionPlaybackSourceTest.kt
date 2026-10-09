package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class ProviderCaptionPlaybackSourceTest {
    @Test fun sameUrlDoesNotAuthorizeAnOlderAcceptedResolution() = CaptionFixture().use { f ->
        val store = f.store(); val task = f.checkpoint(store, store.start(f.source, f.config))
        assertFalse("Same URL admitted a caption receipt owned by another accepted resolution",
            matchesProviderPlaybackSource(task, "b".repeat(32), f.inventory))
    }
    @Test fun uncapturedOrChangedInventoryCannotAdoptAGeneratedProviderTrack() = CaptionFixture().use { f ->
        val store = f.store(); val task = f.checkpoint(store, store.start(f.source, f.config))
        assertFalse(matchesProviderPlaybackSource(task, null, f.inventory))
        assertFalse(matchesProviderPlaybackSource(task, f.source.source.sourceResolutionId, null))
        assertFalse(matchesProviderPlaybackSource(task, f.source.source.sourceResolutionId, f.inventory.copy(expectedDurationMs = 70_000)))
    }
    @Test fun exactAcceptedResolutionAndInventoryRemainEligible() = CaptionFixture().use { f ->
        val store = f.store(); val task = f.checkpoint(store, store.start(f.source, f.config))
        assertTrue(matchesProviderPlaybackSource(task, f.source.source.sourceResolutionId, f.inventory.captureSnapshot()))
    }
    @Test fun nonProviderSpeechDoesNotNeedAnInventedResolutionReceipt() = CaptionFixture().use { f ->
        val task = f.store().start(f.source.copy(source = f.source.source.copy(providerCaptions = null, sourceResolutionId = null)),
            SubtitleGenerationConfig(modelSha256 = "b".repeat(64)))
        assertTrue(matchesProviderPlaybackSource(task, null, null))
    }
}
