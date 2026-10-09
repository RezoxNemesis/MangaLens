package com.mangalens.ui.video

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.TranslationQualityPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Actual worker, native ASR and on-device translation. Provider/Hindi-source fixtures remain separate. */
@RunWith(AndroidJUnit4::class)
class SubtitleTargetReferenceAcceptanceTest {
    @Test fun pinnedOriginalSpeechProducesVerifiedHindiAndHinglishDualTracks(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val audio = File(requireNotNull(arguments.getString("reference_audio_path")) { "Stage the pinned JFK reference audio" })
        val model = File(requireNotNull(arguments.getString("whisper_model_path")) { "Stage the pinned multilingual Whisper model" })
        val directory = File(context.filesDir, "mangalens-qa/subtitle-target-reference").apply { mkdirs() }
        val record = JSONObject().put("status", "failed").put("reference", REFERENCE)
            .put("scope", "Real bounded-window original English ASR and actual Hindi/Hinglish translation; this does not prove Hindi-source ASR, live latency or YouTube/Instagram acceptance.")
        val store = SubtitleGenerationStore.shared(context)
        var accepted: SubtitleGenerationTask? = null
        var primaryFailure: Throwable? = null
        try {
            assertEquals(352078L, audio.length())
            assertEquals(AUDIO_SHA, SubtitleGenerationStore.fileHash(audio))
            assertEquals(77691713L, model.length())
            assertEquals(MODEL_SHA, SubtitleGenerationStore.fileHash(model))
            record.put("audio_sha256", AUDIO_SHA).put("model_sha256", MODEL_SHA)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val importer = VideoSpeechEngine(context, scope)
            try {
                importer.importModel(Uri.fromFile(model))
                assertTrue(importer.state.value.status, importer.state.value.ready)
            } finally { try { importer.close() } finally { scope.cancel() } }

            val source = SubtitleMediaSource(Uri.fromFile(audio).toString(), cacheKey = "qa:subtitle-target:$AUDIO_SHA", label = "Pinned JFK reference")
            val identity = SubtitleInputs.capture(context, source)
            assertTrue(hasSubtitleSourceProof(identity))
            val hindiConfig = SubtitleInputs.config(context, "en", SubtitleTargetOptions("hi", SubtitleOutputMode.DUAL)).copy(threads = 2)
            assertEquals(MODEL_SHA, hindiConfig.modelSha256)
            val started = SystemClock.elapsedRealtime()
            accepted = SubtitleGenerationJobs.start(context, identity, hindiConfig, force = true)
            val hindi = withTimeout(720_000) {
                store.states.first { tasks -> tasks.any { it.id == accepted!!.id && it.generation == accepted!!.generation && it.status in TERMINAL } }
                    .first { it.id == accepted!!.id && it.generation == accepted!!.generation }
            }
            record.put("hindi_runtime_ms", SystemClock.elapsedRealtime() - started).put("hindi", describe(hindi))
            assertEquals(hindi.error ?: "Hindi task did not complete", SubtitleGenerationStatus.COMPLETED, hindi.status)
            assertTrue(hindi.audioComplete)
            assertFalse(hindi.validationPending || hindi.pcmValidationRequired)
            assertEquals(0, hindi.pendingTargetCues)
            assertTrue(hindi.windows.size >= 2)
            val recognized = hindi.sourceCues.joinToString(" ") { it.text }
            val rate = wordErrors(words(REFERENCE), words(recognized)).toDouble() / words(REFERENCE).size
            record.put("recognized_original", recognized).put("source_word_error_rate", rate)
            assertTrue("Original source ASR is weak: $recognized (WER=$rate)", rate <= .15)
            assertTrue(hindi.sourceCues.last().endMs >= 9_000)
            assertTrue(hindi.sourceCues.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= 11_000 })
            val hindiText = targetText(hindi)
            assertTrue("Missing country meaning: $hindiText", hindiText.contains("देश") || hindiText.contains("राष्ट्र"))
            assertTrue("Lost negation: $hindiText", hindiText.contains("नहीं") || hindiText.contains("मत") || Regex("(^|\\s)न[\\s,।]").containsMatchIn(hindiText))
            assertTrue("Lost ask/do meaning: $hindiText", hindiText.contains("पूछ") && hindiText.contains("कर"))
            verifyTrack(hindi)
            val hindiExport = requireNotNull(store.exportVerified(hindi.id, hindi.generation)) { "No verified Hindi exports" }
            assertEquals(SubtitleFormats.srt(hindi.cues), File(hindiExport.srtPath!!).readText())
            assertEquals(SubtitleFormats.vtt(hindi.cues), File(hindiExport.vttPath!!).readText())

            // A new target may reuse only this exact proof-complete original-ASR model/source scope.
            val hinglishConfig = hindiConfig.withTarget(SubtitleTargetOptions("hi-latn", SubtitleOutputMode.DUAL, style = "faithful"))
            accepted = SubtitleGenerationJobs.start(context, identity, hinglishConfig, force = false)
            assertTrue("Retargeting discarded a verified complete original track", accepted!!.audioComplete)
            assertEquals(hindi.windows.map { it.copy(translations = emptyList()) }, accepted!!.windows.map { it.copy(translations = emptyList()) })
            val hinglish = withTimeout(360_000) {
                store.states.first { tasks -> tasks.any { it.id == accepted!!.id && it.generation == accepted!!.generation && it.status in TERMINAL } }
                    .first { it.id == accepted!!.id && it.generation == accepted!!.generation }
            }
            record.put("hinglish", describe(hinglish))
            assertEquals(hinglish.error ?: "Hinglish task did not complete", SubtitleGenerationStatus.COMPLETED, hinglish.status)
            assertEquals(hindi.sourceCues, hinglish.sourceCues)
            assertEquals(0, hinglish.pendingTargetCues)
            val hinglishText = targetText(hinglish).lowercase(Locale.ROOT)
            assertFalse("Hinglish track contains untranslated Hindi script", hinglishText.any { it in '\u0900'..'\u097f' && it.isLetter() })
            assertTrue("Missing country meaning: $hinglishText", listOf("desh", "rashtr", "raashtr", "country").any(hinglishText::contains))
            assertTrue("Lost negation: $hinglishText", Regex("\\b(nahin|nahi|na|mat)\\b").containsMatchIn(hinglishText))
            assertTrue("Lost ask/do meaning: $hinglishText", listOf("puch", "pooch").any(hinglishText::contains) && hinglishText.contains("kar"))
            assertTrue("Hinglish outputs need their independently retained Hindi drafts",
                hinglish.windows.flatMap { it.translations }.all { !it.hindiDraft.isNullOrBlank() })
            verifyTrack(hinglish)
            val hinglishExport = requireNotNull(store.exportVerified(hinglish.id, hinglish.generation))
            File(directory, "hindi-dual.srt").writeText(File(hindiExport.srtPath!!).readText())
            File(directory, "hinglish-dual.srt").writeText(File(hinglishExport.srtPath!!).readText())
            File(directory, "hinglish-dual.vtt").writeText(File(hinglishExport.vttPath!!).readText())

            val restored = SubtitleGenerationStore(File(context.filesDir, "subtitle_jobs"))
            assertTrue(restored.get(hinglish.id)!!.cues.isEmpty())
            val fresh = SubtitleInputs.capture(context, source)
            assertTrue(canTrustSubtitleSource(hinglish.source, fresh))
            restored.confirmValidated(hinglish.id, hinglish.generation)
            val restoredExport = requireNotNull(restored.exportVerified(hinglish.id, hinglish.generation))
            assertEquals(hinglish.cues, restoredExport.cues)
            assertEquals(hinglish.sourceCues, restoredExport.sourceCues)
            val displayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val display = VideoSpeechEngine(context, displayScope)
            try {
                display.applyGeneratedCues(restoredExport.cues, SubtitleOutputMode.DUAL)
                assertEquals(restoredExport.cues, display.state.value.cues)
                assertEquals(SubtitleOutputMode.DUAL, display.state.value.generatedOutputMode)
            } finally { try { display.close() } finally { displayScope.cancel() } }
            record.put("status", "passed")
        } catch (failure: Throwable) {
            primaryFailure = failure
            record.put("failure", failure.toString())
            accepted?.let { store.get(it.id)?.takeIf { current -> current.generation == it.generation }?.let { current -> record.put("last_native", describe(current)) } }
            throw failure
        } finally {
            accepted?.let { task ->
                try { if (store.current(task.id, task.generation)) SubtitleGenerationJobs.cancel(context, task.id, task.generation) }
                catch (failure: Throwable) { primaryFailure?.addSuppressed(failure) ?: throw failure }
            }
            File(directory, "outputs.json").writeText(record.toString(2))
        }
    }

    private fun verifyTrack(task: SubtitleGenerationTask) {
        task.windows.forEach { window -> window.translations.forEach { target ->
            assertTrue(TranslationQualityPolicy.isUsable(window.sourceCues[target.sourceIndex].text, target.text,
                task.config.targetLanguage, target.hindiDraft))
        } }
        assertEquals(task.sourceCues.map { it.startMs to it.endMs }, task.cues.map { it.startMs to it.endMs })
        SubtitleAlignedTrack.pairs(task.windows).zip(task.cues).forEach { (pair, rendered) ->
            assertEquals(pair.original.text + if (pair.original.text == pair.target) "" else "\n" + pair.target, rendered.text)
        }
    }

    private fun targetText(task: SubtitleGenerationTask) = SubtitleAlignedTrack.pairs(task.windows).joinToString(" ") { it.target.orEmpty() }
    private fun describe(task: SubtitleGenerationTask) = JSONObject().put("id", task.id).put("generation", task.generation)
        .put("status", task.status.name).put("error", task.error).put("config_sha256", task.config.fingerprint())
        .put("audio_complete", task.audioComplete).put("source_cues", JSONArray().apply { task.sourceCues.forEach { put(it.text) } })
        .put("windows", JSONArray().apply { task.windows.forEach { window -> put(JSONObject().put("index", window.index)
            .put("start_ms", window.startMs).put("end_ms", window.endMs).put("pcm_sha256", window.pcmSha256)
            .put("source_language", window.detectedLanguage).put("original", JSONArray().apply { window.sourceCues.forEach { put(it.text) } })
            .put("targets", JSONArray().apply { window.translations.forEach { target -> put(JSONObject().put("source_index", target.sourceIndex)
                .put("text", target.text).put("hindi_draft", target.hindiDraft)) } })) } })
    private fun words(text: String): List<String> = Regex("[a-z]+(?:'[a-z]+)?").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
    private fun wordErrors(expected: List<String>, actual: List<String>): Int {
        var previous = IntArray(actual.size + 1) { it }
        expected.forEachIndexed { index, word ->
            val row = IntArray(actual.size + 1); row[0] = index + 1
            actual.forEachIndexed { position, spoken -> row[position + 1] = minOf(row[position] + 1, previous[position + 1] + 1,
                previous[position] + if (word == spoken) 0 else 1) }
            previous = row
        }
        return previous.last()
    }

    companion object {
        private val TERMINAL = setOf(SubtitleGenerationStatus.COMPLETED, SubtitleGenerationStatus.PARTIAL, SubtitleGenerationStatus.FAILED, SubtitleGenerationStatus.CANCELLED)
        private const val REFERENCE = "And so my fellow Americans ask not what your country can do for you ask what you can do for your country"
        private const val AUDIO_SHA = "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e"
        private const val MODEL_SHA = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
    }
}
