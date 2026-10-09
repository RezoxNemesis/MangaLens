package com.mangalens.ui.video

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Actual decoded audio and JNI inference; this reference does not replace provider acceptance. */
@RunWith(AndroidJUnit4::class)
class SpeechReferenceAcceptanceTest {
    @Test fun realReferenceAudioMeetsWordAccuracyAndProducesTimedExports() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val audio = File(requireNotNull(arguments.getString("reference_audio_path")) { "Stage the pinned JFK reference audio" })
        val model = File(requireNotNull(arguments.getString("whisper_model_path")) { "Stage the pinned multilingual Whisper model" })
        val directory = File(context.filesDir, "mangalens-qa/speech-reference").apply { mkdirs() }
        val record = JSONObject().put("scope", "Real decoder/native ASR baseline; provider playback, live lag and translated subtitle quality are separate.")
            .put("reference", REFERENCE).put("status", "failed")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = VideoSpeechEngine(context, scope)
        var primaryFailure: Throwable? = null
        try {
            assertEquals(352078L, audio.length())
            assertEquals(AUDIO_SHA, sha256(audio))
            assertEquals(77691713L, model.length())
            assertEquals(MODEL_SHA, sha256(model))
            record.put("audio_sha256", AUDIO_SHA).put("model_sha256", MODEL_SHA)
            val loadStart = SystemClock.elapsedRealtime()
            engine.importModel(Uri.fromFile(model))
            assertTrue(engine.state.value.status, engine.state.value.ready)
            record.put("model_load_ms", SystemClock.elapsedRealtime() - loadStart)

            // This fixed eleven-second baseline is bounded below the native 30s limit.
            // Decoder windows are stitched at their actual PTS; no microphone is opened.
            val samples = FloatArray(16000 * 11)
            var decodedEnd = 0
            val windows = JSONArray()
            withTimeout(90_000) {
                SubtitleAudioDecoder(context).decode(SubtitleMediaSource(Uri.fromFile(audio).toString())) { pcm, startMs, _, durationMs ->
                    val offset = (startMs * 16).toInt()
                    require(offset >= 0 && offset + pcm.size <= samples.size) { "Unexpected reference PCM timeline" }
                    pcm.copyInto(samples, offset)
                    decodedEnd = maxOf(decodedEnd, offset + pcm.size)
                    windows.put(JSONObject().put("start_ms", startMs).put("samples", pcm.size).put("duration_ms", durationMs))
                }
            }
            record.put("decoder_windows", windows).put("decoded_samples", decodedEnd)
            assertEquals("Decoder lost the end of the reference", samples.size, decodedEnd)
            val started = SystemClock.elapsedRealtime()
            val cues = withTimeout(300_000) { engine.inferEnglishChunk(samples, 0, "en") }
            record.put("inference_ms", SystemClock.elapsedRealtime() - started)
            record.put("cues", JSONArray().apply { cues.forEach { cue ->
                put(JSONObject().put("start_ms", cue.startMs).put("end_ms", cue.endMs).put("text", cue.text))
            } })
            val recognized = cues.joinToString(" ") { it.text }
            val referenceWords = words(REFERENCE)
            val errors = wordErrors(referenceWords, words(recognized))
            val rate = errors.toDouble() / referenceWords.size
            record.put("recognized", recognized).put("word_errors", errors).put("word_error_rate", rate)
            assertTrue("No recognized reference speech", cues.isNotEmpty())
            assertTrue("Reference word accuracy is weak: $recognized (WER=$rate)", rate <= .10)
            assertTrue(cues.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= 11_000 })
            assertTrue("Final spoken words have no timed cue", cues.last().endMs >= 9_000)
            engine.applyGeneratedCues(cues)
            val srt = engine.srt()
            val vtt = SubtitleFormats.vtt(cues)
            assertTrue(srt.contains(" --> ") && srt.contains(cues.last().text))
            assertTrue(vtt.startsWith("WEBVTT\n") && vtt.contains(cues.last().text))
            File(directory, "reference.srt").writeText(srt)
            File(directory, "reference.vtt").writeText(vtt)
            record.put("status", "passed")
        } catch (failure: Throwable) {
            primaryFailure = failure
            record.put("failure", failure.toString())
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            try { engine.close() } catch (failure: Throwable) {
                cleanupFailure = failure
                record.put("status", "failed").put("cleanup_failure", failure.toString())
                primaryFailure?.addSuppressed(failure)
            }
            scope.cancel()
            try { File(directory, "outputs.json").writeText(record.toString(2)) } catch (failure: Throwable) {
                val original = primaryFailure ?: cleanupFailure
                if (original != null) original.addSuppressed(failure) else cleanupFailure = failure
            }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun words(text: String): List<String> = Regex("[a-z]+(?:'[a-z]+)?")
        .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    private fun wordErrors(expected: List<String>, actual: List<String>): Int {
        var previous = IntArray(actual.size + 1) { it }
        expected.forEachIndexed { index, word ->
            val row = IntArray(actual.size + 1); row[0] = index + 1
            actual.forEachIndexed { position, spoken ->
                row[position + 1] = minOf(row[position] + 1, previous[position + 1] + 1,
                    previous[position] + if (word == spoken) 0 else 1)
            }
            previous = row
        }
        return previous.last()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val REFERENCE = "And so my fellow Americans ask not what your country can do for you ask what you can do for your country"
        private const val AUDIO_SHA = "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e"
        private const val MODEL_SHA = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
    }
}
