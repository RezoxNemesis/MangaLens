package com.mangalens.download

import org.json.JSONObject
import java.io.File

internal data class OriginalMediaTrack(
    val index: Int, val mime: String, val codec: String,
    val width: Int = 0, val height: Int = 0, val durationUs: Long? = null,
    val sampleRate: Int = 0, val channels: Int = 0, val codecConfigurationHash: String? = null
)
internal data class VerifiedOriginalMedia(val tracks: List<OriginalMediaTrack>, val packets: Map<Int, EncodedTrackAudit>) {
    val video: OriginalMediaTrack? get() = tracks.firstOrNull { it.mime.startsWith("video/") }
    val audio: OriginalMediaTrack? get() = tracks.firstOrNull { it.mime.startsWith("audio/") }
}
internal enum class OriginalTrackSelection { VIDEO, AUDIO, VIDEO_AND_AUDIO }

internal object OriginalMediaProbe {
    private val inputOptions = listOf("-hide_banner", "-v", "error", "-protocol_whitelist", "file,pipe",
        "-format_whitelist", "mov,matroska,webm,ogg,aac,mp3,flac")

    fun inspect(runtime: NativeOriginalMediaInstallation, file: File, session: MediaResolutionSession,
                selection: OriginalTrackSelection = OriginalTrackSelection.VIDEO_AND_AUDIO,
                timingBase: File? = null): VerifiedOriginalMedia {
        require(file.isFile && file.length() > 0L) { "Original media input is empty." }
        val output = StringBuilder()
        NativeOriginalMediaRuntime.execute(runtime, NativeMediaTool.FFPROBE, inputOptions + listOf(
            "-show_data_hash", "sha256", "-show_entries", "stream=index,codec_type,codec_name,codec_tag_string,width,height,duration,sample_rate,channels,extradata_hash:format=duration", "-of", "json", file.absolutePath
        ), session) { line ->
            require(output.length + line.length < 1024 * 1024) { "Original track metadata exceeded its limit." }
            output.append(line).append('\n')
        }
        val json = JSONObject(output.toString())
        val rows = json.getJSONArray("streams")
        require(rows.length() in 1..128)
        val formatDuration = durationUs(json.optJSONObject("format")?.optString("duration"))
        val allRows = (0 until rows.length()).map(rows::getJSONObject)
        val allIndices = allRows.map { it.getInt("index") }.toSet()
        require(allIndices.size == rows.length() && allIndices.all { it in 0..127 })
        val selectedRows = listOfNotNull(
            if (selection != OriginalTrackSelection.AUDIO) allRows.firstOrNull { it.optString("codec_type") == "video" } else null,
            if (selection != OriginalTrackSelection.VIDEO) allRows.firstOrNull { it.optString("codec_type") == "audio" } else null
        )
        val tracks = selectedRows.map { track ->
            check(track.optString("codec_tag_string") !in setOf("encv", "enca")) { "Protected media cannot be remuxed or verified." }
            val codec = track.getString("codec_name")
            val mime = codecMime(codec) ?: error("This original codec cannot be verified without changing its representation.")
            OriginalMediaTrack(track.getInt("index"), mime, codec,
                track.optInt("width"), track.optInt("height"), durationUs(track.optString("duration")) ?: formatDuration,
                track.optInt("sample_rate"), track.optInt("channels"),
                track.optString("extradata_hash").takeIf { it.matches(Regex("SHA256:[0-9a-fA-F]{64}")) })
        }
        check(tracks.isNotEmpty()) { "Original media has no supported video or audio track." }
        val selectedIndices = tracks.map { it.index }.toSet()
        val receipts = timingBase?.let { base -> tracks.associate { track -> track.index to
            File(base.parentFile, "${base.name}.${if (track.mime.startsWith("video/")) "video" else "audio"}.timing") } }.orEmpty()
        session.checkActive()
        return OriginalPacketAudit(selectedIndices, allIndices - selectedIndices, receipts).use { audit ->
            NativeOriginalMediaRuntime.execute(runtime, NativeMediaTool.FFPROBE, inputOptions + listOf(
                "-show_packets", "-show_data_hash", "sha256", "-show_entries",
                "packet=stream_index,pts_time,duration_time,size,data_hash:packet_side_data=", "-of", "compact=p=0:nk=0", file.absolutePath
            ), session, audit::add)
            VerifiedOriginalMedia(tracks, audit.finish())
        }
    }

