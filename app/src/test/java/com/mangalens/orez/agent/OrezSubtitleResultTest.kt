package com.mangalens.orez.agent

import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs
import com.mangalens.ui.video.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Real managed Store exports; synthetic timed cues are not ASR or translation quality evidence. */
class OrezSubtitleResultTest {
    @get:Rule val temporary = TemporaryFolder()
    private val io = object : SubtitleJournalIo {
        override fun read(file: File) = file.readBytes()
        override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
    }
    private data class Fixture(val folder: File, val source: SubtitleSourceIdentity, val native: SubtitleGenerationStore,
        val plan: OrezTaskPlan, val task: SubtitleGenerationTask, val receipt: OrezSubtitleReceipt) {
        val reference get() = OrezSubtitleResult.references(plan).single()
    }
    private fun fixture(): Fixture {
        val folder = temporary.newFolder(); val file = temporary.newFile().apply { writeText("actual captured test source") }
        val selected = OrezMediaSelection("https://explicit.example/video.mp4", "https://explicit.example/watch",
            headers = mapOf("Cookie" to "never shown"), resolutionId = "complete-pair",
            audio = OrezAudioSelection(file.toURI().toString(), "complete-pair"), expectedDurationUs = 8_000_000)
        val initial = OrezAgentRuntime().decide("Generate subtitles for this selected video", OrezAgentContext(selectedMedia = selected)).plan!!
        val native = SubtitleGenerationStore(folder, io)
        val source = SubtitleSourceIdentity(selected.nativeSubtitleSource(),
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) })
        val owner = OrezDurablePlanRules.requestId(initial.id, 1)
        val started = native.start(source, initial.authorization!!.subtitle!!.nativeConfig("b".repeat(64)), ownerRequestId = owner)
        native.running(started.id, started.generation)
        assertTrue(native.checkpoint(started.id, started.generation,
            SubtitleWindow(0, 0, 8_000, "c".repeat(64), cues = listOf(SpeechCue(100, 7_900, "Hello."))), 8_000))
        val task = native.finish(started.id, started.generation)!!
        val receipt = OrezSubtitleNativeEvidence.receipt(task, selected.sourceId, folder, selected)
        val plan = initial.copy(status = OrezTaskStatus.COMPLETED, steps = initial.steps.map { step ->
            step.copy(status = OrezStepStatus.COMPLETED, outputKind = OrezDurablePlanRules.outputKind(step.call.name),
                outputs = if (step.index == 0) receipt.media.outputs(OrezDurablePlanRules.requestId(initial.id, 0)) else
                    receipt.outputs(owner) + ("destination" to "subtitle-track:${receipt.taskId}"))
        })
        return Fixture(folder, source, native, plan, task, receipt)
    }

    @Test fun completedReferenceReopensOnlyExactManagedGenerationExports() {
        val f = fixture(); val reopened = SubtitleGenerationStore(f.folder, io)
        assertTrue(reopened.get(f.task.id)!!.validationPending)
        // Fresh original source confirmation is separate from integrity of saved exports.
        reopened.confirmValidated(f.task.id, f.task.generation)
        val exact = reopened.exportVerified(f.task.id, f.task.generation)!!
        val receipt = OrezSubtitleNativeEvidence.receipt(exact, f.receipt.media.sourceId, f.folder, f.receipt.media.descriptor)
        OrezSubtitleResult.verify(f.plan, f.reference, receipt)
        assertEquals("Hello.", exact.cues.single().text)
        assertTrue(File(exact.vttPath!!).readText().startsWith("WEBVTT"))
        assertFalse(f.reference.toString().contains("https://"))
        assertFalse(f.reference.toString().contains("never shown"))
    }

    @Test fun dispatchWaitingAndPendingControlNeverExposeCompletedResultNavigation() {
        val f = fixture()
        for (status in listOf(OrezTaskStatus.DISPATCHED, OrezTaskStatus.WAITING, OrezTaskStatus.RUNNING, OrezTaskStatus.CANCELLED))
            assertTrue(OrezSubtitleResult.references(f.plan.copy(status = status)).isEmpty())
        assertTrue(OrezSubtitleResult.references(f.plan.copy(pendingControl = OrezPendingControl.PAUSE)).isEmpty())
    }

    @Test fun foreignPlanStepOrGenerationCannotOpenThisTrack() {
        val f = fixture(); val expected = f.reference
        for (foreign in listOf(expected.copy(planId = "foreign"), expected.copy(stepIndex = 0), expected.copy(generation = "d".repeat(32))))
            assertTrue(runCatching { OrezSubtitleResult.verify(f.plan, foreign, f.receipt) }.isFailure)
    }

    @Test fun corruptManagedExportFailsResultAndNewOwnerIsNotInspectedByOldGeneration() {
        val f = fixture()
        File(f.task.vttPath!!).writeText("corrupt")
        assertNull(f.native.exportVerified(f.task.id, f.task.generation))
        val partial = f.native.get(f.task.id)!!
        assertTrue(runCatching { OrezSubtitleResult.verify(f.plan, f.reference,
            OrezSubtitleNativeEvidence.receipt(partial, f.receipt.media.sourceId, f.folder, f.receipt.media.descriptor)) }.isFailure)
        val replacement = f.native.start(f.source, f.task.config, ownerRequestId = "reader-new-owner")
        val bytes = File(f.folder, "${replacement.id}.json").readBytes()
        assertNull(f.native.exportVerified(replacement.id, f.task.generation))
        assertArrayEquals(bytes, File(f.folder, "${replacement.id}.json").readBytes())
        assertEquals(replacement, f.native.get(replacement.id))
    }

    @Test fun savedHashAndFullCapturedPairMustMatchFreshReceipt() {
        val f = fixture()
        val changedHash = f.plan.copy(steps = f.plan.steps.map { if (it.index == 1) it.copy(outputs = it.outputs + ("vttSha256" to "a".repeat(64))) else it })
        assertTrue(runCatching { OrezSubtitleResult.verify(changedHash, f.reference, f.receipt) }.isFailure)
        val descriptor = f.receipt.media.descriptor.copy(headers = mapOf("Cookie" to "another request"))
        val foreign = f.receipt.copy(media = f.receipt.media.copy(descriptor = descriptor))
        assertTrue(runCatching { OrezSubtitleResult.verify(f.plan, f.reference, foreign) }.isFailure)
    }
}
