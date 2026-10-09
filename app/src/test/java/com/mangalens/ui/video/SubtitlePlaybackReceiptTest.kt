package com.mangalens.ui.video

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.*
import org.junit.Test

/** Uses the actual native Store and durable exports with host file IO; no Android AtomicFile/ASR claim. */
class SubtitlePlaybackReceiptTest {
    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("playback-subtitle-receipt").toFile()
        val io = object : SubtitleJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) {
                val temporary = File(file.path + ".fixture")
                temporary.outputStream().use { it.write(bytes); it.fd.sync() }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val store = SubtitleGenerationStore(directory, io)
        val source = SubtitleSourceIdentity(SubtitleMediaSource("file:///fixture/owned.wav", mapOf("X-QA" to "one")), "a".repeat(64))
        val config = SubtitleGenerationConfig(modelSha256 = "b".repeat(64))
        fun complete(owner: String, force: Boolean = false): SubtitleGenerationTask {
            val task = store.start(source, config, force, owner)
            store.running(task.id, task.generation)
            assertTrue(store.checkpoint(task.id, task.generation,
                SubtitleWindow(0, 0, 8000, "c".repeat(64), listOf(SpeechCue(0, 2500, "Saved original speech."))), 8000))
            return requireNotNull(store.finish(task.id, task.generation))
        }
        override fun close() { directory.deleteRecursively() }
    }

    @Test fun oldOwnerObserverCannotAdoptOrCancelTheReplacementTaskOrItsExports() = Fixture().use { f ->
        val old = f.complete("reader-old")
        val replacement = f.complete("orez-new", force = true)
        assertEquals(old.id, replacement.id)
        assertNotEquals(old.generation, replacement.generation)
        val journal = File(f.directory, replacement.id + ".json").readBytes()
        val selected = if (sameSubtitlePlaybackReceipt(replacement, old)) replacement else old
        assertNull("Old observer must keep its old control generation", f.store.cancel(selected.id, selected.generation))
        assertEquals(replacement, f.store.get(replacement.id))
        assertArrayEquals(journal, File(f.directory, replacement.id + ".json").readBytes())
        assertTrue(File(replacement.srtPath!!).isFile)
        assertTrue(File(replacement.vttPath!!).isFile)
    }

    @Test fun exactSourceHeaderAndConfigIdentityRemainPartOfTheObserverReceipt() = Fixture().use { f ->
        val saved = f.complete("reader")
        assertFalse(sameSubtitlePlaybackReceipt(saved.copy(source = saved.source.copy(
            source = saved.source.source.copy(headers = mapOf("X-QA" to "different")))), saved))
        assertFalse(sameSubtitlePlaybackReceipt(saved.copy(config = saved.config.copy(sourceLanguage = "ja")), saved))
        assertFalse(sameSubtitlePlaybackReceipt(saved.copy(ownerRequestId = "other"), saved))
        assertTrue(sameSubtitlePlaybackReceipt(saved.copy(updatedAt = saved.updatedAt + 1), saved))
    }

    @Test fun restoredOrCompletingGeneratedTrackCannotOverwriteAnImportedNativeTrack() = Fixture().use { f ->
        val saved = f.complete("reader")
        var nativeSelected = true
        var overlays = 0
        assertFalse(attachAutomaticSubtitleTrack(saved, saved, { !nativeSelected }) { overlays++ })
        assertEquals(0, overlays)
        nativeSelected = false
        assertTrue(attachAutomaticSubtitleTrack(saved, saved, { !nativeSelected }) { overlays++ })
        assertEquals(1, overlays)
    }

    @Test fun unverifiedOrReplacedResultsDoNotEvenInvokeTheNativeSelectionCallback() = Fixture().use { f ->
        val saved = f.complete("reader")
        var callbacks = 0
        for (unqualified in listOf(saved.copy(validationPending = true), saved.copy(pcmValidationRequired = true),
            saved.copy(generation = "d".repeat(32)), saved.copy(ownerRequestId = "other"),
            saved.copy(status = SubtitleGenerationStatus.PARTIAL, srtPath = null, vttPath = null))) {
            assertFalse(attachAutomaticSubtitleTrack(unqualified, saved, { callbacks++; true }) { fail("Unqualified output attached") })
        }
        assertEquals(0, callbacks)
    }
}
