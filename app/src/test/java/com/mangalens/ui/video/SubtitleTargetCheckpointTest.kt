package com.mangalens.ui.video

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.mangalens.core.translation.HinglishTranslationOutput
import com.mangalens.core.translation.TranslationRefinementPolicy
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.core.translation.TranslationDraft

class SubtitleTargetCheckpointTest {
    private val source = SubtitleSourceIdentity(SubtitleMediaSource("content://fixture/original-speech"), "a".repeat(64))
    private val legacy = SubtitleGenerationConfig(modelSha256 = "b".repeat(64), threads = 2)
    private fun store(root: File) = SubtitleGenerationStore(root, object : SubtitleJournalIo {
        override fun read(file: File) = file.readBytes()
        override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
    })

    @Test fun newHindiRequestIsAcceptedWithoutChangingLegacyEnglishIdentity() {
        val root = Files.createTempDirectory("subtitle-target").toFile()
        try {
            val store = store(root)
            val english = store.start(source, legacy)
            val hindi = store.start(source, legacy.withTarget(SubtitleTargetOptions(targetLanguage = "hi")))
            assertNotEquals(english.id, hindi.id)
            assertEquals("hi", hindi.config.targetLanguage)
            assertEquals(english.id, store(root).get(english.id)!!.id)
        } finally { root.deleteRecursively() }
    }

