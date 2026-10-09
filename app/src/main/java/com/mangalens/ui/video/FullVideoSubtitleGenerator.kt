package com.mangalens.ui.video

import android.content.Context
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock

/** UI facade only: WorkManager owns decoding, inference and atomic window checkpoints. */
class FullVideoSubtitleGenerator(context: Context, private val engine: VideoSpeechEngine, private val scope: CoroutineScope,
    private val attachmentAllowed: (SubtitleGenerationTask) -> Boolean = { true },
    private val attachManually: ((SubtitleGenerationTask) -> Boolean)? = null) {
    private val app = context.applicationContext
    private val store = SubtitleGenerationStore.shared(app)
    private val mutable = MutableStateFlow(FullSubtitleState())
    val state: StateFlow<FullSubtitleState> = mutable
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val publication = SubtitlePublicationGate()
    private val commandLane = kotlinx.coroutines.sync.Mutex()
    private var observer: Job? = null
    private var selected: SubtitleGenerationTask? = null
    private var activeRequest: SubtitleGenerationRequest? = null
    private var attach = true
    private val attachment = SubtitleAttachmentReceipt()

    /** Reopening a player observes its exact source/model/configuration without starting new work. */
    fun bind(source: SubtitleMediaSource) = bind(source, null)
    fun bind(source: SubtitleMediaSource, targetOptions: SubtitleTargetOptions?) {
        val operation = SubtitleGenerationRequest(publication.advance(), source, engine.language, targetOptions)
        publication.publish(operation.token) {
            attachment.bind(operation.source)
            activeRequest = null
            observer?.cancel()
            selected = null
            mutable.value = FullSubtitleState(stage = "Checking saved subtitles…")
        }
        scope.launch(Dispatchers.IO) {
            try {
                val identity = SubtitleInputs.capture(app, operation.source)
                val config = SubtitleInputs.config(app, operation.sourceLanguage, operation.targetOptions)
                val task = store.find(identity, config) ?: if (config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION &&
                    config.targetLanguage == "en" && config.outputMode == SubtitleOutputMode.TRANSLATED && config.style == "natural" &&
                    !config.localRefinement && config.customStyle.isEmpty()) store.find(identity, SubtitleInputs.config(app, operation.sourceLanguage)) else null
                if (task == null) {
                    withContext(Dispatchers.Main.immediate) {
                        publication.publish(operation.token) { if (scope.isActive) mutable.value = FullSubtitleState() }
                    }
                    return@launch
                }
                if (canTrustSubtitleSource(task.source, identity)) store.confirmValidated(task.id, task.generation)
                observe(task, operation.token)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { publishFailure(operation.token, "Cannot restore subtitles", failure.message) }
        }
    }

    fun generate(source: SubtitleMediaSource, attachToPlayer: Boolean = true, force: Boolean = false, targetOptions: SubtitleTargetOptions? = null) {
        // Detach caller-owned headers and capture language before any coroutine is dispatched.
        val operation = SubtitleGenerationRequest(publication.advance(), source, engine.language, targetOptions)
        publication.publish(operation.token) {
            attachment.bind(operation.source)
            activeRequest = operation
            observer?.cancel()
            selected = null
            attach = attachToPlayer
            engine.setEnabled(false)
            mutable.value = FullSubtitleState(running = true, progress = .01f, stage = "Checking video and speech model…")
        }
        // Acquire the scheduling lane before dispatch so accepted requests cannot
        // commit in reverse order after slow hashing. Work itself is owned by WM.
        launchCommand {
            try {
                val identity = SubtitleInputs.capture(app, operation.source)
                val config = SubtitleInputs.config(app, operation.sourceLanguage, operation.targetOptions)
                val previous = store.find(identity, config)
                var task = SubtitleGenerationJobs.start(app, identity, config, force || previous?.status == SubtitleGenerationStatus.CANCELLED)
                operation.receipt = task
                if (!force && operation.control == null && task.status in setOf(SubtitleGenerationStatus.PAUSED, SubtitleGenerationStatus.PARTIAL, SubtitleGenerationStatus.FAILED) && config.modelSha256 != null) {
                    task = SubtitleGenerationJobs.resume(app, task.id, task.generation) ?: task
                    operation.receipt = task
                }
                applyPendingControl(operation, task)
                observe(task, operation.token)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { publishFailure(operation.token, "Subtitle generation failed", failure.message ?: "Unable to schedule subtitles") }
        }
    }
    fun pause() = dispatchControl("pause")
    fun cancel() = dispatchControl("cancel")
    fun resume() {
        val operation = publication.capture { _ ->
            val captured = selected ?: return@capture null
            val next = SubtitleGenerationRequest(publication.advance(), captured.source.source, captured.config.sourceLanguage)
            next.receipt = captured
            activeRequest = next
            observer?.cancel()
            next
        } ?: return
        val captured = operation.receipt ?: return
        launchCommand {
            try {
                val resumed = SubtitleGenerationJobs.resume(app, captured.id, captured.generation) ?: return@launchCommand
                operation.receipt = resumed
                applyPendingControl(operation, resumed)
                observe(resumed, operation.token)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { publishFailure(operation.token, "Cannot resume subtitles", failure.message) }
        }
    }
    fun applyToPlayer() {
        val captured = publication.capture { token ->
            selected?.takeIf { it.id == mutable.value.taskId && it.generation == mutable.value.generation }
                ?.let { token to it }
        } ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val task = store.refresh(captured.second.id, captured.second.generation) ?: return@launch
                if (!qualifies(task, captured.second)) return@launch
                val fresh = SubtitleInputs.capture(app, task.source.source)
                if (!canTrustSubtitleSource(task.source, fresh)) {
                    publishFailure(captured.first, "Cannot apply saved subtitles", "The video source changed. Reopen it before applying saved subtitles.")
                    return@launch
                }
                withContext(Dispatchers.Main.immediate) {
                    publication.publish(captured.first) {
                        val latest = store.get(task.id) ?: return@publish
                        if (!scope.isActive || latest != task || !qualifies(latest, captured.second)) return@publish
                        val applied = attachManually?.invoke(task) ?: run {
                            engine.applyGeneratedCues(task.cues, task.config.outputMode); true
                        }
                        if (applied) attachment.attached(task.generation)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { publishFailure(captured.first, "Cannot apply saved subtitles", "Saved subtitles could not be verified. Resume or regenerate them.") }
        }
    }

    private fun qualifies(task: SubtitleGenerationTask, captured: SubtitleGenerationTask): Boolean =
        sameSubtitlePlaybackReceipt(task, captured) && !task.validationPending && !task.pcmValidationRequired &&
            hasSubtitleSourceProof(task.source) && task.cues.isNotEmpty() &&
            (captured.status != SubtitleGenerationStatus.COMPLETED || task.status == SubtitleGenerationStatus.COMPLETED && task.srtPath != null && task.vttPath != null)

    private fun launchCommand(action: suspend () -> Unit) {
        commands.launch(start = CoroutineStart.UNDISPATCHED) {
            commandLane.withLock { withContext(Dispatchers.IO) { action() } }
        }
    }
    /** Wait for accepted scheduling/control operations; it does not wait for background inference. */
    internal suspend fun awaitAcceptedCommands() { commandLane.withLock { } }
    private suspend fun applyPendingControl(operation: SubtitleGenerationRequest, task: SubtitleGenerationTask) {
        when (operation.control) {
            "cancel" -> SubtitleGenerationJobs.cancel(app, task.id, task.generation)
            "pause" -> SubtitleGenerationJobs.pause(app, task.id, task.generation)
        }
    }
    private fun dispatchControl(control: String) {
        val captured = publication.capture { token ->
            activeRequest?.control = control
            (activeRequest?.receipt ?: selected)?.let { token to it }
        } ?: return
        launchCommand {
            var error: String? = null
            runSubtitleControl(action = {
                if (control == "cancel") SubtitleGenerationJobs.cancel(app, captured.second.id, captured.second.generation)
                else SubtitleGenerationJobs.pause(app, captured.second.id, captured.second.generation)
            }, accepted = { publication.current() == captured.first && store.get(captured.second.id)?.generation == captured.second.generation },
                onFailure = { error = it })
            error?.let { message ->
                withContext(Dispatchers.Main.immediate) {
                    publication.publish(captured.first) {
                        if (scope.isActive && store.get(captured.second.id)?.generation == captured.second.generation)
                            mutable.value = mutable.value.copy(error = message)
                    }
                }
            }
        }
    }
    private suspend fun publishFailure(token: Long, stage: String, message: String?) = withContext(Dispatchers.Main.immediate) {
        publication.publish(token) {
            if (scope.isActive) mutable.value = mutable.value.copy(running = false, stage = stage, error = message?.take(300))
        }
    }
    private suspend fun observe(captured: SubtitleGenerationTask, token: Long) = withContext(Dispatchers.Main.immediate) {
        publication.publish(token) {
            if (!scope.isActive) return@publish
            selected = store.commandSnapshot(captured)
            activeRequest?.takeIf { it.token == token }?.receipt = selected
            observer?.cancel()
            observer = scope.launch(Dispatchers.IO) {
                store.states.collect { tasks ->
                    if (publication.current() != token) return@collect
                    val task = tasks.firstOrNull { it.id == captured.id } ?: return@collect
                    if (!sameSubtitlePlaybackReceipt(task, captured)) {
                        withContext(Dispatchers.Main.immediate) {
                            publication.publish(token) {
                                if (scope.isActive && store.get(captured.id)?.let { !sameSubtitlePlaybackReceipt(it, captured) } == true)
                                    mutable.value = mutable.value.copy(running = false, stage = "Saved subtitle request was replaced",
                                        error = "Reopen this video's subtitle tools to observe the new request.")
                            }
                        }
                        return@collect
                    }
                    val cues = task.cues
                    val running = task.status in setOf(SubtitleGenerationStatus.QUEUED, SubtitleGenerationStatus.RUNNING)
                    val stage = when {
                        task.validationPending || task.pcmValidationRequired -> "Checking saved video and speech windows…"
                        task.status == SubtitleGenerationStatus.COMPLETED -> "${targetName(task.config.targetLanguage)} subtitles ready"
                        task.status == SubtitleGenerationStatus.QUEUED -> "Queued for background subtitle generation"
                        task.status == SubtitleGenerationStatus.RUNNING -> if (task.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION)
                            "${task.sourceCueCount} original speech cues • ${task.translatedCueCount} translated" else "${task.windows.size} audio windows saved"
                        task.status == SubtitleGenerationStatus.PAUSED -> "Subtitle generation paused"
                        task.status == SubtitleGenerationStatus.CANCELLED -> "Subtitle generation cancelled"
                        task.status == SubtitleGenerationStatus.PARTIAL -> "Generation stopped • completed windows retained"
                        else -> "Subtitle generation failed"
                    }
                    val display = FullSubtitleState(running = running,
                        progress = if (task.status == SubtitleGenerationStatus.COMPLETED) 1f else if (task.durationMs > 0) (task.processedMs.toFloat() / task.durationMs).coerceIn(0f, .99f) else 0f,
                        stage = stage, cues = cues, srt = SubtitleFormats.srt(cues), outputFile = task.srtPath?.let(::File),
                        cached = task.status == SubtitleGenerationStatus.COMPLETED, error = task.error, taskId = task.id, generation = task.generation,
                        status = task.status, completedWindows = task.windows.size, vtt = SubtitleFormats.vtt(cues), vttFile = task.vttPath?.let(::File),
                        sourceCacheKey = task.source.source.cacheKey, targetLanguage = task.config.targetLanguage,
                        outputMode = task.config.outputMode, sourceCues = task.sourceCues, pendingTargetCues = task.pendingTargetCues, pipeline = task.config.pipeline)
                    currentCoroutineContext().ensureActive()
                    // Formatting and journal work can take time. Recheck only at the
                    // final Main publication, including observer changes and attachment.
                    withContext(Dispatchers.Main.immediate) {
                        publication.publish(token) {
                            if (!scope.isActive) return@publish
                            val latest = store.get(captured.id) ?: return@publish
                            if (!sameSubtitlePlaybackReceipt(latest, captured) || latest != task) return@publish
                            selected = task
                            activeRequest?.takeIf { it.token == token }?.receipt = task
                            mutable.value = display
                            if (attach && attachment.shouldAttach(task.generation)) {
                                attachAutomaticSubtitleTrack(task, captured, attachmentAllowed) { verified ->
                                    attachment.attached(verified.generation)
                                    engine.applyGeneratedCues(verified.cues, verified.config.outputMode)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    companion object {
        internal fun targetName(language: String): String = when (language) { "hi" -> "Hindi"; "hi-latn" -> "Hinglish"; else -> "English" }
        fun toSrt(cues: List<SpeechCue>): String = cues.mapIndexed { index, cue ->
            "${index + 1}\n${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n${cue.text.trim()}\n"
        }.joinToString("\n")

        fun parseSrt(value: String): List<SpeechCue> {
            val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
            return normalized.split(Regex("\\n\\s*\\n"))
                .mapNotNull { block ->
                    val lines = block.lines().filter { it.isNotBlank() }
                    if (lines.size < 3) return@mapNotNull null
                    val timingIndex = lines.indexOfFirst { "-->" in it }
                    if (timingIndex < 0) return@mapNotNull null
                    val timing = lines[timingIndex].split("-->", limit = 2)
                    if (timing.size != 2) return@mapNotNull null
                    val start = parseTimestamp(timing[0].trim()) ?: return@mapNotNull null
                    val end = parseTimestamp(timing[1].trim()) ?: return@mapNotNull null
                    val text = lines.drop(timingIndex + 1).joinToString("\n").trim()
                    if (end <= start || text.isBlank()) null else SpeechCue(start, end, text)
                }
        }

        private fun timestamp(ms: Long): String {
            val t = ms.coerceAtLeast(0L)
            return "%02d:%02d:%02d,%03d".format(
                t / 3_600_000,
                t / 60_000 % 60,
                t / 1_000 % 60,
                t % 1_000
            )
        }

        private fun formatClock(ms: Long): String =
            "%02d:%02d".format((ms.coerceAtLeast(0L) / 60_000), (ms / 1_000) % 60)

        private fun parseTimestamp(value: String): Long? {
            val match = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})""").matchEntire(value) ?: return null
            val (h, m, s, ms) = match.destructured
            return h.toLongOrNull()?.times(3_600_000)
                ?.plus((m.toLongOrNull() ?: return null) * 60_000)
                ?.plus((s.toLongOrNull() ?: return null) * 1_000)
                ?.plus(ms.toLongOrNull() ?: return null)
        }
    }

}
