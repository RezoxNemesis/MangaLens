package com.mangalens.ui.web

import com.mangalens.ui.video.BrowserCaptionAuthority
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.browserCaptionBinding

/** Final post-suspension attachment boundary; disk proof cannot replace current native authority. */
internal fun browserCaptionPublicationCurrent(authority: BrowserCaptionAuthority,
    exported: SubtitleGenerationTask, currentTasks: List<SubtitleGenerationTask>): Boolean =
    currentTasks.any { it == exported } && authority.permits(browserCaptionBinding(exported))
