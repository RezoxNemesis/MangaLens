package com.mangalens.orez.agent

import com.mangalens.ui.video.SpeechCue
import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleMediaSource
import com.mangalens.ui.video.SubtitleSourceIdentity
import com.mangalens.ui.video.SubtitleTargetOptions
import com.mangalens.ui.video.SubtitleWindow
import org.junit.Assert.*
import org.junit.Test

class OrezSavedSpeechRepairScopeTest {
    private fun task() = SubtitleGenerationTask("a".repeat(32), "b".repeat(32),
        SubtitleSourceIdentity(SubtitleMediaSource("file:///selected.m4a"), "c".repeat(64)),
        SubtitleGenerationConfig(modelSha256 = "d".repeat(64), threads = 2).withTarget(SubtitleTargetOptions(targetLanguage = "hi-latn")),
        SubtitleGenerationStatus.PARTIAL, durationMs = 8_000, audioComplete = true, ownerRequestId = "orez-repair-step-1",
        windows = listOf(SubtitleWindow(0, 0, 8_000, "e".repeat(64), sourceCues = listOf(SpeechCue(100, 6500, "Fire!")), detectedLanguage = "en")))
    private fun expected(task: SubtitleGenerationTask) = OrezSubtitleNativeEvidence.metadataReceipt(task,
        task.source.source.let { OrezMediaSelection(it.uri, it.cacheKey, it.label, it.headers).sourceId })

    @Test fun exactOwnedCompleteOriginalSpeechCanRepairTargetsWithItsOriginalModelProvenance() {
        val task = task(); val receipt = expected(task)
        val snapshot = OrezSubtitleNativeEvidence.savedSpeechSnapshot(receipt, task.copy(validationPending = true), task.source)
        assertEquals(receipt.media, snapshot)
        assertEquals("d".repeat(64), snapshot!!.speechModelSha256)
        assertEquals(1, task.pendingTargetCues)
    }

    @Test fun unfinishedOrPcmMaskedSpeechCannotBypassInstalledModelInspection() {
        val task = task(); val receipt = expected(task)
        for (changed in listOf(task.copy(audioComplete = false), task.copy(pcmValidationRequired = true), task.copy(windows = emptyList()),
            task.copy(durationMs = 16_000), task.copy(config = SubtitleGenerationConfig(modelSha256 = "d".repeat(64), threads = 2)))) {
            assertNull(OrezSubtitleNativeEvidence.savedSpeechSnapshot(receipt, changed, task.source))
        }
    }

    @Test fun ownerGenerationModelOrFullTargetPolicyReplacementCannotRecoverTheOldProof() {
        val task = task(); val receipt = expected(task)
        for (changed in listOf(task.copy(id = "9".repeat(32)), task.copy(generation = "9".repeat(32)),
            task.copy(ownerRequestId = "another-reader-request"), task.copy(config = task.config.copy(modelSha256 = "9".repeat(64))),
            task.copy(config = task.config.copy(targetLanguage = "hi")), task.copy(config = task.config.copy(threads = 4)))) {
            assertNull(OrezSubtitleNativeEvidence.savedSpeechSnapshot(receipt, changed, task.source))
        }
    }

    @Test fun changedOrUnverifiedFreshAudioNeverRecoversSavedSpeech() {
        val task = task(); val receipt = expected(task)
        for (fresh in listOf(task.source.copy(fingerprint = "9".repeat(64)), task.source.copy(verifiable = false),
            task.source.copy(source = task.source.source.copy(uri = "file:///different.m4a")),
            task.source.copy(source = task.source.source.copy(headers = mapOf("Cookie" to "changed"))))) {
            assertNull(OrezSubtitleNativeEvidence.savedSpeechSnapshot(receipt, task, fresh))
        }
    }
}
