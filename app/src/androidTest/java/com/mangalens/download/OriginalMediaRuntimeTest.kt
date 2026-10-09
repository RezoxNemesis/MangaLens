package com.mangalens.download

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.BuildConfig
import com.mangalens.ProviderMediaAudit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real packaged tools and original encoded fixtures. These are not public-provider acceptance. */
@RunWith(AndroidJUnit4::class)
class OriginalMediaRuntimeTest {
    @Test(timeout = 360_000) fun higherWebmOpusSelectionSurvivesOriginalAssembly() = scenario("higher_webm_opus") {
        val resolved = SiteMediaInfoParser.parse("""{
          "duration":8,"extractor_key":"Youtube","requested_formats":[
            {"format_id":"vp9-high","url":"https://fixture.invalid/high","ext":"webm","height":1080,"vcodec":"vp9","acodec":"none"},
            {"format_id":"opus","url":"https://fixture.invalid/audio","ext":"webm","vcodec":"none","acodec":"opus"}],
          "formats":[{"height":720,"vcodec":"avc1"},{"height":1080,"vcodec":"vp9"}]
        }""", "https://fixture.invalid/source", true)!!
        assertEquals(1080, resolved.detectedHeight)
        val result = assemble("video-vp9-1080.webm", "audio-opus-8.webm", resolved.originalSelection)
        assertEquals("video/webm", result.mime)
        assertEquals("webm", result.file.extension)
        assertEquals(1080, result.media.video!!.height)
        assertEquals("vp9", result.media.video!!.codec)
        assertEquals("opus", result.media.audio!!.codec)
        if (Build.VERSION.SDK_INT < 29) assertEquals(OriginalMuxBackend.FFMPEG, result.backend)
        record(result)
    }

    @Test(timeout = 360_000) fun avcAacKeepsCodecDataAndEveryOriginalPacket() = scenario("avc_aac_original") {
        val result = assemble("video-h264-720.mp4", "audio-aac-8.m4a")
        assertEquals("video/mp4", result.mime)
        assertEquals("mp4", result.file.extension)
        assertEquals(720, result.media.video!!.height)
        assertEquals("h264", result.media.video!!.codec)
        assertEquals("aac", result.media.audio!!.codec)
        record(result)
    }

    @Test(timeout = 360_000) fun vp8VorbisUsesAValidOriginalWebmContainer() = scenario("vp8_vorbis_original") {
        val result = assemble("video-vp8-480.webm", "audio-vorbis-8.webm")
        assertEquals("video/webm", result.mime)
        assertEquals(480, result.media.video!!.height)
        assertEquals("vp8", result.media.video!!.codec)
        assertEquals("vorbis", result.media.audio!!.codec)
        record(result)
    }

    @Test(timeout = 360_000) fun crossCodecPairCopiesIntoMatroskaWithoutAudioConversion() = scenario("avc_opus_matroska") {
        val result = assemble("video-h264-720.mp4", "audio-opus-8.webm")
        assertEquals("video/x-matroska", result.mime)
        assertEquals("mkv", result.file.extension)
        assertEquals(OriginalMuxBackend.FFMPEG, result.backend)
        assertEquals("h264", result.media.video!!.codec)
        assertEquals("opus", result.media.audio!!.codec)
        record(result)
    }

    @Test(timeout = 360_000) fun shortAudioCannotPublishAFullVideoCompletion() = scenario("reject_short_audio") {
        val video = fixture("video-h264-720.mp4")
        val audio = fixture("audio-aac-short-tail-1.5.m4a")
        val base = OriginalMediaRemuxer.newAttemptBase(directory, "short-audio")
        try {
            val failure = runCatching { OriginalMediaRemuxer.assemble(context, video, audio, "video/mp4",
                base, 8_000_000, 720) }.exceptionOrNull()
            assertTrue("An incomplete original audio track must fail", failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("declared duration"))
            assertFalse(OriginalMediaContainer.entries.any { File(directory, "${base.name}.${it.extension}").exists() })
            evidence.put("rejected_before_publication", true).put("failure_category", "incomplete_audio_tail")
        } finally { OriginalMediaRemuxer.removeAttemptOutputs(base) }
    }

