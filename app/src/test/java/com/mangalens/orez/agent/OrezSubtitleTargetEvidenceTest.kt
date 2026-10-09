package com.mangalens.orez.agent

import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs
import com.mangalens.ui.video.SpeechCue
import com.mangalens.ui.video.SubtitleFormats
import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleMediaSource
import com.mangalens.ui.video.SubtitleOutputMode
import com.mangalens.ui.video.SubtitlePipeline
import com.mangalens.ui.video.SubtitleRefinementPin
import com.mangalens.ui.video.SubtitleSourceIdentity
import com.mangalens.ui.video.SubtitleTargetOptions
import com.mangalens.ui.video.SubtitleTranslatedCue
import com.mangalens.ui.video.SubtitleWindow
import com.mangalens.core.translation.TranslationStyleProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Real native model/export serialization contracts; timed text fixtures do not prove ASR or semantic quality. */
class OrezSubtitleTargetEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pin = "b".repeat(64)
    private val request = "orez-scope-test-step-1"
    private val legacyOptions = OrezSubtitleOptions(threads = 2)
    private fun base(config: SubtitleGenerationConfig = SubtitleGenerationConfig(modelSha256 = pin, threads = 2)): SubtitleGenerationTask =
        SubtitleGenerationTask("a".repeat(32), "c".repeat(32),
            SubtitleSourceIdentity(SubtitleMediaSource("file:///explicit-selected.mp4"), "d".repeat(64)), config,
            SubtitleGenerationStatus.QUEUED, ownerRequestId = request)
    private fun metadata(task: SubtitleGenerationTask) = OrezSubtitleNativeEvidence.metadataReceipt(task, "selected-" + "e".repeat(32))
    private fun exported(task: SubtitleGenerationTask): OrezSubtitleReceipt {
        val root = temporary.newFolder()
        val folder = File(root, task.id).apply { mkdirs() }
        val srt = File(folder, "${task.generation}.srt").apply { writeText(SubtitleFormats.srt(task.cues)) }
        val vtt = File(folder, "${task.generation}.vtt").apply { writeText(SubtitleFormats.vtt(task.cues)) }
        return OrezSubtitleNativeEvidence.receipt(task.copy(srtPath = srt.path, srtSha256 = sha(srt.readBytes()),
            vttPath = vtt.path, vttSha256 = sha(vtt.readBytes())), "selected-" + "e".repeat(32), root)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun everyNonlegacyNativeFieldCannotBeDowngradedIntoLegacyOwnedScope() {
        val config = base().config
        val changed = listOf(
            config.copy(outputMode = SubtitleOutputMode.DUAL),
            config.copy(pipeline = SubtitlePipeline.SOURCE_TRANSLATION),
            config.copy(customStyle = "Use concise dialogue."),
            config.copy(localRefinement = true),
            config.copy(translationPolicy = "another-policy-v2"),
            config.copy(capturedStyle = TranslationStyleProfile.NATURAL),
            config.copy(refinementPin = SubtitleRefinementPin("small-local-model", "f".repeat(64), 1_500_000))
        )
        for (actual in changed) {
            assertThrows("Native extra options must not inherit legacy stop or completion authority: $actual", IllegalArgumentException::class.java) {
                val receipt = metadata(base(actual))
                OrezSubtitleTools.verify(receipt, metadata(base()).media, legacyOptions, request)
            }
        }
    }

    @Test fun actualNativeConfigFingerprintAppearsInItsTypedReceipt() {
        val task = base(SubtitleGenerationConfig(modelSha256 = pin, threads = 2).withTarget(
            SubtitleTargetOptions(targetLanguage = "hi-latn", outputMode = SubtitleOutputMode.DUAL, style = "faithful")))
        val outputs = metadata(task).outputs(request)
        assertEquals(task.config.fingerprint(), outputs["configFingerprint"])
        assertEquals("SOURCE_TRANSLATION", outputs["pipeline"])
        assertEquals("DUAL", outputs["outputMode"])
        assertEquals("mlkit-dialogue-v1", outputs["translationPolicy"])
    }

    private fun sourceTask(audioComplete: Boolean, missingTarget: Boolean): SubtitleGenerationTask {
        val original = listOf(SpeechCue(100, 2200, "Hello there."), SpeechCue(2800, 6500, "We can begin."))
        val translations = listOf(SubtitleTranslatedCue(0, "नमस्ते।"), SubtitleTranslatedCue(1, "हम शुरू कर सकते हैं।"))
        return base(SubtitleGenerationConfig(modelSha256 = pin, threads = 2).withTarget(SubtitleTargetOptions(targetLanguage = "hi"))).copy(
            status = SubtitleGenerationStatus.COMPLETED, durationMs = 8_000, audioComplete = audioComplete,
            windows = listOf(SubtitleWindow(0, 0, 8_000, "f".repeat(64), sourceCues = original,
                detectedLanguage = "en", translations = if (missingTarget) translations.take(1) else translations)))
    }

    @Test fun sourceRecognitionNotMarkedAudioCompleteCannotCompleteEvenWithMatchingExports() {
        assertThrows(IllegalArgumentException::class.java) {
            OrezSubtitleTools.verifyCompleted(exported(sourceTask(audioComplete = false, missingTarget = false)))
        }
    }

    @Test fun missingTargetCueCannotCompleteEvenWhenPartialExportHashesAndBytesMatch() {
        assertThrows(IllegalArgumentException::class.java) {
            OrezSubtitleTools.verifyCompleted(exported(sourceTask(audioComplete = true, missingTarget = true)))
        }
    }

    @Test fun completeLegacyModelAndManagedExportsRetainTheirExistingContract() {
        val task = base().copy(status = SubtitleGenerationStatus.COMPLETED, durationMs = 8_000,
            windows = listOf(SubtitleWindow(0, 0, 8_000, "f".repeat(64), listOf(SpeechCue(100, 6500, "Hello there.")))))
        val receipt = exported(task)
        OrezSubtitleTools.verify(receipt, metadata(task).media, legacyOptions, request)
        OrezSubtitleTools.verifyCompleted(receipt)
        assertEquals(1, receipt.cueCount)
    }
}