    @Test fun actualVersionOneJournalKeepsItsOriginalIdentityAndEnglishCueProvenance() {
        val root = Files.createTempDirectory("subtitle-legacy").toFile()
        try {
            val oldConfig = "SubtitleGenerationConfig(sourceLanguage=auto, targetLanguage=en, style=whisper-english, modelSha256=${legacy.modelSha256}, windowSeconds=8, overlapSeconds=1, threads=2)"
            val identity = listOf(source.source.cacheKey, source.fingerprint, oldConfig).joinToString("|") { "${it.length}:$it" }
            val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
            val cue = JSONObject().put("startMs", 500).put("endMs", 2500).put("text", "This was translated by Whisper.")
            val journal = JSONObject().put("version", 1).put("id", id).put("generation", "d".repeat(32))
                .put("uri", source.source.uri).put("headers", JSONObject()).put("cacheKey", source.source.cacheKey)
                .put("label", source.source.label).put("fingerprint", source.fingerprint).put("verifiable", true)
                .put("config", JSONObject().put("sourceLanguage", "auto").put("targetLanguage", "en").put("style", "whisper-english")
                    .put("modelSha256", legacy.modelSha256).put("windowSeconds", 8).put("overlapSeconds", 1).put("threads", 2))
                .put("status", "PAUSED").put("durationMs", 8000).put("updatedAt", 1)
                .put("windows", JSONArray().put(JSONObject().put("index", 0).put("startMs", 0).put("endMs", 8000)
                    .put("pcmSha256", "c".repeat(64)).put("silent", false).put("cues", JSONArray().put(cue))))
            File(root, "$id.json").writeText(journal.toString())
            val restored = store(root).get(id)
            assertNotNull("A genuine v1 identity must remain readable after new options are added", restored)
            assertEquals("This was translated by Whisper.", restored!!.windows.single().cues.single().text)
            assertEquals("en", restored.config.targetLanguage)
            assertEquals(SubtitlePipeline.WHISPER_ENGLISH, restored.config.pipeline)
            assertTrue("Legacy translated English is not original ASR", restored.sourceCues.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun sourceOnlyCheckpointIsUsefulPartialSpeechRatherThanCompletedTargetOutput() {
        val root = Files.createTempDirectory("subtitle-source-first").toFile()
        try {
            val store = store(root)
            val config = legacy.withTarget(SubtitleTargetOptions(targetLanguage = "hi"))
            val task = store.start(source, config)
            val window = SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = listOf(SpeechCue(500, 2500, "Where are you?")))
            assertTrue(store.checkpoint(task.id, task.generation, window, 8000))
            assertEquals(window.sourceCues, store(root).get(task.id)!!.windows.single().sourceCues)
            val result = store.finish(task.id, task.generation)!!
            assertEquals(SubtitleGenerationStatus.PARTIAL, result.status)
            assertEquals(window.sourceCues, result.sourceCues)
            assertEquals(1, result.pendingTargetCues)
            assertNull(result.srtPath)
        } finally { root.deleteRecursively() }
    }

    @Test fun missingOneTranslationNeverExportsAnApparentlyCompleteTrack() {
        val root = Files.createTempDirectory("subtitle-partial-target").toFile()
        try {
            val store = store(root)
            val task = store.start(source, legacy.withTarget(SubtitleTargetOptions(targetLanguage = "hi")))
            val originals = listOf(SpeechCue(500, 2500, "Where are you?"), SpeechCue(3000, 5000, "Come here."))
            val window = SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = originals,
                translations = listOf(SubtitleTranslatedCue(0, "तुम कहाँ हो?")))
            store.checkpoint(task.id, task.generation, window, 8000)
            val result = store.finish(task.id, task.generation)!!
            assertEquals(SubtitleGenerationStatus.PARTIAL, result.status)
            assertEquals(originals, result.sourceCues)
            assertEquals(listOf(SpeechCue(500, 2500, "तुम कहाँ हो?")), result.cues)
            assertNull(result.srtPath)
            assertNull(store.exportVerified(task.id, task.generation))
        } finally { root.deleteRecursively() }
    }

    @Test fun aCopiedEnglishTargetCannotEnterAHinglishJournal() {
        val root = Files.createTempDirectory("subtitle-quality").toFile()
        try {
            val store = store(root)
            val task = store.start(source, legacy.withTarget(SubtitleTargetOptions(targetLanguage = "hi-latn")))
            val window = SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = listOf(SpeechCue(500, 2500, "Where are you?")),
                translations = listOf(SubtitleTranslatedCue(0, "Where are you?")))
            try { store.checkpoint(task.id, task.generation, window, 8000); fail("An English clause was saved as Hinglish") }
            catch (_: IllegalArgumentException) { }
            assertTrue(store.get(task.id)!!.windows.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun dualOutputKeepsActualOriginalSpeechAndExactCueTiming() {
        val config = legacy.withTarget(SubtitleTargetOptions(targetLanguage = "hi-latn", outputMode = SubtitleOutputMode.DUAL))
        val window = SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = listOf(SpeechCue(500, 2500, "Where are you?")),
            translations = listOf(SubtitleTranslatedCue(0, "Tum kahan ho?")))
        val task = SubtitleGenerationTask("d".repeat(32), "e".repeat(32), source, config, SubtitleGenerationStatus.PAUSED, listOf(window))
        assertEquals(listOf(SpeechCue(500, 2500, "Where are you?\nTum kahan ho?")), task.cues)
        assertEquals(listOf(SpeechCue(500, 2500, "Where are you?")), task.sourceCues)
    }

    @Test fun sourceCheckpointDetachesCallerOwnedCueLists() {
        val root = Files.createTempDirectory("subtitle-source-alias").toFile()
        try {
            val store = store(root)
            val task = store.start(source, legacy.withTarget(SubtitleTargetOptions(targetLanguage = "hi")))
            val originals = mutableListOf(SpeechCue(500, 2500, "Where are you?"))
            store.checkpoint(task.id, task.generation, SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = originals), 8000)
            originals.clear()
            assertEquals(listOf(SpeechCue(500, 2500, "Where are you?")), store.get(task.id)!!.windows.single().sourceCues)
        } finally { root.deleteRecursively() }
    }

    @Test fun individualTargetSuccessAndOriginalSpeechSurvivePauseAndGenerationFencedResume() {
        val root = Files.createTempDirectory("subtitle-cue-resume").toFile()
        try {
            val store = store(root)
            val task = store.start(source, legacy.withTarget(SubtitleTargetOptions(targetLanguage = "hi", outputMode = SubtitleOutputMode.DUAL)))
            val originals = listOf(SpeechCue(500, 2500, "Where are you?"), SpeechCue(3000, 5000, "Come here."))
            store.checkpoint(task.id, task.generation, SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = originals), 8000)
            assertTrue(store.checkpointTarget(task.id, task.generation, 0, "c".repeat(64), SubtitleTranslatedCue(0, "तुम कहाँ हो?")))
            store.pause(task.id, task.generation)
            val resumed = store.resume(task.id, task.generation)!!
            assertEquals(originals, resumed.sourceCues)
            assertEquals(1, resumed.pendingTargetCues)
            assertFalse(store.checkpointTarget(task.id, task.generation, 0, "c".repeat(64), SubtitleTranslatedCue(1, "यहाँ आओ।")))
            assertTrue(store.checkpointTarget(resumed.id, resumed.generation, 0, "c".repeat(64), SubtitleTranslatedCue(1, "यहाँ आओ।")))
            store.completeAudio(resumed.id, resumed.generation, 1)
            val completed = store.finish(resumed.id, resumed.generation)!!
            assertEquals(SubtitleGenerationStatus.COMPLETED, completed.status)
            assertEquals(originals[1].startMs, completed.cues[1].startMs)
            assertEquals("Come here.\nयहाँ आओ।", completed.cues[1].text)
            assertTrue(File(completed.srtPath!!).readText().contains("Where are you?\nतुम कहाँ हो?"))
            val reopened = store(root)
            assertTrue(reopened.get(task.id)!!.audioComplete)
            reopened.confirmValidated(task.id, resumed.generation)
            assertEquals(completed.cues, reopened.exportVerified(task.id, resumed.generation)!!.cues)
        } finally { root.deleteRecursively() }
    }

