package com.mangalens.download

internal enum class OriginalMediaContainer(val extension: String, val mime: String, val ffmpegFormat: String) {
    MP4("mp4", "video/mp4", "mp4"),
    WEBM("webm", "video/webm", "webm"),
    MATROSKA("mkv", "video/x-matroska", "matroska")
}

internal enum class OriginalMuxBackend { ANDROID, FFMPEG }
internal data class OriginalMuxPlan(
    val container: OriginalMediaContainer,
    val backend: OriginalMuxBackend,
    val videoMime: String,
    val audioMime: String
)

/** Platform limits are documented by MediaMuxer; another container never means another codec. */
internal object OriginalMediaMuxPolicy {
    private val mp4Video = setOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp", "video/av01")
    private val webmVideo = setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01")
    private val otherVideo = setOf("video/mpeg2", "video/x-vnd.on2.theora")
    private val audio = setOf("audio/mp4a-latm", "audio/opus", "audio/vorbis", "audio/mpeg", "audio/flac", "audio/ac3", "audio/eac3")

    fun plan(videoMime: String, audioMime: String, sdk: Int): OriginalMuxPlan {
        require(videoMime in mp4Video + webmVideo + otherVideo && audioMime in audio) {
            "The original codec pair has no supported lossless container. No lower-quality substitute was selected."
        }
        val container = when {
            videoMime in mp4Video && audioMime == "audio/mp4a-latm" -> OriginalMediaContainer.MP4
            videoMime in webmVideo && audioMime in setOf("audio/opus", "audio/vorbis") -> OriginalMediaContainer.WEBM
            else -> OriginalMediaContainer.MATROSKA
        }
        val native = when (container) {
            OriginalMediaContainer.MP4 -> videoMime != "video/av01" || sdk >= 34
            OriginalMediaContainer.WEBM -> videoMime != "video/av01" && (audioMime != "audio/opus" || sdk >= 29)
            OriginalMediaContainer.MATROSKA -> false
        }
        return OriginalMuxPlan(container, if (native) OriginalMuxBackend.ANDROID else OriginalMuxBackend.FFMPEG, videoMime, audioMime)
    }

    fun copyArguments(video: String, audio: String, output: String, plan: OriginalMuxPlan): List<String> = listOf(
        "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-copyts",
        "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov,matroska,webm,ogg,aac,mp3,flac", "-i", video,
        "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov,matroska,webm,ogg,aac,mp3,flac", "-i", audio,
        "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-map_metadata", "-1", "-map_chapters", "-1",
        "-f", plan.container.ffmpegFormat, output
    )
}
