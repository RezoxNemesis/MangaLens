package com.mangalens.ui.video

internal fun sameSubtitlePlaybackReceipt(task: SubtitleGenerationTask, captured: SubtitleGenerationTask): Boolean =
    task.id == captured.id && task.generation == captured.generation && task.ownerRequestId == captured.ownerRequestId &&
        task.source == captured.source && task.config == captured.config

/** Source/result proof is checked before invoking a player callback that can retire native text. */
internal fun attachAutomaticSubtitleTrack(task: SubtitleGenerationTask, captured: SubtitleGenerationTask,
    allowed: (SubtitleGenerationTask) -> Boolean, attach: (SubtitleGenerationTask) -> Unit): Boolean {
    if (!sameSubtitlePlaybackReceipt(task, captured) || task.status != SubtitleGenerationStatus.COMPLETED ||
        task.validationPending || task.pcmValidationRequired || !hasSubtitlePlaybackProof(task) ||
        task.srtPath == null || task.vttPath == null || task.cues.isEmpty()) return false
    if (!allowed(task)) return false
    attach(task)
    return true
}
