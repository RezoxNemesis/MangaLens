package com.mangalens.core.events

enum class DownloadTerminalHint { COMPLETED, FAILED }

/** Typed producer adapter; inputs come from committed native/task journals, not a model. */
object CommittedDownloadHint {
    fun create(taskId: String, executionEpoch: Long, currentlyExecuting: Boolean,
        ownedDownloadIds: Set<String>, observedDownloadId: String, terminal: DownloadTerminalHint?): AppEvent.TaskHint? {
        if (!currentlyExecuting || executionEpoch < 0 || terminal == null || ownedDownloadIds.size !in 1..8 ||
            observedDownloadId !in ownedDownloadIds || !taskId.matches(Regex("[A-Za-z0-9-]{1,80}")) ||
            ownedDownloadIds.any { !it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }) return null
        val identity = TaskEventIdentity(EventIdentity.task(taskId), EventIdentity.source(observedDownloadId),
            EventIdentity.owner(observedDownloadId), executionEpoch)
        return AppEvent.TaskHint(when (terminal) {
            DownloadTerminalHint.COMPLETED -> AppEventType.DOWNLOAD_COMPLETE
            DownloadTerminalHint.FAILED -> AppEventType.DOWNLOAD_FAILED
        }, identity)
    }
}