    fun verifyTail(media: VerifiedOriginalMedia, expectedDurationUs: Long?) {
        for (track in media.tracks) {
            val expected = maxOf(track.durationUs ?: 0L, expectedDurationUs ?: 0L)
            val samples = media.packets.getValue(track.index)
            // At most one bounded packet plus two WebM ticks, rather than the
            // adaptive downloader's two-second floor. Unknown long packets do
            // not authorize accepting a multi-second missing original tail.
            val tolerance = (samples.maximumSampleDurationUs.coerceIn(0L, 248_000L) + 2_000L)
                .coerceAtLeast(25_000L)
            check(samples.lastSampleEndUs >= 0L &&
                (expected <= 0L || samples.lastSampleEndUs >= expected - tolerance)) {
                "Original media ends before its declared duration. No partial file was published."
            }
        }
    }

    /** Nonzero fMP4 decode timestamps must not conceal a missing declared VOD span. */
    fun verifyObservedSpan(media: VerifiedOriginalMedia, track: OriginalMediaTrack, observedDurationUs: Long?) {
        if (observedDurationUs == null) return
        require(observedDurationUs in 1..OriginalFragmentPlan.MAX_DURATION_US)
        val samples = media.packets.getValue(track.index)
        val tolerance = (samples.maximumSampleDurationUs.coerceIn(0L, 248_000L) + 2_000L).coerceAtLeast(25_000L)
        val span = java.math.BigInteger.valueOf(samples.lastSampleEndUs).subtract(java.math.BigInteger.valueOf(samples.firstSampleUs))
        check(span.signum() > 0 && span.subtract(java.math.BigInteger.valueOf(observedDurationUs)).abs() <= java.math.BigInteger.valueOf(tolerance)) {
            "Original HLS samples differ from the observed playlist span. No partial file was published."
        }
    }

    fun sameOriginalTrack(source: VerifiedOriginalMedia, sourceTrack: OriginalMediaTrack,
                          output: VerifiedOriginalMedia, outputTrack: OriginalMediaTrack): Boolean =
        sourceTrack.mime == outputTrack.mime && sourceTrack.codec == outputTrack.codec &&
            (sourceTrack.codecConfigurationHash == null || sourceTrack.codecConfigurationHash == outputTrack.codecConfigurationHash) &&
            source.packets.getValue(sourceTrack.index).hasSamePayload(output.packets.getValue(outputTrack.index))

    internal fun retainsTrackTiming(video: EncodedTrackAudit, outputVideo: EncodedTrackAudit,
                                    audio: EncodedTrackAudit, outputAudio: EncodedTrackAudit,
                                    checkActive: () -> Unit = {}): Boolean {
        // WebM stores millisecond timestamps. A single shared rebase may round each
        // endpoint by one tick; it must not change the relative audio/video timing.
        fun near(a: Long, b: Long): Boolean = java.math.BigInteger.valueOf(a)
            .subtract(java.math.BigInteger.valueOf(b)).abs() <= java.math.BigInteger.valueOf(2_000L)
        return runCatching {
            val videoOffset = Math.subtractExact(outputVideo.firstSampleUs, video.firstSampleUs)
            val audioOffset = Math.subtractExact(outputAudio.firstSampleUs, audio.firstSampleUs)
            near(videoOffset, audioOffset) &&
                near(Math.subtractExact(outputVideo.lastSampleEndUs, video.lastSampleEndUs), videoOffset) &&
                near(Math.subtractExact(outputAudio.lastSampleEndUs, audio.lastSampleEndUs), audioOffset)
        }.getOrDefault(false)
            && OriginalPacketTiming.matches(video.timingReceipt, outputVideo.timingReceipt, video.samples, checkActive)
            && OriginalPacketTiming.matches(audio.timingReceipt, outputAudio.timingReceipt, audio.samples, checkActive)
    }

    fun codecMime(codec: String): String? = when (codec.lowercase()) {
        "h264", "avc", "avc1" -> "video/avc"
        "hevc", "h265" -> "video/hevc"
        "av1" -> "video/av01"
        "vp8" -> "video/x-vnd.on2.vp8"
        "vp9" -> "video/x-vnd.on2.vp9"
        "mpeg4" -> "video/mp4v-es"
        "h263" -> "video/3gpp"
        "mpeg2video" -> "video/mpeg2"
        "theora" -> "video/x-vnd.on2.theora"
        "aac" -> "audio/mp4a-latm"
        "opus" -> "audio/opus"
        "vorbis" -> "audio/vorbis"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ac3" -> "audio/ac3"
        "eac3" -> "audio/eac3"
        else -> null
    }

    private fun durationUs(value: String?): Long? = value?.toDoubleOrNull()?.takeIf {
        it.isFinite() && it > 0.0 && it < Long.MAX_VALUE / 1_000_000.0
    }?.let { (it * 1_000_000.0).toLong() }
}
