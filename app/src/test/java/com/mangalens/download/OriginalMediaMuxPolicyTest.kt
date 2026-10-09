package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

class OriginalMediaMuxPolicyTest {
    @Test fun api28PreservesVp9OpusWithCopyOnlyWebmRatherThanLowerMp4() {
        val plan = OriginalMediaMuxPolicy.plan("video/x-vnd.on2.vp9", "audio/opus", 28)
        assertEquals(OriginalMediaContainer.WEBM, plan.container)
        assertEquals(OriginalMuxBackend.FFMPEG, plan.backend)
    }

    @Test fun supportedNativeContainersFollowDocumentedCodecAndSdkLimits() {
        assertEquals(OriginalMuxBackend.ANDROID, OriginalMediaMuxPolicy.plan("video/x-vnd.on2.vp9", "audio/opus", 29).backend)
        assertEquals(OriginalMuxBackend.ANDROID, OriginalMediaMuxPolicy.plan("video/x-vnd.on2.vp8", "audio/vorbis", 26).backend)
        assertEquals(OriginalMuxBackend.ANDROID, OriginalMediaMuxPolicy.plan("video/avc", "audio/mp4a-latm", 26).backend)
        assertEquals(OriginalMuxBackend.ANDROID, OriginalMediaMuxPolicy.plan("video/hevc", "audio/mp4a-latm", 26).backend)
        assertEquals(OriginalMuxBackend.FFMPEG, OriginalMediaMuxPolicy.plan("video/av01", "audio/mp4a-latm", 33).backend)
        assertEquals(OriginalMuxBackend.ANDROID, OriginalMediaMuxPolicy.plan("video/av01", "audio/mp4a-latm", 34).backend)
    }

    @Test fun crossContainerPairsUseMatroskaWithoutChangingEitherCodec() {
        for ((video, audio) in listOf("video/avc" to "audio/opus", "video/x-vnd.on2.vp9" to "audio/mp4a-latm")) {
            val plan = OriginalMediaMuxPolicy.plan(video, audio, 35)
            assertEquals(OriginalMediaContainer.MATROSKA, plan.container)
            assertEquals(OriginalMuxBackend.FFMPEG, plan.backend)
            assertEquals(video, plan.videoMime)
            assertEquals(audio, plan.audioMime)
        }
        assertEquals("mkv", OriginalMediaContainer.MATROSKA.extension)
        assertEquals("video/x-matroska", OriginalMediaContainer.MATROSKA.mime)
    }

    @Test fun copyCommandCannotEncodeOrReadNetworkInputs() {
        val plan = OriginalMediaMuxPolicy.plan("video/x-vnd.on2.vp9", "audio/opus", 28)
        val args = OriginalMediaMuxPolicy.copyArguments("/private/video.part", "/private/audio.part", "/private/output.webm", plan)
        assertEquals(listOf("copy"), args.indices.filter { args[it] == "-c" }.map { args[it + 1] })
        assertEquals(listOf("0:v:0", "1:a:0"), args.indices.filter { args[it] == "-map" }.map { args[it + 1] })
        assertEquals(2, args.count { it == "-protocol_whitelist" })
        assertFalse(args.any { it.startsWith("http") || it in setOf("-vf", "-af", "-filter_complex", "-b:v", "-b:a") })
        assertEquals("webm", args[args.lastIndexOf("-f") + 1])
    }

    @Test fun unsupportedCodecPairFailsWithoutAQualityFallback() {
        assertTrue(runCatching { OriginalMediaMuxPolicy.plan("video/arbitrary", "audio/arbitrary", 35) }.isFailure)
    }

    @Test fun selectorIncludesCombinedVideoAndNeverEscapesAnExplicitCeiling() {
        assertEquals("bestvideo*+bestaudio/best", OriginalMediaFormatPolicy.selector(DownloadQuality.BEST, true))
        assertEquals("bestvideo*[height<=1080]+bestaudio/best[height<=1080]", OriginalMediaFormatPolicy.selector(DownloadQuality.P1080, true))
        assertEquals("best[height<=720]", OriginalMediaFormatPolicy.selector(DownloadQuality.P720, false))
    }
}