    @Test(timeout = 360_000) fun nearlyTwoSecondsMissingFromEightSecondAudioCannotPublish() = scenario("reject_near_audio_tail") {
        val video = fixture("video-h264-720.mp4")
        val audio = fixture("audio-aac-near-tail-6.04.m4a")
        val base = OriginalMediaRemuxer.newAttemptBase(directory, "near-tail-audio")
        try {
            val failure = runCatching { OriginalMediaRemuxer.assemble(context, video, audio, "video/mp4",
                base, 8_000_000, 720) }.exceptionOrNull()
            assertTrue("The former two-second tolerance accepted this 25-percent loss", failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("declared duration"))
            assertFalse(OriginalMediaContainer.entries.any { File(directory, "${base.name}.${it.extension}").exists() })
            evidence.put("rejected_before_publication", true).put("failure_category", "near_audio_tail_truncation")
        } finally { OriginalMediaRemuxer.removeAttemptOutputs(base) }
    }

    @Test(timeout = 360_000) fun unknownLegacyVideoOnlyFileRequiresSourceResolutionRatherThanSilentPublication() = scenario("reject_legacy_video_only") {
        val video = fixture("video-vp9-720.webm")
        val base = OriginalMediaRemuxer.newAttemptBase(directory, "legacy-video-only")
        try {
            val failure = runCatching { OriginalMediaRemuxer.assemble(context, video, null, "video/webm",
                base, 8_000_000, 720, requireCombinedAudio = true) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message.orEmpty().contains("Resolve the source again"))
            evidence.put("rejected_before_publication", true).put("failure_category", "unknown_legacy_audio_source")
        } finally { OriginalMediaRemuxer.removeAttemptOutputs(base) }
    }

    @Test(timeout = 360_000) fun substitutedLowerVideoCannotSatisfyTheSelectedHeight() = scenario("reject_lower_height") {
        val video = fixture("video-h264-720.mp4")
        val audio = fixture("audio-aac-8.m4a")
        val base = OriginalMediaRemuxer.newAttemptBase(directory, "lower-video")
        try {
            val failure = runCatching { OriginalMediaRemuxer.assemble(context, video, audio, "video/mp4",
                base, 8_000_000, 1080) }.exceptionOrNull()
            assertTrue("A substituted lower representation must fail", failure is IllegalArgumentException)
            assertTrue(failure!!.message.orEmpty().contains("selected original resolution"))
            assertFalse(OriginalMediaContainer.entries.any { File(directory, "${base.name}.${it.extension}").exists() })
            evidence.put("rejected_before_publication", true).put("failure_category", "different_selected_height")
        } finally { OriginalMediaRemuxer.removeAttemptOutputs(base) }
    }

    @Test(timeout = 360_000) fun intentionallySilentOriginalDoesNotInventAnAudioTrack() = scenario("silent_original") {
        val result = assemble("video-vp9-720.webm", null)
        assertNull(result.media.audio)
        val note = OriginalMediaCompletionPolicy.note(720, null, null, result.codecPlaybackSupported, false)
        assertTrue(note.contains("no audio track"))
        assertFalse(note.contains("both track tails"))
        record(result)
    }

    @Test(timeout = 360_000) fun savedAvcAacOriginalAudioDecodesThroughItsTail() = scenario("avc_aac_local_decode") {
        val result = assemble("video-h264-720.mp4", "audio-aac-8.m4a")
        val legacyBase = OriginalMediaRemuxer.newAttemptBase(directory, "legacy-whole-source")
        val legacy = OriginalMediaRemuxer.assemble(context, result.file, null, result.mime,
            legacyBase, 8_000_000, 720, requireCombinedAudio = true)
        assertEquals("A proven combined legacy file remains supported", result.file, legacy.file)
        assertNotNull(legacy.media.audio)
        val decoded = ProviderMediaAudit.inspect(context, Uri.fromFile(result.file), 8_000_000)
        assertTrue(decoded.getLong("decoded_pcm_samples_16k_with_overlap") > 0)
        assertTrue(decoded.getLong("non_silent_decoded_samples") > 0)
        assertTrue(decoded.getLong("decoded_pcm_end_ms") >= 7_500L)
        // This existing audit decodes audio and scans video samples; it does not decode video.
        evidence.put("android_audio_decode", decoded).put("video_decode", "NOT_EVALUATED")
        record(result)
    }

