package com.mangalens.ui.video

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaDataSource
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext



/** Bounded PCM decoder; no whole-video sample array is retained. */
internal class SubtitleAudioDecoder(context: Context) {
    private val app = context.applicationContext
    suspend fun decode(
        source: SubtitleMediaSource,
        expectedEtag: String? = null,
        expectedSize: Long? = null,
        expectedUrl: String? = null,
        onChunk: suspend (FloatArray, Long, Float, Long) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var network: SubtitleNetworkSource? = null
        try {
            val uri = Uri.parse(source.uri)
            when (uri.scheme?.lowercase()) {
                "content", "android.resource", "file" -> extractor.setDataSource(app, uri, source.headers)
                "http", "https" -> {
                    val reader = SubtitleNetworkSource(source, expectedEtag = expectedEtag, expectedSize = expectedSize, expectedUrl = expectedUrl)
                    network = reader
                    extractor.setDataSource(object : MediaDataSource() {
                        override fun getSize(): Long = reader.size()
                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = reader.readAt(position, buffer, offset, size)
                        override fun close() = reader.close()
                    })
                }
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
            require(durationUs / 1000 <= SubtitleGenerationStore.MAX_DURATION) { "Generate subtitles for videos up to six hours." }
            var decodedEndMs = 0L

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
            var lastDecoderActivity = android.os.SystemClock.elapsedRealtime()

            while (!outputDone) {
                coroutineContext.ensureActive()
                check(android.os.SystemClock.elapsedRealtime() - lastDecoderActivity < 30_000) { "Audio decoder stalled. Resume to retry its source." }
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
                            lastDecoderActivity = android.os.SystemClock.elapsedRealtime()
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
                        lastDecoderActivity = android.os.SystemClock.elapsedRealtime()
                        val ready = ArrayList<DecodedChunk>()
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
                            ready.addAll(chunks)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                        val progress = (lastPresentationUs.toDouble() / durationUs.toDouble()).toFloat().coerceIn(0f, 1f)
                        for (chunk in ready) {
                            decodedEndMs = chunk.startMs + chunk.samples.size * 1000L / 16000
                            onChunk(chunk.samples, chunk.startMs, progress, durationUs / 1000)
                        }
                        // Native transcription may legitimately take longer than decoder
                        // polling. It does not count as a MediaCodec stall.
                        lastDecoderActivity = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }

            val trailing = chunker.flush()
            if (trailing != null) {
                decodedEndMs = trailing.startMs + trailing.samples.size * 1000L / 16000
                onChunk(trailing.samples, trailing.startMs, 1f, durationUs / 1000)
            }
            require(durationUs <= 1 || decodedEndMs + 1500 >= durationUs / 1000) { "Audio ended before its advertised duration. Completed windows have been kept." }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            network?.close()
        }
    }

    internal data class DecodedChunk(val samples: FloatArray, val startMs: Long)

    internal class Pcm16kChunker(
        private val chunkSamples: Int = 16_000 * 8,
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
            val mediaMs = presentationMs.coerceAtLeast(0L)
            if (baseMs == null) baseMs = mediaMs
            else {
                val expectedMs = (baseMs ?: 0L) + emittedSamples * 1000L / 16_000L
                require(mediaMs - expectedMs in -100L..100L) {
                    "Audio timestamp discontinuity prevents reliable subtitle timing. Completed windows have been kept."
                }
            }
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
