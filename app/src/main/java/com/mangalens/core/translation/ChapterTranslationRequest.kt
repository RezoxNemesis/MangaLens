package com.mangalens.core.translation

/** User controls received while a durable start is still validating or being scheduled. */
class ChapterTranslationRequest {
    @Volatile private var cancelled = false
    @Volatile private var paused = false

    fun cancel() { cancelled = true }
    fun setPaused(value: Boolean) { paused = value }

    suspend fun dispatch(
        start: suspend () -> ChapterTranslationTask,
        pause: suspend (ChapterTranslationTask) -> ChapterTranslationTask?,
        resume: suspend (ChapterTranslationTask) -> ChapterTranslationTask?,
        cancel: suspend (ChapterTranslationTask) -> ChapterTranslationTask?
    ): ChapterTranslationTask? {
        if (cancelled) return null
        var task = start()
        while (true) {
            if (cancelled) return cancel(task) ?: task
            if (paused && task.status in setOf(ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING)) {
                val changed = pause(task) ?: return task
                if (changed == task) return task
                task = changed
                continue
            }
            if (!paused && task.status == ChapterTranslationStatus.PAUSED) {
                val changed = resume(task) ?: return task
                if (changed == task) return task
                task = changed
                continue
            }
            return task
        }
    }
}

/** Scheduling/control acceptance survives an Activity or ViewModel being destroyed. */
internal object ChapterTranslationCommandScope {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
}
