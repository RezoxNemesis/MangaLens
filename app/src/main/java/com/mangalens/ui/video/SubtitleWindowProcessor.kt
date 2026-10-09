package com.mangalens.ui.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SubtitleRecognizedWindow(val cues: List<SpeechCue>, val detectedLanguage: String? = null)
internal data class SubtitleWindowTranslationProgress(val savedTargets: Int, val failedTargets: Int, val error: String? = null)

/** Recognition commits before any translator can run; every target cue has its own atomic commit. */
internal class SubtitleWindowProcessor(
    private val store: SubtitleGenerationStore,
    private val captured: SubtitleGenerationTask,
    private val checkOwner: suspend () -> Unit,
    private val translate: suspend (SubtitleGenerationTask, SubtitleWindow, Int) -> SubtitleTranslatedCue
) {
    suspend fun process(index: Int, startMs: Long, endMs: Long, pcmSha256: String, durationMs: Long, silent: Boolean,
        recognize: suspend () -> SubtitleRecognizedWindow): SubtitleWindowTranslationProgress {
        currentCoroutineContext().ensureActive()
        checkOwner()
        var task = current()
        val previous = task.windows.getOrNull(index)
        if (previous != null) {
            if (previous.startMs != startMs || previous.endMs != endMs || previous.pcmSha256 != pcmSha256) {
                store.fail(captured.id, captured.generation, "Decoded source audio differs from the saved original speech. Generate a new task.", invalidate = true)
                throw CancellationException("Changed source audio invalidated its old checkpoints.")
            }
        } else {
            val original = if (silent) SubtitleRecognizedWindow(emptyList()) else recognize()
            currentCoroutineContext().ensureActive()
            checkOwner()
            check(silent || original.cues.isNotEmpty()) { "An active audio window produced no recognizable speech. Earlier windows have been kept." }
            if (!store.checkpoint(captured.id, captured.generation, SubtitleWindow(index, startMs, endMs, pcmSha256,
                    silent = silent, sourceCues = original.cues, detectedLanguage = original.detectedLanguage), durationMs, original.detectedLanguage))
                throw CancellationException("Original-speech checkpoint generation was replaced.")
            task = current()
        }
        return translateSaved(task.windows[index])
    }

    /** A proof-bound complete source track can repair translations without loading/repeating ASR. */
    suspend fun translateSaved(window: SubtitleWindow): SubtitleWindowTranslationProgress {
        var successes = 0
        var failures = 0
        var firstError: String? = null
        for (sourceIndex in window.sourceCues.indices) {
            currentCoroutineContext().ensureActive()
            checkOwner()
            val task = current()
            val saved = task.windows[window.index]
            check(saved.pcmSha256 == window.pcmSha256)
            if (saved.translations.any { it.sourceIndex == sourceIndex }) continue
            try {
                val target = translate(task, saved, sourceIndex)
                currentCoroutineContext().ensureActive()
                checkOwner()
                if (!store.checkpointTarget(captured.id, captured.generation, window.index, window.pcmSha256, target))
                    throw CancellationException("Target-speech checkpoint generation was replaced.")
                successes++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (blocked: SubtitleOwnedWorkBlocked) { throw blocked }
            catch (paused: com.mangalens.core.compute.ResourcePausedException) { throw paused }
            catch (failure: Exception) {
                failures++
                firstError = firstError ?: (failure.message ?: "A speech cue could not be translated.").take(300)
                // A failed provider must not retry a model download for dozens of cues.
                if (failures >= 3) break
            }
        }
        return SubtitleWindowTranslationProgress(successes, failures, firstError)
    }

    private fun current(): SubtitleGenerationTask = store.get(captured.id)?.takeIf {
        sameSubtitleGeneration(captured, it) && store.current(it.id, it.generation)
    } ?: throw CancellationException("Subtitle task was paused, cancelled or replaced.")
}
