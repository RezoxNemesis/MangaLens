package com.mangalens.ui.video

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class SubtitleMediaSource(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val cacheKey: String = uri,
    val label: String = "Video"
)

data class FullSubtitleState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "Ready",
    val cues: List<SpeechCue> = emptyList(),
    val srt: String = "",
    val outputFile: File? = null,
    val cached: Boolean = false,
    val error: String? = null
)

/**
 * Complete-video fallback for devices that cannot keep Whisper comfortably ahead of playback.
 *
 * Audio is decoded with Android MediaExtractor/MediaCodec, downmixed/resampled to 16 kHz mono,
 * processed through the exact same local multilingual Whisper engine as live captions, de-duplicated
 * with SpeechWindowPolicy, then cached as SRT. No microphone, cloud speech API or paid service is used.
 */
class FullVideoSubtitleGenerator(
    context: Context,
    private val engine: VideoSpeechEngine,
    private val scope: CoroutineScope
) {
    private val app = context.applicationContext
    private val mutable = MutableStateFlow(FullSubtitleState())
    val state: StateFlow<FullSubtitleState> = mutable
    private var job: Job? = null

    fun generate(source: SubtitleMediaSource, attachToPlayer: Boolean = true, force: Boolean = false) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val target = cacheFile(source)
            try {
                engine.setEnabled(false)
                if (!force && target.isFile && target.length() > 0L) {
                    val cachedText = target.readText()
                    val cachedCues = parseSrt(cachedText)
                    if (cachedCues.isNotEmpty()) {
                        mutable.value = FullSubtitleState(
                            progress = 1f,
                            stage = "Generated English subtitles ready",
                            cues = cachedCues,
                            srt = cachedText,
                            outputFile = target,
                            cached = true
                        )
                        if (attachToPlayer) engine.applyGeneratedCues(cachedCues)
                        return@launch
                    }
                }

                mutable.value = FullSubtitleState(running = true, progress = .01f, stage = "Opening video audio…")
                val all = mutableListOf<SpeechCue>()
                decode(source) { audio, startMs, mediaProgress ->
                    coroutineContext.ensureActive()
                    if (!SpeechWindowPolicy.hasActivity(audio)) {
                        mutable.value = mutable.value.copy(
                            progress = (.05f + mediaProgress * .85f).coerceIn(.05f, .90f),
                            stage = "Scanning speech…"
                        )
                        return@decode
                    }
                    mutable.value = mutable.value.copy(
                        progress = (.05f + mediaProgress * .85f).coerceIn(.05f, .90f),
                        stage = "Transcribing and translating speech to English…"
                    )
                    val cues = engine.inferEnglishChunk(audio, startMs)
                    val merged = SpeechWindowPolicy.append(all, cues)
                    all.clear()
                    all.addAll(merged)
                    mutable.value = mutable.value.copy(cues = all.toList())
                }

                coroutineContext.ensureActive()
                mutable.value = mutable.value.copy(progress = .94f, stage = "Building English SRT…")
                val cleaned = SpeechWindowPolicy.append(emptyList(), all)
                require(cleaned.isNotEmpty()) {
                    "No speech was recognised. Try a larger multilingual Whisper model or check the video's audio track."
                }
                val text = toSrt(cleaned)
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".part")
                temp.writeText(text)
                check(temp.renameTo(target)) { "Could not save generated subtitle file." }

                mutable.value = FullSubtitleState(
                    progress = 1f,
                    stage = "Generated English subtitles ready",
                    cues = cleaned,
                    srt = text,
                    outputFile = target,
                    cached = false
                )
                if (attachToPlayer) engine.applyGeneratedCues(cleaned)
            } catch (cancelled: CancellationException) {
                mutable.value = mutable.value.copy(running = false, stage = "Subtitle generation cancelled")
                throw cancelled
            } catch (failure: Throwable) {
                mutable.value = mutable.value.copy(
                    running = false,
                    stage = "Subtitle generation failed",
                    error = failure.message ?: "Unable to generate subtitles"
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun applyToPlayer() {
        val cues = mutable.value.cues
        if (cues.isNotEmpty()) engine.applyGeneratedCues(cues)
    }

    private suspend fun decode(
        source: SubtitleMediaSource,
        onChunk: suspend (FloatArray, Long, Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val uri = Uri.parse(source.uri)
            when (uri.scheme?.lowercase()) {
                "content", "android.resource", "file" -> extractor.setDataSource(app, uri, source.headers)
                else -> extractor.setDataSource(source.uri, source.headers)
            }

            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("This video has no decodable audio track.")

            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio codec is unknown.")
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else 1L

            extractor.selectTrack(audioTrack)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            val chunker = Pcm16kChunker()
            var inputDone = false
            var outputDone = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var lastPresentationUs = 0L

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: error("Audio decoder input buffer unavailable.")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                    }
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val output = decoder.getOutputBuffer(outputIndex)
                                ?: error("Audio decoder output buffer unavailable.")
                            val view = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            view.position(info.offset)
                            view.limit(info.offset + info.size)
                            lastPresentationUs = info.presentationTimeUs.coerceAtLeast(lastPresentationUs)
                            val chunks = chunker.consume(
                                view.slice().order(ByteOrder.LITTLE_ENDIAN),
                                sampleRate,
                                channels,
                                pcmEncoding,
                                info.presentationTimeUs / 1000L
                            )
                            val progress = (lastPresentationUs.toDouble() / durationUs.toDouble()).toFloat().coerceIn(0f, 1f)
                            for (chunk in chunks) onChunk(chunk.samples, chunk.startMs, progress)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val trailing = chunker.flush()
            if (trailing != null) onChunk(trailing.samples, trailing.startMs, 1f)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun cacheFile(source: SubtitleMediaSource): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.cacheKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(app.filesDir, "subtitles/$digest.en.srt")
    }

    companion object {
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

        private fun parseTimestamp(value: String): Long? {
            val match = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})""").matchEntire(value) ?: return null
            val (h, m, s, ms) = match.destructured
            return h.toLongOrNull()?.times(3_600_000)
                ?.plus((m.toLongOrNull() ?: return null) * 60_000)
                ?.plus((s.toLongOrNull() ?: return null) * 1_000)
                ?.plus(ms.toLongOrNull() ?: return null)
        }
    }

    private data class DecodedChunk(val samples: FloatArray, val startMs: Long)

    private class Pcm16kChunker(
        private val chunkSamples: Int = 16_000 * 20,
        private val overlapSamples: Int = 16_000
    ) {
        private val samples = FloatArray(chunkSamples)
        private var used = 0
        private var phase = 0L
        private var startMs: Long? = null
        private var baseMs: Long? = null
        private var emittedSamples = 0L

        fun consume(
            buffer: ByteBuffer,
            sampleRate: Int,
            channels: Int,
            encoding: Int,
            presentationMs: Long
        ): List<DecodedChunk> {
            require(sampleRate in 8_000..192_000 && channels in 1..8) { "Unsupported decoded audio format." }
            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                "Unsupported decoded PCM format ($encoding)."
            }
            if (baseMs == null) baseMs = presentationMs.coerceAtLeast(0L)
            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val frameBytes = bytesPerSample * channels
            val ready = mutableListOf<DecodedChunk>()

            while (buffer.remaining() >= frameBytes) {
                var mono = 0f
                repeat(channels) {
                    mono += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        buffer.float.coerceIn(-1f, 1f)
                    } else {
                        buffer.short / 32768f
                    }
                }
                mono /= channels
                phase += 16_000
                while (phase >= sampleRate) {
                    phase -= sampleRate
                    if (used == 0 && startMs == null) {
                        startMs = (baseMs ?: 0L) + emittedSamples * 1000L / 16_000L
                    }
                    samples[used++] = mono
                    emittedSamples++
                    if (used >= chunkSamples) ready += emit(overlap = true)
                }
            }
            return ready
        }

        fun flush(): DecodedChunk? =
            if (used >= 1600) emit(overlap = false) else null

        private fun emit(overlap: Boolean): DecodedChunk {
            val count = used
            val result = DecodedChunk(samples.copyOf(count), startMs ?: 0L)
            val retained = if (overlap) minOf(overlapSamples, count / 4) else 0
            val nextStart = (startMs ?: 0L) + (count - retained) * 1000L / 16_000L
            if (retained > 0) samples.copyInto(samples, 0, count - retained, count)
            used = retained
            startMs = if (retained > 0) nextStart else null
            return result
        }
    }
}
