package com.mangalens.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Remuxes unprotected AVC/AAC tracks locally without recompression or a paid service. */
internal object LocalMediaMuxer {
    fun mux(video: File, audio: File, output: File, checkActive: () -> Unit = {}) {
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
            check(videoMime.startsWith("video/") && audioMime == "audio/mp4a-latm") {
                "Local MP4 muxing requires a supported video track and AAC audio."
            }
            videoInput.selectTrack(vi); audioInput.selectTrack(ai)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vt = muxer.addTrack(vf); val at = muxer.addTrack(af)
            muxer.start()
            val maxSample = maxOf(2 * 1024 * 1024, if (vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0)
            check(maxSample <= 16 * 1024 * 1024) { "Media sample exceeds the safe muxing buffer." }
            val buffer = ByteBuffer.allocateDirect(maxSample)
            val info = MediaCodec.BufferInfo()
            for ((input, track) in listOf(videoInput to vt, audioInput to at)) {
                var count = 0
                while (true) {
                    checkActive(); buffer.clear()
                    val size = input.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, input.sampleTime, if (input.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(track, buffer, info)
                    input.advance(); count++
                }
                check(count > 0) { "Empty track cannot be muxed." }
            }
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