    @Test fun savedHindiDraftKeepsVerifiedHinglishNounAcrossJournalRestoration() {
        val root = Files.createTempDirectory("subtitle-hindi-proof").toFile()
        try {
            val store = store(root)
            val task = store.start(source, legacy.withTarget(SubtitleTargetOptions(targetLanguage = "hi-latn")))
            val original = SpeechCue(500, 2500, "Food!")
            store.checkpoint(task.id, task.generation, SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = listOf(original)), 8000)
            val draft = com.mangalens.core.translation.HinglishTranslationOutput.fromHindiDraft(original.text, "भोजन!")
            assertTrue(store.checkpointTarget(task.id, task.generation, 0, "c".repeat(64), SubtitleTranslatedCue(0, draft.text, draft.hindiDraft)))
            val reopened = store(root).get(task.id)!!
            assertEquals(draft.hindiDraft, reopened.windows.single().translations.single().hindiDraft)
            assertEquals(draft.text, reopened.windows.single().translations.single().text)
        } finally { root.deleteRecursively() }
    }

    @Test fun targetPunctuationNeverMergesOrMovesDistinctOriginalCueTimings() {
        val config = legacy.withTarget(SubtitleTargetOptions(targetLanguage = "hi", outputMode = SubtitleOutputMode.DUAL))
        val originals = listOf(SpeechCue(500, 1000, "Go."), SpeechCue(1100, 2000, "Yes."))
        val window = SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = originals,
            translations = listOf(SubtitleTranslatedCue(0, "जाओ।"), SubtitleTranslatedCue(1, "हाँ।")))
        val task = SubtitleGenerationTask("d".repeat(32), "e".repeat(32), source, config, SubtitleGenerationStatus.PAUSED, listOf(window))
        assertEquals(listOf(SpeechCue(500, 1000, "Go.\nजाओ।"), SpeechCue(1100, 2000, "Yes.\nहाँ।")), task.cues)
    }

    @Test fun translatedDocumentReceiptRequiresCompleteTargetCoverage() {
        val cue = SpeechCue(500, 1000, "Go.\nजाओ।")
        val partial = FullSubtitleState(cues = listOf(cue), srt = SubtitleFormats.srt(listOf(cue)),
            vtt = SubtitleFormats.vtt(listOf(cue)), taskId = "d".repeat(32), generation = "e".repeat(32),
            sourceCacheKey = source.source.cacheKey, status = SubtitleGenerationStatus.PARTIAL,
            pipeline = SubtitlePipeline.SOURCE_TRANSLATION, targetLanguage = "hi", outputMode = SubtitleOutputMode.DUAL,
            pendingTargetCues = 1)
        assertNull("Useful partial speech must not export as a completed translated track",
            SubtitleExportReceipt.capture(partial, source.source, "srt"))
        assertNull(SubtitleExportReceipt.capture(partial.copy(status = SubtitleGenerationStatus.COMPLETED), source.source, "vtt"))
        assertNotNull(SubtitleExportReceipt.capture(partial.copy(status = SubtitleGenerationStatus.COMPLETED, pendingTargetCues = 0), source.source, "vtt"))
    }

    @Test fun overlappingSourceDedupNeverErasesNegationOrLaterRepeatedSpeech() {
        val first = SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = listOf(SpeechCue(7000, 8000, "Do it.")))
        val second = SubtitleWindow(1, 7000, 15000, "d".repeat(64), sourceCues = listOf(
            SpeechCue(7100, 8000, "Do not do it."), SpeechCue(9000, 10000, "Do it.")))
        assertEquals(first.sourceCues + second.sourceCues, SubtitleAlignedTrack.pairs(listOf(first, second)).map { it.original })
    }

    @Test fun exactOverlappingOriginalCanAcquireItsTargetWithoutMovingTheOriginalTiming() {
        val original = SpeechCue(7000, 8000, "Go.")
        val first = SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = listOf(original))
        val second = SubtitleWindow(1, 7000, 15000, "d".repeat(64), sourceCues = listOf(SpeechCue(7100, 8050, "Go.")),
            translations = listOf(SubtitleTranslatedCue(0, "जाओ।")))
        val pairs = SubtitleAlignedTrack.pairs(listOf(first, second))
        assertEquals(listOf(original), pairs.map { it.original })
        assertEquals(listOf(original.copy(text = "Go.\nजाओ।")), SubtitleAlignedTrack.render(pairs, SubtitleOutputMode.DUAL))
        assertEquals(SubtitleAlignedTrack.render(pairs, SubtitleOutputMode.DUAL),
            SubtitleAlignedTrack.retainTimings(SubtitleAlignedTrack.render(pairs, SubtitleOutputMode.DUAL)))
    }

    @Test fun verifiedFormalAndCustomHindiCandidateKeepsItsRespectfulRegisterInColdJournal() {
        for (style in listOf(TranslationStyleProfile.FORMAL,
            TranslationStyleProfile.custom("Use respectful Hindi addressing the listener politely."))) {
            assertRespectfulCandidateRestores(style, "hi", "अपनी पुस्तक दो।", "कृपया अपनी पुस्तक दीजिए।")
        }
    }

    @Test fun verifiedFormalAndCustomRomanCandidateKeepsItsRegisterAndPinnedCandidateReceipt() {
        for (style in listOf(TranslationStyleProfile.FORMAL,
            TranslationStyleProfile.custom("Address the listener respectfully with aap and polite imperatives."))) {
            assertRespectfulCandidateRestores(style, "hi-latn", "tum apni pustak do.", "aap apni pustak dijiye.")
        }
    }

    @Test fun alreadySelectedRespectfulHindiProofSurvivesPinnedRomanJournalRestoration() {
        for (style in listOf(TranslationStyleProfile.FORMAL,
            TranslationStyleProfile.custom("Use respectful Hindi and preserve its register in Roman Hindi."))) {
            val draft = HinglishTranslationOutput.fromHindiDraft("Give me your book.", "कृपया अपनी पुस्तक दीजिए।", style)
            assertRespectfulCandidateRestores(style, "hi-latn", draft.text, draft.text, draft.hindiDraft)
        }
    }

    private fun assertRespectfulCandidateRestores(style: TranslationStyleProfile, target: String,
        draft: String, candidate: String, hindiDraft: String? = null) {
        val root = Files.createTempDirectory("subtitle-captured-register").toFile()
        try {
            val store = store(root)
            val config = legacy.withTarget(SubtitleTargetOptions(targetLanguage = target,
                outputMode = SubtitleOutputMode.DUAL, style = style.id,
                customStyle = if (style.id == "custom") style.instruction else "", localRefinement = true,
                refinementPin = SubtitleRefinementPin("captured-model", "f".repeat(64), 1_000_000), capturedStyle = style))
            val task = store.start(source, config)
            val original = SpeechCue(500, 2500, "Give me your book.")
            val window = SubtitleWindow(0, 0, 8000, "c".repeat(64), sourceCues = listOf(original))
            assertTrue(store.checkpoint(task.id, task.generation, window, 8000))
            val saved = store.get(task.id)!!
            val selected = config.selectTargetDraft(original.text, TranslationDraft(draft, hindiDraft), candidate)
            assertEquals("Generation must preserve the captured register before saving its receipt", candidate, selected.text)
            assertEquals(hindiDraft, selected.hindiDraft)
            val prompt = TranslationRefinementPolicy.prompt(original.text, draft, target, style,
                saved.translationContext(0, 0), emptyMap())
            val accepted = SubtitleTranslatedCue(0, candidate, hindiDraft,
                SubtitleSavedRefinement(config.refinementPin!!, TranslationRefinementPolicy.hash(prompt),
                    TranslationRefinementPolicy.hash(candidate)), draft, candidate, hindiDraft)
            assertTrue("A verified captured-style candidate must not be rewritten as natural dialogue",
                store.checkpointTarget(task.id, task.generation, 0, window.pcmSha256, accepted))
            store.completeAudio(task.id, task.generation, 1)
            val completed = store.finish(task.id, task.generation)!!
            assertEquals(SubtitleGenerationStatus.COMPLETED, completed.status)
            assertEquals(listOf(original.copy(text = original.text + "\n" + candidate)), completed.cues)
            val reopened = store(root)
            assertEquals(accepted, reopened.get(task.id)!!.windows.single().translations.single())
            assertNotNull(reopened.confirmValidated(task.id, task.generation))
            assertEquals(completed.cues, reopened.exportVerified(task.id, task.generation)!!.cues)
        } finally { root.deleteRecursively() }
    }
}
