package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Authored controls: execution deferred until the assembled implementation phase. */
class FailedDownloadPartialPolicyTest {
    private fun item(state: DownloadState = DownloadState.FAILED) = DownloadEntity(
        "failed-1", "https://example.invalid/movie.mp4", "Movie", "video/mp4", state = state)

    @Test fun onlyAnExactFailedOrdinaryUnpublishedRowCanBeClaimed() {
        assertEquals("failed-1", FailedDownloadPartialPolicy.capture(item()).id)
        DownloadState.entries.filterNot { it == DownloadState.FAILED }.forEach { state ->
            assertThrows(IllegalStateException::class.java) { FailedDownloadPartialPolicy.capture(item(state)) }
        }
        assertThrows(IllegalStateException::class.java) { FailedDownloadPartialPolicy.capture(item().copy(destination = "content://media/1")) }
        assertThrows(IllegalStateException::class.java) { FailedDownloadPartialPolicy.capture(item().copy(sourceUrl = "https://example.invalid/live.m3u8")) }
        assertThrows(IllegalStateException::class.java) { FailedDownloadPartialPolicy.capture(item().copy(id = "../escape")) }
    }

    @Test fun onlyAnAcknowledgedCleanupClaimCanRetryAfterWaitingForAProducer() {
        val pending = item(DownloadState.CANCELLED).copy(stage = FailedDownloadPartialPolicy.PENDING)
        assertEquals(pending, FailedDownloadPartialPolicy.capture(pending))
        assertThrows(IllegalStateException::class.java) { FailedDownloadPartialPolicy.capture(pending.copy(stage = "Cancelled by user")) }
        assertThrows(IllegalStateException::class.java) { FailedDownloadPartialPolicy.capture(pending.copy(stage = FailedDownloadPartialPolicy.REMOVED)) }
    }

    @Test fun exactManagedFilesExcludeNeighboursCompletedExportsAndDurableRoots() {
        val parent = Files.createTempDirectory("partial-policy").toFile()
        try {
            val root = File(parent, "downloads").apply { mkdir() }
            val attempt = "failed-1.muxed-12345678-1234-1234-1234-123456789abc"
            val managed = listOf("failed-1.part", "failed-1.validator", "failed-1.audio.part", "failed-1.audio.validator",
                "$attempt.mp4", "$attempt.source-video.video.timing", "failed-1.muxed.mp4")
            val retained = listOf("failed-10.part", "failed-1.mp4", "failed-1.muxed-not-a-uuid.mp4", "unrelated.part")
            (managed + retained).forEach { File(root, it).writeText(it) }
            val important = File(parent, "chapters/page.jpg").apply { parentFile!!.mkdir(); writeText("original") }
            assertEquals(managed.toSet(), FailedDownloadPartialFiles.plan(root, "failed-1").map { it.name }.toSet())
            assertTrue(retained.all { File(root, it).exists() }); assertEquals("original", important.readText())
        } finally { parent.deleteRecursively() }
    }

    @Test fun managedSymlinkAndDirectoryFailClosedBeforeAnyDeletion() {
        val parent = Files.createTempDirectory("partial-symlink").toFile()
        try {
            val root = File(parent, "downloads").apply { mkdir() }
            val valuable = File(parent, "original.mp4").apply { writeText("only copy") }
            Files.createSymbolicLink(File(root, "failed-1.part").toPath(), valuable.toPath())
            assertThrows(IllegalStateException::class.java) { FailedDownloadPartialFiles.plan(root, "failed-1") }
            assertEquals("only copy", valuable.readText())
            File(root, "failed-1.part").delete(); File(root, "failed-1.part").mkdir()
            assertThrows(IllegalStateException::class.java) { FailedDownloadPartialFiles.plan(root, "failed-1") }
        } finally { parent.deleteRecursively() }
    }

    @Test fun childRootSymlinkCannotTurnPartialCleanupIntoExternalDeletion() {
        val parent = Files.createTempDirectory("partial-root").toFile()
        try {
            val external = File(parent, "valuable").apply { mkdir() }
            File(external, "failed-1.part").writeText("retain")
            Files.createSymbolicLink(File(parent, "downloads").toPath(), external.toPath())
            assertThrows(IllegalStateException::class.java) { FailedDownloadPartialFiles.plan(File(parent, "downloads"), "failed-1") }
            assertEquals("retain", File(external, "failed-1.part").readText())
        } finally { parent.deleteRecursively() }
    }
}
