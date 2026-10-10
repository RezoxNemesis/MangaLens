package com.mangalens.ui.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mangalens.whisper.WhisperNative
import com.mangalens.core.compute.NativeComputeAdmission
import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.core.compute.checkNativeComputePrecondition
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

 data class SpeechState(
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val status: String = "Install a multilingual speech model to generate English subtitles from video audio.",
    val inferenceMs: Long = 0,
    val cues: List<SpeechCue> = emptyList(),
    val latestText: String = "",
    val revision: Long = 0,
    val generated: Boolean = false,
    val capturedAudioMs: Long = 0L,
    val processedWindows: Int = 0,
    val lastAudioAtMs: Long = 0L,
    val generatedOutputMode: SubtitleOutputMode = SubtitleOutputMode.TRANSLATED
)

/** Transcribes decoded media audio only; never opens the microphone. */
@OptIn(UnstableApi::class)
class VideoSpeechEngine private constructor(private val context: Context, private val scope: CoroutineScope,
    private val model: File, private val pinnedReadOnly: Boolean) {
    constructor(context: Context, scope: CoroutineScope) : this(context, scope,
        File(context.filesDir, "speech/whisper.bin"), false)
    /** Internal microphone-only captured-FD path; ordinary install/load API remains unchanged. */
    internal constructor(context: Context, scope: CoroutineScope, capturedModel: File) : this(context, scope, capturedModel, true) {
        require(Regex("/proc/self/fd/[0-9]+").matches(capturedModel.absolutePath))
    }

    private val mutable = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = mutable
    private val lock = Mutex()
    private val native by lazy { WhisperNative() }
    private val handleGuard = Any()
    @Volatile private var closed = false
    @Volatile private var handle = 0L
    @Volatile private var enabled = false
    @Volatile private var generation = 0L
    @Volatile private var modelEpoch = 0L
    @Volatile private var detectedSourceLanguage: String? = null
    private val liveBudget = NativeLiveSpeechBudget(LIVE_INFERENCE_BUDGET_MS, android.os.SystemClock::elapsedRealtime)
    private val liveHandles = NativeLiveSpeechHandles(handleGuard, { handle }) { native.cancel(it) }
    @Volatile var positionMs = 0L
    @Volatile var language = context.getSharedPreferences("live_audio_subtitles", Context.MODE_PRIVATE).getString("source", "auto") ?: "auto"
    @Volatile var chunkSeconds = context.getSharedPreferences("live_audio_subtitles", Context.MODE_PRIVATE).getInt("chunk_seconds", 4).coerceIn(3, 12)
    private val windowSnapshots = LiveSpeechWindowSnapshots(handleGuard, android.os.SystemClock::elapsedRealtime) {
        LiveSpeechControlSnapshot(closed, enabled, mutable.value.ready && handle != 0L,
            generation, modelEpoch, language)
    }
    private data class Chunk(val audio: FloatArray, val startMs: Long, val request: LiveSpeechWindowSnapshot)
    private val chunks = Channel<Chunk>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val processor = SpeechAudioProcessor()
    init {
        scope.launch(Dispatchers.Default) {
            for (chunk in chunks) {
                if (!windowSnapshots.isCurrent(chunk.request)) continue
                val isCurrent = { windowSnapshots.isCurrent(chunk.request) }
                try {
                    val started = chunk.request.enqueuedAtMs
                    val outcome = withContext(NativeLiveSpeechEnqueuedAt(chunk.request.enqueuedAtMs)) {
                        liveBudget.run(lock, isCurrent = isCurrent) { permit ->
                            val invocation = synchronized(handleGuard) {
                                if (!permit() || handle == 0L) null else liveHandles.begin(handle)
                            }
                            if (invocation == null) null else object : NativeLiveSpeechInvocation<Array<String>> {
                                override fun infer(): Array<String> {
                                    val requestedLanguage = chunk.request.inferenceLanguage(detectedSourceLanguage)
                                    val translateToEnglish = requestedLanguage != "en"
                                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                                    if (!permit()) return emptyArray()
                                    return native.infer(
                                        invocation.handle,
                                        chunk.audio,
                                        requestedLanguage,
                                        translateToEnglish,
                                        threads
                                    ).also {
                                        if (permit() && chunk.request.sourceLanguage == "auto" && detectedSourceLanguage == null) {
                                            val detected = runCatching { native.detectedLanguage(invocation.handle) }
                                                .getOrNull()?.takeIf { detected -> detected.isNotBlank() && detected != "auto" }
                                            synchronized(handleGuard) {
                                                if (permit() && chunk.request.sourceLanguage == "auto" && detectedSourceLanguage == null) {
                                                    detectedSourceLanguage = detected
                                                }
                                            }
                                        }
                                    }
                                }
                                override fun cancel() { liveHandles.cancel(invocation) }
                                override fun finish() { liveHandles.finish(invocation) }
                            }
                        }
                    }
                    if (!isCurrent()) continue
                    if (outcome == NativeLiveSpeechOutcome.Invalidated) continue
                    if (outcome == NativeLiveSpeechOutcome.TimedOut) {
                        publishLiveSpeechWindow(handleGuard, isCurrent) {
                            chunkSeconds = 3
                            mutable.update { current ->
                                current.copy(
                                    inferenceMs = android.os.SystemClock.elapsedRealtime() - started,
                                    processedWindows = current.processedWindows + 1,
                                    status = "Speech processing exceeded 20 s • switched to Low latency"
                                )
                            }
                        }
                        continue
                    }
                    val segments = (outcome as NativeLiveSpeechOutcome.Completed).value
                    val cues = segments.mapNotNull { line ->
                        val parts = line.split('\t', limit = 3)
                        val text = parts.getOrNull(2)?.trim().orEmpty()
                        if (text.isBlank()) null else {
                            val duration = chunk.audio.size * 1000L / 16000
                            val start = (parts[0].toLongOrNull() ?: 0L).coerceIn(0L, duration)
                            val end = (parts[1].toLongOrNull() ?: duration).coerceIn(start, duration)
                            SpeechCue(chunk.startMs + start, chunk.startMs + end, text)
                        }
                    }
                    publishLiveSpeechWindow(handleGuard, isCurrent) {
                        mutable.update { current ->
                            current.copy(
                                cues = SpeechWindowPolicy.append(current.cues, cues),
                                inferenceMs = android.os.SystemClock.elapsedRealtime() - started,
                                latestText = cues.lastOrNull()?.text.orEmpty(),
                                revision = current.revision + 1,
                                processedWindows = current.processedWindows + 1,
                                status = if (cues.isEmpty())
                                    "Audio captured • no clear speech in the latest window"
                                else "English subtitles • offline • synced to playback"
                            )
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    publishLiveSpeechWindow(handleGuard, isCurrent) {
                        mutable.value = mutable.value.copy(status = e.message ?: "Speech inference failed")
                    }
                }
            }
        }
    }
    fun setEnabled(value: Boolean) {
        val replacingGeneratedTrack = value && mutable.value.generated
        if (value) detectedSourceLanguage = null
        synchronized(handleGuard) { enabled = value && !closed && handle != 0L }
        invalidate()
        mutable.value = mutable.value.copy(
            enabled = enabled,
            generated = false,
            cues = if (replacingGeneratedTrack) emptyList() else mutable.value.cues,
            latestText = "",
            capturedAudioMs = if (enabled) 0L else mutable.value.capturedAudioMs,
            processedWindows = if (enabled) 0 else mutable.value.processedWindows,
            lastAudioAtMs = if (enabled) 0L else mutable.value.lastAudioAtMs,
            status = if (enabled) "Waiting for decoded video audio…"
                else if (handle == 0L) "Install or import a multilingual Whisper model first."
                else "Live audio subtitles off"
        )
    }
    fun invalidate(clear: Boolean = false) {
        synchronized(handleGuard) {
            generation++
            if (clear) detectedSourceLanguage = null
            if (handle != 0L) native.cancel(handle)
        }
        while (chunks.tryReceive().isSuccess) { }
        mutable.value = mutable.value.copy(
            cues = if (clear) emptyList() else mutable.value.cues,
            generated = if (clear) false else mutable.value.generated,
            latestText = ""
        )
    }
    private fun markAudio(sampleCount16k: Int) {
        if (!enabled || sampleCount16k <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        mutable.update { current ->
            val nextMs = current.capturedAudioMs + sampleCount16k * 1000L / 16000L
            current.copy(
                capturedAudioMs = nextMs,
                lastAudioAtMs = now,
                status = when {
                    current.latestText.isNotBlank() -> current.status
                    current.processedWindows > 0 -> "Capturing audio • waiting for the next speech segment"
                    nextMs >= chunkSeconds * 1000L -> "Processing the first subtitle window…"
                    else -> "Capturing decoded video audio…"
                }
            )
        }
    }

    /** Accepts Web playback-capture audio already resampled to mono 16 kHz. */
    fun submitPcm16k(samples: FloatArray, startMs: Long) {
        if (enabled && samples.size <= 16000 * 12) {
            markAudio(samples.size)
            if (SpeechWindowPolicy.hasActivity(samples)) {
                queueWindow(samples.copyOf(), startMs)
            }
        }
    }
    private fun queueWindow(audio: FloatArray, startMs: Long, expectedGeneration: Long? = null) {
        val captured = windowSnapshots.capture() ?: return
        if (expectedGeneration != null && captured.generation != expectedGeneration) return
        if (chunks.trySend(Chunk(audio, startMs, captured)).isFailure) {
            publishLiveSpeechWindow(handleGuard, { windowSnapshots.isCurrent(captured) }) {
                mutable.update { it.copy(status = "Speech queue is saturated • try Balanced mode or a smaller model") }
            }
        }
    }

    suspend fun loadInstalled() = withContext(Dispatchers.IO) {
        if (model.exists()) {
            try { loadModel() }
            catch (e: CancellationException) { throw e }
            catch (paused: com.mangalens.core.compute.ResourcePausedException) { throw paused }
            catch (e: Exception) { mutable.value = mutable.value.copy(ready = false, status = e.message ?: "Cannot load speech model") }
        }
    }
    private suspend fun loadModel() = lock.withLock {
        if (closed) return@withLock
        withNativeWork(nativePriority()) {
            mutable.value = mutable.value.copy(ready = false)
            synchronized(handleGuard) {
                // The engine mutex retains the old handle until its actual native return.
                // Retire old PCM even if the allocator later recycles that handle address.
                generation++
                modelEpoch++
                detectedSourceLanguage = null
                if (handle != 0L) native.free(handle)
                handle = 0L
            }
            val loaded = native.load(model.absolutePath)
            synchronized(handleGuard) {
                if (closed) native.free(loaded)
                else {
                    handle = loaded
                    mutable.value = mutable.value.copy(ready = true, busy = false,
                        status = "Multilingual model ready. Turn on live English subtitles.")
                }
            }
        }
    }
    suspend fun installTiny() = install { temp ->
        val request = Request.Builder().url("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.bin").build()
        val call = client.newCall(request)
        val registration = currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "Model download failed (HTTP ${response.code})." }
                val body = response.body ?: error("Model download was empty")
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        total += n; check(total <= 77691713L) { "Model exceeds expected size" }
                        digest.update(buffer, 0, n); output.write(buffer, 0, n)
                        mutable.value = mutable.value.copy(status = "Downloading free speech model: ${total * 100 / 77691713L}%")
                    }
                    check(total == 77691713L) { "Incomplete speech model download" }
                } }
                check(digest.digest().joinToString("") { "%02x".format(it) } == "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21") { "Speech model checksum mismatch" }
            }
        } finally { registration.dispose() }
    }
    suspend fun importModel(uri: Uri) = install { temp ->
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    total += n; check(total <= 600_000_000L) { "Use tiny, base or small models (under 600 MB)." }
                    output.write(buffer, 0, n)
                }
            }
        } ?: error("Cannot read model")
    }
    private suspend fun install(write: suspend (File) -> Unit) = withContext(Dispatchers.IO) {
        check(!pinnedReadOnly) { "A captured microphone model is read-only. Use the ordinary speech model controls." }
        if (closed || mutable.value.busy) return@withContext
        setEnabled(false)
        mutable.value = mutable.value.copy(busy = true, status = "Preparing multilingual speech model…")
        model.parentFile!!.mkdirs()
        val temp = File(model.parentFile, "model.part")
        try {
            write(temp)
            require(temp.length() in 1000000L..600000000L) { "Use a complete multilingual GGML Whisper model." }
            val header = temp.inputStream().use { input -> ByteArray(48).also { require(input.read(it) == 48) } }
            val words = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(words.int == 0x67676d6c && words.int in 51865..51866 && words.int == 1500) { "This is not a supported multilingual GGML Whisper model." }
            val audioWidth = words.int; val audioHeads = words.int; val audioLayers = words.int
            val textContext = words.int; val textWidth = words.int; val textHeads = words.int; val textLayers = words.int
            val melBins = words.int
            require(audioWidth in listOf(384, 512, 768) && audioHeads == audioWidth / 64 && audioLayers in listOf(4, 6, 12) &&
                textContext == 448 && textWidth == audioWidth && textHeads == audioHeads && textLayers == audioLayers && melBins == 80) {
                "Use a multilingual tiny, base or small Whisper model."
            }
            // Validate by loading before replacing the previous working model.
            lock.withLock {
                check(!closed) { "Speech engine is closed" }
                withNativeWork(NativeComputeAdmission.Priority.INTERACTIVE) {
                    val probe = native.load(temp.absolutePath)
                    native.free(probe)
                    synchronized(handleGuard) {
                        check(!closed) { "Speech engine is closed" }
                        check(temp.renameTo(model)) { "Cannot install speech model" }
                        mutable.value = mutable.value.copy(ready = false)
                    }
                }
            }
            loadModel()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(status = e.message ?: "Model installation failed") }
        finally { temp.delete(); mutable.value = mutable.value.copy(busy = false) }
    }
    /**
     * Offline/full-video generation uses the same verified multilingual Whisper model but is not
     * tied to real-time playback. Chunks are decoded by FullVideoSubtitleGenerator and translated
     * directly to English here, so slow devices can still produce a complete timed subtitle file.
     */
    suspend fun inferEnglishChunk(
        samples: FloatArray,
        startMs: Long,
        sourceLanguage: String = language,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    ): List<SpeechCue> = inferChunk(samples, startMs, sourceLanguage, threads, translateToEnglish = true)

    /** Original-language ASR for durable source checkpoints and dual captions. */
    suspend fun inferOriginalChunk(
        samples: FloatArray,
        startMs: Long,
        sourceLanguage: String = language,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    ): List<SpeechCue> = inferChunk(samples, startMs, sourceLanguage, threads, translateToEnglish = false)

    private suspend fun inferChunk(samples: FloatArray, startMs: Long, sourceLanguage: String, threads: Int,
        translateToEnglish: Boolean): List<SpeechCue> = withContext(Dispatchers.Default) {
        require(samples.isNotEmpty() && samples.size <= 16000 * 30) { "Speech chunks must be 30 seconds or shorter." }
        val started = android.os.SystemClock.elapsedRealtime()
        val caller = currentCoroutineContext()
        val segments = lock.withLock {
            check(!closed && handle != 0L) { "Install or import a multilingual Whisper model first." }
            withNativeWork(NativeComputeAdmission.Priority.BACKGROUND) {
                // Original speech must detect each window independently; mixed-language media
                // cannot inherit the first window's language from the English/live path.
                val requestedLanguage = if (sourceLanguage == "auto" && translateToEnglish) detectedSourceLanguage ?: "auto" else sourceLanguage
                // A timeout/pause must abort native inference and wait for its actual return
                // before a handle can be freed. Whisper resets its abort bit on entry, so
                // repeat cancellation until completion also covers a queued native start.
                withContext(NonCancellable) {
                    val pending = async(Dispatchers.Default) {
                        caller.ensureActive()
                        // The independently scheduled entry must recheck its captured feature/model after waiting.
                        if (pinnedReadOnly) withContext(caller) { checkNativeComputePrecondition(waited = false) }
                        caller.ensureActive()
                        com.mangalens.core.compute.ResourceGovernorRuntime.shared.requireNativeEntry(com.mangalens.core.compute.ResourceWorkKind.BACKGROUND)
                        native.infer(handle, samples, requestedLanguage, translateToEnglish && requestedLanguage != "en", threads.coerceIn(1, 4))
                    }
                    try { withContext(caller) { pending.await() } }
                    catch (cancelled: CancellationException) {
                        while (!pending.isCompleted) {
                            synchronized(handleGuard) { if (handle != 0L) native.cancel(handle) }
                            delay(50)
                        }
                        runCatching { pending.await() }
                        throw cancelled
                    }
                }.also {
                    if ((!translateToEnglish || sourceLanguage == "auto" && detectedSourceLanguage == null) && handle != 0L) {
                        detectedSourceLanguage = runCatching { native.detectedLanguage(handle) }
                            .getOrNull()?.takeIf { detected -> detected.isNotBlank() && detected != "auto" }
                    }
                }
            }
        }
        caller.ensureActive()
        val duration = samples.size * 1000L / 16000
        val cues = segments.mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            val text = parts.getOrNull(2)?.trim().orEmpty()
            if (text.isBlank()) null else {
                val localStart = (parts[0].toLongOrNull() ?: 0L).coerceIn(0L, duration)
                val localEnd = (parts[1].toLongOrNull() ?: duration).coerceIn(localStart, duration)
                if (localEnd <= localStart) null else SpeechCue(startMs + localStart, startMs + localEnd, text)
            }
        }
        mutable.value = mutable.value.copy(
            inferenceMs = android.os.SystemClock.elapsedRealtime() - started,
            status = if (translateToEnglish) "Generating complete English subtitles…" else "Recognising original speech…"
        )
        cues
    }

    internal fun detectedLanguageSnapshot(): String? = detectedSourceLanguage

    /** Display a finished generated track without continuing live transcription in the background. */
    fun applyGeneratedCues(cues: List<SpeechCue>) = attachGenerated(SubtitleFormats.normalizeGenerated(cues), SubtitleOutputMode.TRANSLATED)
    fun applyGeneratedCues(cues: List<SpeechCue>, outputMode: SubtitleOutputMode) = attachGenerated(SubtitleAlignedTrack.retainTimings(cues), outputMode)
    private fun attachGenerated(cleaned: List<SpeechCue>, outputMode: SubtitleOutputMode) {
        synchronized(handleGuard) {
            enabled = false
            generation++
            if (handle != 0L) native.cancel(handle)
        }
        while (chunks.tryReceive().isSuccess) { }
        mutable.value = mutable.value.copy(
            enabled = false,
            generated = true,
            generatedOutputMode = outputMode,
            cues = cleaned,
            latestText = "",
            revision = mutable.value.revision + 1,
            status = "Generated subtitles active"
        )
    }

    suspend fun close() {
        synchronized(handleGuard) {
            if (closed) return
            closed = true
            enabled = false
            generation++
            if (handle != 0L) native.cancel(handle)
        }
        chunks.close()
        mutable.value = mutable.value.copy(enabled = false, generated = false, ready = false, latestText = "")
        withContext(NonCancellable + Dispatchers.IO) {
            lock.withLock {
                withNativeWork(NativeComputeAdmission.Priority.BACKGROUND, cleanup = true) {
                    synchronized(handleGuard) {
                        if (handle != 0L) native.free(handle)
                        handle = 0L
                    }
                }
            }
        }
        client.dispatcher.cancelAll()
    }

    private suspend fun nativePriority(): NativeComputeAdmission.Priority =
        if (currentCoroutineContext()[NativeComputePrecondition] == null) NativeComputeAdmission.Priority.INTERACTIVE
        else NativeComputeAdmission.Priority.BACKGROUND

    private suspend fun <T> withNativeWork(priority: NativeComputeAdmission.Priority, cleanup: Boolean = false,
        block: suspend () -> T): T {
        val caller = currentCoroutineContext()
        val lease = NativeComputeAdmission.shared.acquire(priority,
            if (cleanup) com.mangalens.core.compute.ResourceWorkKind.CLEANUP else when (priority) {
                NativeComputeAdmission.Priority.LIVE -> com.mangalens.core.compute.ResourceWorkKind.LIVE
                NativeComputeAdmission.Priority.INTERACTIVE -> com.mangalens.core.compute.ResourceWorkKind.INTERACTIVE
                NativeComputeAdmission.Priority.BACKGROUND -> com.mangalens.core.compute.ResourceWorkKind.BACKGROUND
            }) { cleanup || !closed }
            ?: throw CancellationException("Speech engine closed before native admission.")
        try {
            caller.ensureActive()
            if (!cleanup) checkNativeComputePrecondition(lease.waited)
            caller.ensureActive()
            return withContext(NonCancellable + Dispatchers.IO) {
                caller.ensureActive()
                com.mangalens.core.compute.ResourceGovernorRuntime.shared.requireNativeEntry(
                    if (cleanup) com.mangalens.core.compute.ResourceWorkKind.CLEANUP else when (priority) {
                        NativeComputeAdmission.Priority.LIVE -> com.mangalens.core.compute.ResourceWorkKind.LIVE
                        NativeComputeAdmission.Priority.INTERACTIVE -> com.mangalens.core.compute.ResourceWorkKind.INTERACTIVE
                        NativeComputeAdmission.Priority.BACKGROUND -> com.mangalens.core.compute.ResourceWorkKind.BACKGROUND
                    })
                block()
            }
        } finally { lease.close() }
    }
    fun srt(): String = state.value.cues.mapIndexed { i, cue ->
        "${i + 1}\n${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n${cue.text}\n"
    }.joinToString("\n")
    private fun timestamp(ms: Long): String {
        val t = ms.coerceAtLeast(0); return "%02d:%02d:%02d,%03d".format(t / 3600000, t / 60000 % 60, t / 1000 % 60, t % 1000)
    }
    private companion object {
        const val LIVE_INFERENCE_BUDGET_MS = 20_000L
    }

    inner class SpeechAudioProcessor : BaseAudioProcessor() {
        private var rate = 48000
        private var channels = 2
        private var samples = FloatArray(16000 * 12)
        private var used = 0
        private var phase = 0L
        private var accumulated = 0f
        private var accumulatedFrames = 0
        private var meterSamples = 0
        private var start = 0L
        private var epoch = -1L
        override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
            rate = inputAudioFormat.sampleRate; channels = inputAudioFormat.channelCount
            return inputAudioFormat
        }
        override fun queueInput(inputBuffer: ByteBuffer) {
            // Media3 drains processors with EMPTY_BUFFER. BaseAudioProcessor reuses that
            // same zero-capacity buffer, so attempting to put it into itself throws.
            if (!inputBuffer.hasRemaining()) return
            val copy = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (enabled) {
                if (epoch != generation) { epoch = generation; used = 0; phase = 0; accumulated = 0f; accumulatedFrames = 0; start = positionMs }
                while (copy.remaining() >= channels * 2) {
                    var mono = 0f
                    repeat(channels) { mono += copy.short.toFloat() / 32768f }
                    mono /= channels
                    accumulated += mono
                    accumulatedFrames++
                    phase += 16000
                    while (phase >= rate) {
                        phase -= rate
                        samples[used++] = if (accumulatedFrames > 0) accumulated / accumulatedFrames else mono
                        meterSamples++
                        if (meterSamples >= 8000) {
                            markAudio(meterSamples)
                            meterSamples = 0
                        }
                        accumulated = 0f; accumulatedFrames = 0
                        if (used >= 16000 * chunkSeconds.coerceIn(3, 12)) sendChunk()
                    }
                }
            }
            val out = replaceOutputBuffer(inputBuffer.remaining()); out.put(inputBuffer); out.flip()
        }
        private fun sendChunk(overlap: Boolean = true) {
            if (SpeechWindowPolicy.hasActivity(samples.copyOf(used))) {
                queueWindow(samples.copyOf(used), start, epoch)
            }
            // Keep roughly one second of real speech context between windows.
            // 500 ms was too easy to cut words at boundaries; a full second is still
            // bounded enough that low-latency mode does not spend most of its time
            // retranscribing the same audio.
            val retained = if (overlap) minOf(16000, used / 3) else 0
            start += (used - retained) * 1000L / 16000
            if (retained > 0) samples.copyInto(samples, 0, used - retained, used)
            used = retained
        }
        override fun onQueueEndOfStream() {
            if (meterSamples > 0) { markAudio(meterSamples); meterSamples = 0 }
            if (enabled && used > 8000) sendChunk(overlap = false)
        }
        override fun onFlush() { used = 0; phase = 0; accumulated = 0f; accumulatedFrames = 0; meterSamples = 0; epoch = -1 }
        override fun onReset() { onFlush() }
    }
}
