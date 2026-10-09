package com.mangalens.ui.video

import com.mangalens.download.ProviderCaptionInventory

/** A matching URL cannot grant an older provider receipt authority over another accepted resolution. */
internal fun matchesProviderPlaybackSource(task: SubtitleGenerationTask, resolutionId: String?, inventory: ProviderCaptionInventory?): Boolean =
    task.providerCaptionReceipt == null || resolutionId != null && task.source.source.sourceResolutionId == resolutionId &&
        inventory != null && task.source.source.providerCaptions == inventory
