package com.mangalens.ui.video

internal fun browserCaptionBinding(task: SubtitleGenerationTask): BrowserCaptionTaskBinding =
    BrowserCaptionTaskBinding(BrowserCaptionAuthorityKey(requireNotNull(task.source.source.sourceResolutionId),
        task.source.fingerprint, task.config.fingerprint()), task.id, task.generation)
