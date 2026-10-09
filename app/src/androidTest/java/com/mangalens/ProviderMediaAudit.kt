package com.mangalens

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.mangalens.download.MediaResolutionRunner
import com.mangalens.download.MediaCompletenessPolicy
import com.mangalens.ui.video.SubtitleAudioDecoder
import com.mangalens.ui.video.SubtitleMediaSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max

/** Scans actual saved provider samples and decodes all audio; it accepts no metadata-only success. */
internal object ProviderMediaAudit {
    fun inspect(context: Context, uri: Uri, expectedDurationUs: Long?): JSONObject = runBlocking {
        MediaResolutionRunner.run(90_000L) { session ->
            val extractor = MediaExtractor()
            val tracks = JSONArray()
            var videoHeight = 0
            var hasAudio = false
            try {
                extractor.setDataSource(context, uri, emptyMap())
                for (index in 0 until extractor.trackCount) {
                    session.checkActive()
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                    val isVideo = mime.startsWith("video/")
                    if (isVideo) videoHeight = max(videoHeight, format.getInteger(MediaFormat.KEY_HEIGHT)) else hasAudio = true
                    val declaredUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                    val durationUs = max(declaredUs, expectedDurationUs ?: 0L)
                    check(durationUs > 0) { "Saved media duration cannot be verified" }
                    val maxSample = max(2 * 1024 * 1024, if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0)
                    check(maxSample <= 16 * 1024 * 1024) { "Provider sample exceeds bounded audit buffer" }
                    val buffer = ByteBuffer.allocateDirect(maxSample)
                    extractor.selectTrack(index)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var samples = 0L
                    var bytes = 0L
                    var firstUs = -1L
                    var lastUs = -1L
                    do {
                        session.checkActive(); buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size <= 0) break
                        if (firstUs < 0) firstUs = extractor.sampleTime
                        lastUs = max(lastUs, extractor.sampleTime)
                        samples++; bytes += size
                        check(samples <= 10_000_000) { "Provider track exceeds bounded audit sample count" }
                    } while (extractor.advance())
                    extractor.unselectTrack(index)
                    check(samples > 0 && bytes > 0) { "Saved provider track contains no media samples" }
                    check(firstUs <= 2_000_000L) { "Saved provider track is missing its beginning" }
                    check(MediaCompletenessPolicy.hasCompleteTail(durationUs, lastUs)) { "Saved provider track ends before the declared source tail" }
                    tracks.put(JSONObject().put("mime", mime).put("sample_count", samples).put("sample_bytes", bytes)
                        .put("declared_duration_us", declaredUs).put("expected_source_duration_us", expectedDurationUs ?: JSONObject.NULL)
                        .put("first_sample_us", firstUs).put("last_sample_us", lastUs)
                        .put("width", if (isVideo) format.getInteger(MediaFormat.KEY_WIDTH) else JSONObject.NULL)
                        .put("height", if (isVideo) format.getInteger(MediaFormat.KEY_HEIGHT) else JSONObject.NULL)
                        .put("sample_rate", if (!isVideo) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else JSONObject.NULL)
                        .put("channels", if (!isVideo) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else JSONObject.NULL))
                }
            } finally { extractor.release() }
            check(videoHeight > 0 && hasAudio) { "Saved source must contain actual video and audio" }
            val digest = MessageDigest.getInstance("SHA-256")
            var fileBytes = 0L
            context.contentResolver.openInputStream(uri)!!.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    session.checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count); fileBytes += count
                }
            }
            var pcmSamples = 0L
            var pcmEndMs = 0L
            var audioDurationMs = 0L
            var audibleSamples = 0L
            runBlocking {
                SubtitleAudioDecoder(context).decode(SubtitleMediaSource(uri.toString(), label = "Provider acceptance saved media")) { pcm, startMs, _, durationMs ->
                    session.checkActive()
                    pcmSamples += pcm.size
                    audibleSamples += pcm.count { it.isFinite() && kotlin.math.abs(it) > .001f }
                    pcmEndMs = max(pcmEndMs, startMs + pcm.size * 1000L / 16_000L)
                    audioDurationMs = max(audioDurationMs, durationMs)
                }
            }
            check(pcmSamples > 0 && pcmEndMs + 1_500 >= audioDurationMs) { "Saved audio did not decode through its tail" }
            check(audibleSamples > 0) { "Supplied provider fixture decoded no non-silent audio" }
            JSONObject().put("video_height", videoHeight).put("tracks", tracks)
                .put("file_bytes", fileBytes).put("file_sha256", digest.digest().joinToString("") { "%02x".format(it) })
                .put("decoded_pcm_samples_16k_with_overlap", pcmSamples).put("decoded_pcm_end_ms", pcmEndMs)
                .put("audio_duration_ms", audioDurationMs).put("non_silent_decoded_samples", audibleSamples)
                .put("speech_quality", "NOT_EVALUATED")
        }
    }
}