    private fun scenario(name: String, action: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(name)
        val started = SystemClock.elapsedRealtime()
        var passed = false
        var primaryFailure: Throwable? = null
        try {
            fixture.action()
            passed = true
        } catch (failure: Throwable) {
            primaryFailure = failure
            fixture.evidence.put("failure_class", failure.javaClass.name)
            throw failure
        } finally {
            fixture.evidence.put("status", if (passed) "PASS" else "FAIL")
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            try { fixture.saveEvidence() } catch (storageFailure: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(storageFailure) else throw storageFailure
            } finally { fixture.directory.deleteRecursively() }
        }
    }

    private class Fixture(private val name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "original-media-$name-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val evidence = JSONObject().put("scope", "synthetic_original_encoded_media")
            .put("case", name).put("source_sha", BuildConfig.SOURCE_SHA)
            .put("apk_version", BuildConfig.VERSION_NAME).put("sdk", Build.VERSION.SDK_INT)
            .put("public_provider_access", "NOT_EVALUATED").put("video_decode", "NOT_EVALUATED")
        private val pins = instrumentation.context.assets.open("original-media/fixture-pins.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONArray("fixtures") }

        fun fixture(fileName: String): File {
            require(fileName.matches(Regex("[a-z0-9.-]+")))
            val pin = (0 until pins.length()).map(pins::getJSONObject).single { it.getString("file") == fileName }
            return File(directory, fileName).apply {
                instrumentation.context.assets.open("original-media/$fileName").use { input ->
                    outputStream().use { output -> input.copyTo(output) }
                }
                assertEquals("Fixture length", pin.getLong("bytes"), length())
                assertEquals("Fixture pin", pin.getString("sha256"), sha256(this))
            }
        }

        suspend fun assemble(videoName: String, audioName: String?, selection: OriginalMediaSelection? = null): OriginalMediaPublication {
            val video = fixture(videoName)
            val audio = audioName?.let(::fixture)
            val sourceMime = if (videoName.endsWith("webm")) "video/webm" else "video/mp4"
            val base = OriginalMediaRemuxer.newAttemptBase(directory, "fixture")
            evidence.put("phase", "original_assembly")
            val result = OriginalMediaRemuxer.assemble(context, video, audio, sourceMime, base, 8_000_000,
                if (videoName.contains("1080")) 1080 else if (videoName.contains("720")) 720 else 480, selection)
            assertTrue(result.file.length() > 0)
            evidence.put("phase", "verified")
            return result
        }

        fun record(result: OriginalMediaPublication) {
            evidence.put("output_mime", result.mime).put("output_extension", result.file.extension)
                .put("output_sha256", sha256(result.file)).put("output_bytes", result.file.length())
                .put("mux_backend", result.backend?.name ?: "UNCHANGED_SOURCE")
                .put("codec_decoder_advertised", result.codecPlaybackSupported ?: JSONObject.NULL)
            evidence.put("verified_tracks", JSONArray().apply {
                result.media.tracks.forEach { track ->
                    val packets = result.media.packets.getValue(track.index)
                    put(JSONObject().put("codec", track.codec).put("mime", track.mime)
                        .put("height", track.height).put("width", track.width)
                        .put("codec_configuration_hash", track.codecConfigurationHash ?: JSONObject.NULL)
                        .put("samples", packets.samples).put("encoded_bytes", packets.encodedBytes)
                        .put("payload_fingerprint", packets.payloadFingerprint)
                        .put("first_sample_us", packets.firstSampleUs).put("last_sample_end_us", packets.lastSampleEndUs))
                }
            })
        }

        fun saveEvidence() {
            val target = File(context.filesDir, "mangalens-qa/original-media/$name.json")
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try { output.write(evidence.toString(2).toByteArray()); atomic.finishWrite(output) }
            catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        }
    }

    companion object {
        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
