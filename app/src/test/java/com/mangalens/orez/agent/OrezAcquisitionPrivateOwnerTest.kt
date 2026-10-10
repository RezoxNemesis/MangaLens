package com.mangalens.orez.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class OrezAcquisitionPrivateOwnerTest {
    @Test fun failedActualCloseRetainsHandleWithoutRetryingIt() {
        val root = Files.createTempDirectory("failed-native-close-").toFile()
        try {
            val owner = OrezAcquisitionPrivateOwner(root); var closes = 0
            val handle = AutoCloseable { closes++; throw IOException("Actual close failed") }
            assertTrue(runCatching { owner.usePrivate(handle) { } }.isFailure)
            assertFalse(owner.privateReleaseProven())
            assertTrue(runCatching { owner.finish() }.isFailure)
            assertEquals(1, closes)
        } finally { root.deleteRecursively() }
    }
    @Test fun failedCloseBlocksStageDeletionAndFreshFileOpening() {
        val root = Files.createTempDirectory("retained-native-stage-").toFile()
        try {
            val owner = OrezAcquisitionPrivateOwner(root)
            runCatching { owner.usePrivate(AutoCloseable { throw IOException("Close failed") }) { } }
            val journal = File(root, "chapter.json"); val retained = File(root, "chapter.json.new").apply { writeText("retained original stage") }
            assertTrue(runCatching { OrezOwnedChapterJournalIo(owner).write(journal, "replacement".toByteArray()) }.isFailure)
            assertEquals("retained original stage", retained.readText()); assertFalse(journal.exists())
            runCatching { owner.finish() }
        } finally { root.deleteRecursively() }
    }
    @Test fun processSlotRetainsFailedOwnerAndRejectsAnotherProducer() = runTest {
        val root = Files.createTempDirectory("retained-native-slot-").toFile()
        try {
            val slot = OrezChapterAcquisitionSlot(); var secondStarted = false
            assertTrue(runCatching { slot.withFiles(root) { owner ->
                owner.usePrivate(AutoCloseable { throw IOException("Close failed") }) { }
            } }.isFailure)
            assertTrue(runCatching { slot.withFiles(root) { secondStarted = true } }.isFailure)
            assertFalse(secondStarted)
        } finally { root.deleteRecursively() }
    }
}
