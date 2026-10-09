package com.mangalens.orez.agent

import com.mangalens.ui.video.SpeechCue
import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationStore
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleJournalIo
import com.mangalens.ui.video.SubtitleSourceIdentity
import com.mangalens.ui.video.SubtitleWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real native export-validation mutations; no Android Context or provider substitute. */
class OrezOwnedSubtitleLookupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "orez-${"a".repeat(36)}-step-1"
    private val config = SubtitleGenerationConfig(sourceLanguage = "en", modelSha256 = "b".repeat(64), threads = 2)
    private val media = OrezMediaSelection("file:///explicit-selection.wav")
    private val source = SubtitleSourceIdentity(media.nativeSubtitleSource(), "c".repeat(64))
    private val io = object : SubtitleJournalIo {
        override fun read(file: File) = file.readBytes()
        override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
    }
    private fun complete(native: SubtitleGenerationStore, request: String, force: Boolean = false): SubtitleGenerationTask {
        val task = native.start(source, config, force = force, ownerRequestId = request)
        native.running(task.id, task.generation)
        assertTrue(native.checkpoint(task.id, task.generation, SubtitleWindow(0, 0, 8_000, "d".repeat(64),
            cues = listOf(SpeechCue(200, 7_000, "We can begin."))), 8_000))
        return native.finish(task.id, task.generation)!!
    }
    private fun verify(task: SubtitleGenerationTask) {
        val receipt = OrezSubtitleNativeEvidence.metadataReceipt(task, media.sourceId, media)
        OrezSubtitleTools.verify(receipt, OrezSubtitleSnapshot(media.sourceId, media, source.fingerprint, config.modelSha256!!),
            config.orezOptions(), owner)
    }

    @Test fun replacementDuringHeldScopeVerificationCannotRefreshOrRewriteTheNewOwnersCorruptExports() = runTest {
        val directory = temporary.newFolder(); val native = SubtitleGenerationStore(directory, io)
        val g1 = complete(native, owner)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val lookup = async {
            runCatching { OrezOwnedSubtitleLookup.refresh(native, g1.id, owner) { task ->
                verify(task)
                if (task.generation == g1.generation) { entered.complete(Unit); release.await() }
            } }
        }
        entered.await()
        val g2 = complete(native, "unrelated-reader", force = true)
        assertNotEquals(g1.generation, g2.generation)
        val damaged = File(g2.srtPath!!).apply { writeText("damaged replacement export") }
        val journal = File(directory, "${g2.id}.json"); val manifest = journal.readBytes()
        release.complete(Unit)
        val result = lookup.await()
        assertEquals("A stale refresh changed the replacement's status", SubtitleGenerationStatus.COMPLETED, native.get(g2.id)!!.status)
        assertEquals(g2.srtPath, native.get(g2.id)!!.srtPath)
        assertEquals(g2.vttPath, native.get(g2.id)!!.vttPath)
        assertArrayEquals("A stale request rewrote the replacement's journal", manifest, journal.readBytes())
        assertEquals("damaged replacement export", damaged.readText())
        assertNull(result.getOrThrow())
    }

    @Test fun capturedOwnerCandidateThatWasReplacedBeforeLookupIsRejectedBeforeScopeOrExportInspection() = runTest {
        val directory = temporary.newFolder(); val native = SubtitleGenerationStore(directory, io)
        val candidate = complete(native, owner)
        val replacement = complete(native, "unrelated-reader", force = true)
        File(replacement.vttPath!!).writeText("damaged replacement export")
        val journal = File(directory, "${replacement.id}.json"); val manifest = journal.readBytes()
        var inspections = 0
        val result = runCatching { OrezOwnedSubtitleLookup.refresh(native, candidate.id, owner, candidate.generation) {
            inspections++; verify(it)
        } }
        assertEquals(0, inspections)
        assertNull(result.getOrThrow())
        assertEquals(replacement, native.get(replacement.id))
        assertArrayEquals(manifest, journal.readBytes())
    }

    @Test fun invalidCapturedConfigProofDoesNotMutateEvenAnOwnedCorruptExport() = runTest {
        val directory = temporary.newFolder(); val native = SubtitleGenerationStore(directory, io)
        val task = complete(native, owner)
        File(task.srtPath!!).writeText("damaged owned export")
        val journal = File(directory, "${task.id}.json"); val manifest = journal.readBytes()
        val failure = runCatching { OrezOwnedSubtitleLookup.refresh(native, task.id, owner) {
            val receipt = OrezSubtitleNativeEvidence.metadataReceipt(it, media.sourceId, media)
            OrezSubtitleTools.verify(receipt, receipt.media, config.copy(threads = 1).orezOptions(), owner)
        } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(task, native.get(task.id))
        assertArrayEquals(manifest, journal.readBytes())
    }

    @Test fun theSameOwnersNewerGenerationCannotBeInspectedByAnOlderPersistedReceipt() = runTest {
        val directory = temporary.newFolder(); val native = SubtitleGenerationStore(directory, io)
        val previous = complete(native, owner)
        complete(native, "intervening-reader", force = true)
        val latest = complete(native, owner, force = true)
        assertNotEquals(previous.generation, latest.generation)
        File(latest.srtPath!!).writeText("damaged latest export")
        val journal = File(directory, "${latest.id}.json"); val manifest = journal.readBytes()
        var inspections = 0
        val result = OrezOwnedSubtitleLookup.refresh(native, previous.id, owner, previous.generation) {
            inspections++; verify(it)
        }
        assertEquals(0, inspections)
        assertNull(result)
        assertEquals(latest, native.get(latest.id))
        assertArrayEquals(manifest, journal.readBytes())
    }

    @Test fun exactOwnedLookupStillPerformsRealCorruptExportValidation() = runTest {
        val native = SubtitleGenerationStore(temporary.newFolder(), io)
        val task = complete(native, owner)
        File(task.srtPath!!).writeText("damaged owned export")
        val refreshed = OrezOwnedSubtitleLookup.refresh(native, task.id, owner, task.generation, ::verify)!!
        assertEquals(SubtitleGenerationStatus.PARTIAL, refreshed.status)
        assertNull(refreshed.srtPath); assertNull(refreshed.vttPath)
        assertEquals(task.generation, refreshed.generation)
        assertEquals(owner, refreshed.ownerRequestId)
    }

    @Test fun reopenedOwnedExportsOnlyLiftTheirSourceMaskAfterExactFreshSourceProof() = runTest {
        val directory = temporary.newFolder(); val original = SubtitleGenerationStore(directory, io)
        val completed = complete(original, owner)
        val reopened = SubtitleGenerationStore(directory, io)
        val captured = reopened.get(completed.id)!!
        assertTrue(captured.validationPending)
        assertTrue(captured.cues.isEmpty())
        val confirmed = OrezOwnedSubtitleLookup.confirmSource(reopened, captured, source, ::verify)!!
        assertFalse(confirmed.validationPending)
        assertEquals(completed.generation, confirmed.generation)
        assertEquals(completed.cues, confirmed.cues)
        assertNotNull(reopened.exportVerified(completed.id, completed.generation))
    }

    @Test fun sourceConfirmationCannotAdoptTheSameOwnersNewerGenerationAfterLookup() = runTest {
        val directory = temporary.newFolder(); val original = SubtitleGenerationStore(directory, io)
        val g1 = complete(original, owner)
        complete(original, "intervening-reader", force = true)
        val g2 = complete(original, owner, force = true)
        val reopened = SubtitleGenerationStore(directory, io)
        assertTrue(reopened.get(g2.id)!!.validationPending)
        val journal = File(directory, "${g2.id}.json"); val manifest = journal.readBytes()
        val result = OrezOwnedSubtitleLookup.confirmSource(reopened, g1, source, ::verify)
        assertTrue("A stale observation unmasked the new generation", reopened.get(g2.id)!!.validationPending)
        assertEquals(g2.generation, reopened.get(g2.id)!!.generation)
        assertArrayEquals(manifest, journal.readBytes())
        assertNull(result)
    }

    @Test fun freshSourceProofCannotClearTheNativeRequirementToRevalidateDecodedPcm() = runTest {
        val native = SubtitleGenerationStore(temporary.newFolder(), io)
        val task = native.start(source, config, ownerRequestId = owner)
        native.running(task.id, task.generation)
        native.checkpoint(task.id, task.generation, SubtitleWindow(0, 0, 8_000, "d".repeat(64),
            cues = listOf(SpeechCue(200, 7_000, "We can begin."))), 8_000)
        native.fail(task.id, task.generation, "Source must be revalidated.", requireValidation = true)
        val captured = native.get(task.id)!!
        assertTrue(captured.validationPending && captured.pcmValidationRequired)
        val confirmed = OrezOwnedSubtitleLookup.confirmSource(native, captured, source, ::verify)!!
        assertEquals(captured, confirmed)
        assertTrue(confirmed.validationPending && confirmed.pcmValidationRequired)
        assertNull(native.exportVerified(confirmed.id, confirmed.generation))
    }

    @Test fun changedSourceProofCannotLiftTheOwnedRestorationMask() = runTest {
        val directory = temporary.newFolder(); val original = SubtitleGenerationStore(directory, io)
        val task = complete(original, owner)
        val reopened = SubtitleGenerationStore(directory, io); val captured = reopened.get(task.id)!!
        val journal = File(directory, "${task.id}.json"); val manifest = journal.readBytes()
        val failure = runCatching { OrezOwnedSubtitleLookup.confirmSource(reopened, captured,
            source.copy(fingerprint = "e".repeat(64)), ::verify) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(captured, reopened.get(task.id))
        assertArrayEquals(manifest, journal.readBytes())
    }
}
