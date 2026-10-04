package com.mangalens.ui.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mangalens.whisper.WhisperNative
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

 data class SpeechCue(val startMs: Long, val endMs: Long, val text: String)
 data class SpeechState(val enabled: Boolean = false, val ready: Boolean = false, val busy: Boolean = false,
    val status: String = "Install a multilingual speech model to generate English subtitles from video audio.",
    val inferenceMs: Long = 0, val cues: List<SpeechCue> = emptyList(), val latestText: String = "", val revision: Long = 0)

/** Transcribes decoded media audio only; never opens the microphone. */
@OptIn(UnstableApi::class)
class VideoSpeechEngine(private val context: Context, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = mutable
    private val lock = Mutex()
    private val native by lazy { WhisperNative() }
    private val handleGuard = Any()
    @Volatile private var closed = false
    @Volatile private var handle = 0L
    @Volatile private var enabled = false
    @Volatile private var generation = 0L
    @Volatile var positionMs = 0L
    @Volatile var language = context.getSharedPreferences("live_audio_subtitles", Context.MODE_PRIVATE).getString("source", "auto") ?: "auto"
    @Volatile var chunkSeconds = context.getSharedPreferences("live_audio_subtitles", Context.MODE_PRIVATE).getInt("chunk_seconds", 6).coerceIn(3, 12)
    private data class Chunk(val audio: FloatArray, val startMs: Long, val generation: Long)
    private val chunks = Channel<Chunk>(capacity = 2)
    private val model = File(context.filesDir, "speech/whisper.bin")
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val processor = SpeechAudioProcessor()
    init {
        scope.launch(Dispatchers.Default) {
            for (chunk in chunks) {
                if (closed || !enabled || chunk.generation != generation) continue
                try {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val segments = lock.withLock {
                        if (!enabled || chunk.generation != generation || handle == 0L) emptyArray()
                        else native.infer(handle, chunk.audio, language, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                    }
                    if (closed || !enabled || chunk.generation != generation) continue
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
                    mutable.value = mutable.value.copy(cues = SpeechWindowPolicy.append(mutable.value.cues, cues),
                        inferenceMs = android.os.SystemClock.elapsedRealtime() - started, latestText = cues.lastOrNull()?.text.orEmpty(), revision = mutable.value.revision + 1,
                        status = if (cues.isEmpty()) "Listening to video audio…" else "English subtitles • offline • short processing delay")
                } catch (e: Exception) { mutable.value = mutable.value.copy(status = e.message ?: "Speech inference failed") }
            }
        }
    }
    fun setEnabled(value: Boolean) {
        synchronized(handleGuard) { enabled = value && !closed && handle != 0L }
        invalidate()
        mutable.value = mutable.value.copy(enabled = enabled, latestText = "", status =
            if (enabled) "Listening to video audio…" else if (handle == 0L) "Install or import a multilingual Whisper model first." else "Live audio subtitles off")
    }
    fun invalidate(clear: Boolean = false) {
        synchronized(handleGuard) {
            generation++
            if (handle != 0L) native.cancel(handle)
        }
        while (chunks.tryReceive().isSuccess) { }
        mutable.value = mutable.value.copy(cues = if (clear) emptyList() else mutable.value.cues, latestText = "")
    }
    /** Accepts Web playback-capture audio already resampled to mono 16 kHz. */
    fun submitPcm16k(samples: FloatArray, startMs: Long) {
        if (enabled && samples.size <= 16000 * 12 && SpeechWindowPolicy.hasActivity(samples)) {
            chunks.trySend(Chunk(samples.copyOf(), startMs, generation))
        }
    }
    suspend fun loadInstalled() = withContext(Dispatchers.IO) {
        if (model.exists()) {
            try { loadModel() }
            catch (e: Exception) { mutable.value = mutable.value.copy(ready = false, status = e.message ?: "Cannot load speech model") }
        }
    }
    private suspend fun loadModel() = lock.withLock {
        if (closed) return@withLock
        mutable.value = mutable.value.copy(ready = false)
        synchronized(handleGuard) {
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
                val probe = native.load(temp.absolutePath)
                native.free(probe)
                synchronized(handleGuard) {
                    check(!closed) { "Speech engine is closed" }
                    check(temp.renameTo(model)) { "Cannot install speech model" }
                }
            }
            loadModel()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(status = e.message ?: "Model installation failed") }
        finally { temp.delete(); mutable.value = mutable.value.copy(busy = false) }
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
        mutable.value = mutable.value.copy(enabled = false, ready = false, latestText = "")
        lock.withLock {
            synchronized(handleGuard) {
                if (handle != 0L) native.free(handle)
                handle = 0L
            }
        }
        client.dispatcher.cancelAll()
    }
    fun srt(): String = state.value.cues.mapIndexed { i, cue ->
        "${i + 1}\n${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n${cue.text}\n"
    }.joinToString("\n")
    private fun timestamp(ms: Long): String {
        val t = ms.coerceAtLeast(0); return "%02d:%02d:%02d,%03d".format(t / 3600000, t / 60000 % 60, t / 1000 % 60, t % 1000)
    }
    inner class SpeechAudioProcessor : BaseAudioProcessor() {
        private var rate = 48000
        private var channels = 2
        private var samples = FloatArray(16000 * 12)
        private var used = 0
        private var phase = 0L
        private var accumulated = 0f
        private var accumulatedFrames = 0
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
                        accumulated = 0f; accumulatedFrames = 0
                        if (used >= 16000 * chunkSeconds.coerceIn(3, 12)) sendChunk()
                    }
                }
            }
            val out = replaceOutputBuffer(inputBuffer.remaining()); out.put(inputBuffer); out.flip()
        }
        private fun sendChunk(overlap: Boolean = true) {
            if (SpeechWindowPolicy.hasActivity(samples.copyOf(used))) {
                if (chunks.trySend(Chunk(samples.copyOf(used), start, epoch)).isFailure)
                    mutable.value = mutable.value.copy(status = "Speech engine is behind playback. Use a smaller model or longer chunks.")
            }
            val retained = if (overlap) minOf(8000, used) else 0
            start += (used - retained) * 1000L / 16000
            if (retained > 0) samples.copyInto(samples, 0, used - retained, used)
            used = retained
        }
        override fun onQueueEndOfStream() { if (enabled && used > 8000) sendChunk(overlap = false) }
        override fun onFlush() { used = 0; phase = 0; accumulated = 0f; accumulatedFrames = 0; epoch = -1 }
        override fun onReset() { onFlush() }
    }
}
