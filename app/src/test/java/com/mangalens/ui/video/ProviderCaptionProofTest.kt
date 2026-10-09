package com.mangalens.ui.video

import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.download.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProviderCaptionProofTest {
    private val page = "https://www.youtube.com/watch?v=abcdefghijk"
    private val track = ProviderCaptionTrack("https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", "en", ProviderCaptionKind.MANUAL, ProviderCaptionFormat.VTT)
    private val inventory = ProviderCaptionInventory(page, "abcdefghijk", "en", null, 20_000, listOf(track))
    private val source = SubtitleSourceIdentity(SubtitleMediaSource("https://video.invalid/manifest", cacheKey=page,
        providerCaptions=inventory, sourceResolutionId="f".repeat(32)), "a".repeat(64), verifiable=false)
    private val config = SubtitleGenerationConfig().withTarget(SubtitleTargetOptions())
    private val document = ProviderCaptionParser.parse("WEBVTT\n\n00:00.500 --> 00:18.000\nDon't open the door.\n".toByteArray(), ProviderCaptionFormat.VTT, 20_000)
    private fun task(): SubtitleGenerationTask {
        val task = SubtitleGenerationTask("b".repeat(32), "c".repeat(32), source, config, SubtitleGenerationStatus.RUNNING,
            providerCaptionWindows(document, "en"), durationMs=20_000)
        return task.copy(providerCaptionReceipt=providerReceipt(task, track, document))
    }
    @Test fun providerTimingProofDoesNotClaimAudioCompletionOrDecodedPcm() {
        val task = task()
        assertTrue(validProviderCaptionTask(task))
        assertTrue(hasVerifiedProviderCaptions(task))
        assertFalse(task.audioComplete)
        assertEquals("", task.windows.single().pcmSha256)
        assertEquals(18_000L, task.windows.single().sourceCues.single().endMs)
    }
    @Test fun changedInteriorTimingDialogueSourceConfigAndGenerationRejectTheReceipt() {
        val task = task()
        val changedWindow = task.windows.single().copy(sourceCues=listOf(SpeechCue(500, 17_999, "Don't open the door.")))
        val invalid = listOf(task.copy(windows=listOf(changedWindow)), task.copy(source=source.copy(fingerprint="d".repeat(64))),
            task.copy(config=config.copy(targetLanguage="hi")), task.copy(generation="e".repeat(32)),
            task.copy(audioComplete=true), task.copy(windows=listOf(task.windows.single().copy(pcmSha256="f".repeat(64)))))
        invalid.forEach { assertFalse(validProviderCaptionTask(it)) }
    }
    @Test fun resumedGenerationCanRetainMaskedSourceButCannotPublishUntilFreshCaptionProof() {
        val resumed = task().copy(generation="e".repeat(32), validationPending=true)
        assertTrue(validProviderCaptionTask(resumed, allowUnvalidatedGeneration=true))
        assertFalse(hasVerifiedProviderCaptions(resumed))
        assertTrue(resumed.cues.isEmpty())
    }
    @Test fun changedProviderDocumentIsNotTheSameProofEvenWhenOnlyItsBodyBytesChange() {
        val task = task(); val receipt = task.providerCaptionReceipt!!
        assertTrue(receipt.sameDocument(receipt.copy(generation="e".repeat(32))))
        assertFalse(receipt.sameDocument(receipt.copy(payloadSha256="f".repeat(64))))
        assertFalse(receipt.sameDocument(receipt.copy(trackUrlSha256="f".repeat(64))))
    }
    @Test fun providerCaptionCandidateQueuesWithoutRequiringAWhisperModel() {
        val root = Files.createTempDirectory("provider-caption-proof").toFile()
        val io = object : SubtitleJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
        }
        try {
            val saved = SubtitleGenerationStore(root, io).start(source, config)
            assertEquals(SubtitleGenerationStatus.QUEUED, saved.status)
            assertNull(saved.error)
            assertNull(saved.config.modelSha256)
            assertFalse(saved.audioComplete)
        } finally { root.deleteRecursively() }
    }
    @Test fun sceneContextChangesConfigurationAndIsCapturedBeforeCallerMutation() {
        var context = "A younger sibling is speaking to her sister."
        val options = SubtitleTargetOptions(style="formal",localRefinement=true,
            refinementPin=SubtitleRefinementPin("verified-model", "f".repeat(64), 2_000_000),sceneContext=context).capture()
        val captured = SubtitleGenerationConfig(modelSha256="d".repeat(64)).withTarget(options)
        context = "A guard is speaking to an enemy."
        assertEquals("A younger sibling is speaking to her sister.", captured.sceneContext)
        assertNotEquals(captured.fingerprint(), captured.copy(sceneContext=context).fingerprint())
        assertEquals(TranslationStyleProfile.FORMAL, captured.capturedStyle)
    }
    @Test fun sceneContextAndOnlyEarlierOriginalCuesReachTheCurrentPrompt() {
        val task = task().copy(config=config.copy(sceneContext="Two siblings are arguing."), windows=listOf(
            SubtitleWindow(0,0,8000,"",sourceCues=listOf(SpeechCue(0,1000,"Earlier original."),SpeechCue(2000,3000,"Current original."),
                SpeechCue(4000,5000,"Future original.")), providerCueSha256="a".repeat(64))))
        val context = task.translationContext(0,1)
        assertTrue(context.contains("Two siblings are arguing."))
        assertTrue(context.contains("Earlier original."))
        assertFalse(context.contains("Current original."))
        assertFalse(context.contains("Future original."))
    }
    @Test fun providerCuePairingRetainsOverlappingRepeatAcrossJournalBatches() {
        val cues = (0 until 65).map { ProviderCaptionCue(it * 100L, it * 100L + 1000, "Wait.") }
        val windows = providerCaptionWindows(ProviderCaptionDocument(cues, "a".repeat(64), ProviderCaptionParser.cueHash(cues)), "en")
        assertEquals(2, windows.size)
        assertEquals(cues.size, providerCaptionPairs(windows).size)
        assertEquals(6400L, providerCaptionPairs(windows).last().original.startMs)
    }
}
