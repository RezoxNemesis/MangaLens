package com.mangalens.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Remuxes documented platform codec/container pairs without recompression. */
internal object LocalMediaMuxer {
    fun mux(video: File, audio: File, output: File, plan: OriginalMuxPlan? = null, checkActive: () -> Unit = {}) {
        val videoInput = MediaExtractor()
        val audioInput = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoInput.setDataSource(video.absolutePath); audioInput.setDataSource(audio.absolutePath)
            val vi = (0 until videoInput.trackCount).firstOrNull { videoInput.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("Video track is missing")
            val ai = (0 until audioInput.trackCount).firstOrNull { audioInput.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("Audio track is missing")
            val vf = videoInput.getTrackFormat(vi); val af = audioInput.getTrackFormat(ai)
            val videoMime = vf.getString(MediaFormat.KEY_MIME).orEmpty()
            val audioMime = af.getString(MediaFormat.KEY_MIME).orEmpty()
            val selected = plan ?: OriginalMediaMuxPolicy.plan(videoMime, audioMime, android.os.Build.VERSION.SDK_INT)
            check(selected.backend == OriginalMuxBackend.ANDROID && selected.videoMime == videoMime && selected.audioMime == audioMime) {
                "This original codec pair requires copy-only native FFmpeg remuxing."
            }
            videoInput.selectTrack(vi); audioInput.selectTrack(ai)
            muxer = MediaMuxer(output.absolutePath, when (selected.container) {
                OriginalMediaContainer.MP4 -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                OriginalMediaContainer.WEBM -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                OriginalMediaContainer.MATROSKA -> error("Platform Matroska muxing is unavailable")
            })
            val vt = muxer.addTrack(vf); val at = muxer.addTrack(af)
            muxer.start()
            val maxSample = maxOf(2 * 1024 * 1024,
                if (vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0,
                if (af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0)
            check(maxSample <= 16 * 1024 * 1024) { "Media sample exceeds the safe muxing buffer." }
            val buffer = ByteBuffer.allocateDirect(maxSample)
            val info = MediaCodec.BufferInfo()
            val counts = longArrayOf(0L, 0L)
            while (videoInput.sampleTime >= 0L || audioInput.sampleTime >= 0L) {
                checkActive(); buffer.clear()
                val selectedInput = if (audioInput.sampleTime < 0L ||
                    videoInput.sampleTime >= 0L && videoInput.sampleTime <= audioInput.sampleTime) 0 else 1
                val input = if (selectedInput == 0) videoInput else audioInput
                val track = if (selectedInput == 0) vt else at
                val size = input.readSampleData(buffer, 0)
                check(size > 0) { "Original media contains an empty sample." }
                check(input.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Protected media cannot be remuxed." }
                info.set(0, size, input.sampleTime, if (input.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buffer, info)
                input.advance(); counts[selectedInput]++
            }
            check(counts.all { it > 0L }) { "Empty track cannot be muxed." }
            muxer.stop()
        } catch (failure: Throwable) {
            output.delete(); throw failure
        } finally {
            runCatching { muxer?.release() }; videoInput.release(); audioInput.release()
        }
    }
    fun videoHeight(file: File, requireAudio: Boolean = false, expectedDurationUs: Long? = null, checkActive: () -> Unit = {}): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val videoIndex = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            check(videoIndex >= 0) { "Downloaded file contains no video track." }
            val video = formats[videoIndex]
            val audioIndex = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (requireAudio) check(audioIndex >= 0) { "Muxed output has no audio track." }
            // Track metadata alone also exists in an MP4 initialization segment. Verify
            // actual samples and the declared tail before publishing a completed download.
            for (track in listOfNotNull(videoIndex, audioIndex.takeIf { it >= 0 })) {
                checkActive()
                val format = formats[track]
                val declaredDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                val duration = maxOf(declaredDuration, expectedDurationUs ?: 0L)
                val maxSample = maxOf(2 * 1024 * 1024, if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0)
                check(maxSample <= 16 * 1024 * 1024) { "Media sample exceeds the safe verification buffer." }
                val buffer = ByteBuffer.allocateDirect(maxSample)
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                check(extractor.readSampleData(buffer, 0) > 0) { "Downloaded media contains an empty track." }
                if (duration > 0L) {
                    val tolerance = MediaCompletenessPolicy.tailToleranceUs(duration)
                    extractor.seekTo((duration - tolerance).coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var lastSampleUs = -1L
                    do {
                        checkActive(); buffer.clear()
                        if (extractor.readSampleData(buffer, 0) <= 0) break
                        lastSampleUs = maxOf(lastSampleUs, extractor.sampleTime)
                    } while (extractor.advance())
                    check(MediaCompletenessPolicy.hasCompleteTail(duration, lastSampleUs)) {
                        "Downloaded media ends before its declared duration. Retry the source instead of publishing a partial file."
                    }
                }
                extractor.unselectTrack(track)
            }
            return video.getInteger(MediaFormat.KEY_HEIGHT).also { check(it > 0) }
        } finally { extractor.release() }
    }
}

