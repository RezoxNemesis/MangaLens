package com.mangalens.download

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

internal data class OriginalMediaPublication(
    val file: File, val mime: String, val media: VerifiedOriginalMedia,
    val backend: OriginalMuxBackend?, val codecPlaybackSupported: Boolean?
)

/** Bounded local processing; a failed original track never triggers selection of lower source media. */
internal object OriginalMediaRemuxer {
    fun newAttemptBase(directory: File, id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return File(directory, "$id.muxed-${UUID.randomUUID()}")
    }

    fun removeAttemptOutputs(base: File) {
        OriginalMediaContainer.entries.forEach { File(base.parentFile, "${base.name}.${it.extension}").delete() }
        removeTimingReceipts(base)
    }

    suspend fun assemble(context: Context, video: File, audio: File?, sourceMime: String,
                         outputBase: File, expectedDurationUs: Long?, selectedHeight: Int?,
                         selection: OriginalMediaSelection? = null,
                         requireCombinedAudio: Boolean = false,
                         observedVideoSpanUs: Long? = null, observedAudioSpanUs: Long? = null): OriginalMediaPublication = try {
        val ownerId = outputBase.name.substringBefore(".muxed-")
        require(ownerId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        MediaResolutionRunner.run(300_000L, DownloadPrivateFileOwner(requireNotNull(outputBase.parentFile), ownerId)) { session ->
            try {
                requirePrivate(context, video); audio?.let { requirePrivate(context, it) }
                requirePrivate(context, outputBase)
                require(outputBase.name.matches(Regex("[A-Za-z0-9_-]{1,100}\\.muxed-[a-f0-9-]{36}")))
                val runtime = NativeOriginalMediaRuntime.initialize(context, session)
                val source = OriginalMediaProbe.inspect(runtime, video, session,
                    if (audio == null) OriginalTrackSelection.VIDEO_AND_AUDIO else OriginalTrackSelection.VIDEO,
                    File(outputBase.parentFile, "${outputBase.name}.source-video"))
                val videoTrack = source.video ?: error("The original source contains no video track.")
                require(selectedHeight == null || selectedHeight == videoTrack.height) {
                    "Downloaded video differs from the selected original resolution. No lower-quality file was published."
                }
                require(selection?.videoCodec?.let(::selectedCodecMime)?.let { it == videoTrack.mime } != false) {
                    "Downloaded video codec differs from the selected original source."
                }
                OriginalMediaProbe.verifyTail(source, expectedDurationUs)
                OriginalMediaProbe.verifyObservedSpan(source, videoTrack, observedVideoSpanUs)
                if (audio == null) {
                    require(!requireCombinedAudio || source.audio != null) {
                        "Legacy media has no proven complete audio source. Resolve the source again; no silent substitute was published."
                    }
                    require(selection?.audioCodec == null || source.audio != null) { "The selected original audio track is missing." }
                    return@run OriginalMediaPublication(video, sourceMime, source.withoutTimingReceipts(), null, playbackSupported(source))
                }
                val sound = OriginalMediaProbe.inspect(runtime, audio, session, OriginalTrackSelection.AUDIO,
                    File(outputBase.parentFile, "${outputBase.name}.source-audio"))
                val audioTrack = sound.audio ?: error("The selected original audio source contains no audio track.")
                require(selection?.audioCodec?.let(::selectedCodecMime)?.let { it == audioTrack.mime } != false) {
                    "Downloaded audio codec differs from the selected original source."
                }
                OriginalMediaProbe.verifyTail(sound, expectedDurationUs)
                OriginalMediaProbe.verifyObservedSpan(sound, audioTrack, observedAudioSpanUs)
                val plan = OriginalMediaMuxPolicy.plan(videoTrack.mime, audioTrack.mime, Build.VERSION.SDK_INT)
                // A timed-out previous worker can only continue writing its own attempt's output.
                val output = File(outputBase.parentFile, "${outputBase.name}.${plan.container.extension}")
                try {
                    var backend = plan.backend
                    var verified: VerifiedOriginalMedia? = null
                    if (backend == OriginalMuxBackend.ANDROID) {
                        try {
                            LocalMediaMuxer.mux(video, audio, output, plan, session::checkActive)
                            verified = verifyOutput(runtime, output, source, videoTrack, sound, audioTrack, expectedDurationUs, session, outputBase, observedVideoSpanUs, observedAudioSpanUs)
                        } catch (failure: Exception) {
                            session.checkActive()
                            if (failure is InterruptedException) throw failure
                            if (session.privateFilesReleased()) output.delete()
                            backend = OriginalMuxBackend.FFMPEG
                        }
                    }
                    if (verified == null) {
                        NativeOriginalMediaRuntime.execute(runtime, NativeMediaTool.FFMPEG,
                            OriginalMediaMuxPolicy.copyArguments(video.absolutePath, audio.absolutePath, output.absolutePath, plan), session)
                        verified = verifyOutput(runtime, output, source, videoTrack, sound, audioTrack, expectedDurationUs, session, outputBase, observedVideoSpanUs, observedAudioSpanUs)
                    }
                    session.checkActive()
                    OriginalMediaPublication(output, plan.container.mime, verified.withoutTimingReceipts(), backend, playbackSupported(verified))
                } catch (failure: Throwable) {
                    if (session.privateFilesReleased()) output.delete()
                    throw failure
                }
            } finally {
                cleanupAfterProcessing(outputBase, session, rejected = session.isStopped())
            }
        }
    } catch (timeout: MediaResolutionTimeoutException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("Original media processing exceeded its five-minute limit. Retry processing; no lower-quality substitute was published.", timeout)
    }

    internal fun cleanupAfterProcessing(base: File, session: MediaResolutionSession, rejected: Boolean) {
        if (!session.privateFilesReleased()) return
        if (rejected) removeAttemptOutputs(base) else removeTimingReceipts(base)
    }

    private fun verifyOutput(runtime: NativeOriginalMediaInstallation, output: File, video: VerifiedOriginalMedia,
                             videoTrack: OriginalMediaTrack, audio: VerifiedOriginalMedia, audioTrack: OriginalMediaTrack,
                             expectedDurationUs: Long?, session: MediaResolutionSession, outputBase: File,
                             observedVideoSpanUs: Long?, observedAudioSpanUs: Long?): VerifiedOriginalMedia {
        val result = OriginalMediaProbe.inspect(runtime, output, session,
            timingBase = File(outputBase.parentFile, "${outputBase.name}.result"))
        val resultVideo = result.video ?: error("Remuxed output has no video track.")
        val resultAudio = result.audio ?: error("Remuxed output has no audio track.")
        check(videoTrack.height == resultVideo.height && videoTrack.width == resultVideo.width &&
            OriginalMediaProbe.sameOriginalTrack(video, videoTrack, result, resultVideo) &&
            OriginalMediaProbe.sameOriginalTrack(audio, audioTrack, result, resultAudio)) {
            "Remuxed output changed original encoded samples or codec data. No lower-quality file was published."
        }
        check(OriginalMediaProbe.retainsTrackTiming(video.packets.getValue(videoTrack.index),
            result.packets.getValue(resultVideo.index), audio.packets.getValue(audioTrack.index),
            result.packets.getValue(resultAudio.index), session::checkActive)) {
            "Remuxed output changed the original audio/video timing. No lower-quality file was published."
        }
        OriginalMediaProbe.verifyTail(result, expectedDurationUs)
        OriginalMediaProbe.verifyObservedSpan(result, resultVideo, observedVideoSpanUs)
        OriginalMediaProbe.verifyObservedSpan(result, resultAudio, observedAudioSpanUs)
        return result
    }

    private fun VerifiedOriginalMedia.withoutTimingReceipts() = copy(packets = packets.mapValues {
        it.value.copy(timingReceipt = null)
    })

    private fun removeTimingReceipts(base: File) {
        listOf("source-video.video", "source-video.audio", "source-audio.audio", "result.video", "result.audio")
            .forEach { File(base.parentFile, "${base.name}.$it.timing").delete() }
    }

    private fun requirePrivate(context: Context, file: File) {
        val path = file.canonicalFile.toPath()
        require(listOf(context.filesDir, context.cacheDir).any { path.startsWith(it.canonicalFile.toPath()) }) {
            "Original media processing requires an owned app-private file."
        }
    }

    private fun selectedCodecMime(codec: String): String? = when {
        codec.startsWith("avc", true) || codec.startsWith("h264", true) -> "video/avc"
        codec.startsWith("hev", true) || codec.startsWith("hvc", true) || codec.startsWith("h265", true) -> "video/hevc"
        codec.startsWith("av01", true) || codec.equals("av1", true) -> "video/av01"
        codec.startsWith("vp09", true) || codec.startsWith("vp9", true) -> "video/x-vnd.on2.vp9"
        codec.startsWith("vp08", true) || codec.startsWith("vp8", true) -> "video/x-vnd.on2.vp8"
        codec.startsWith("mp4a", true) || codec.equals("aac", true) -> "audio/mp4a-latm"
        else -> OriginalMediaProbe.codecMime(codec)
    }

    private fun playbackSupported(media: VerifiedOriginalMedia): Boolean? = runCatching {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
        media.tracks.all { track ->
            val format = if (track.mime.startsWith("video/")) MediaFormat.createVideoFormat(track.mime, track.width, track.height)
            else MediaFormat.createAudioFormat(track.mime, track.sampleRate, track.channels)
            codecs.findDecoderForFormat(format) != null
        }
    }.getOrNull()
}
