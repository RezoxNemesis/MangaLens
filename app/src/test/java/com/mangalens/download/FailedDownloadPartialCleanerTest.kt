package com.mangalens.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Exact row, scheduler and real file contracts; authored UNRUN. */
class FailedDownloadPartialCleanerTest {
    private class Rows(var row: DownloadEntity) : FailedDownloadPartialRows {
        override suspend fun get(id: String) = row.takeIf { it.id == id }
        override suspend fun claim(captured: DownloadEntity): Boolean {
            if (row != captured) return false
            row = row.copy(state = DownloadState.CANCELLED, stage = FailedDownloadPartialPolicy.PENDING, error = "Stopped for partial cleanup")
            return true
        }
        override suspend fun complete(captured: DownloadEntity): Boolean {
            if (row != captured) return false
            row = row.copy(bytesDownloaded = 0, totalBytes = -1, stage = FailedDownloadPartialPolicy.REMOVED, error = null)
            return true
        }
    }
    private fun row() = DownloadEntity("owned", "https://example.invalid/movie.mp4", "Movie", "video/mp4",
        state = DownloadState.FAILED, bytesDownloaded = 12)

    @Test fun explicitCleanupClaimsOnlyItsFailedRowAndDeletesOnlyItsNamedManagedPartials() = runBlocking {
        val root = Files.createTempDirectory("partial-cleaner").toFile()
        try {
            root.resolve("owned.part").writeText("partial")
            root.resolve("peer.part").writeText("peer")
            root.resolve("owned.mp4").writeText("completed")
            val rows = Rows(row())
            val result = FailedDownloadPartialCleaner(root, rows, { true }, { _, _ -> false }).clean(rows.get("owned")!!)
            assertEquals(1, result.filesRemoved); assertFalse(root.resolve("owned.part").exists())
            assertEquals("peer", root.resolve("peer.part").readText())
            assertEquals("completed", root.resolve("owned.mp4").readText())
            assertEquals(DownloadState.CANCELLED, rows.row.state)
            assertEquals(FailedDownloadPartialPolicy.REMOVED, rows.row.stage)
        } finally { root.deleteRecursively() }
    }

    @Test fun allUniqueWorkGenerationsMustActuallyBeTerminalBeforeDeletion() = runBlocking {
        val root = Files.createTempDirectory("active-generation").toFile()
        try {
            val partial = root.resolve("owned.part").apply { writeText("retain") }
            val rows = Rows(row())
            try {
                FailedDownloadPartialCleaner(root, rows, { false }, { _, _ -> false }).clean(rows.get("owned")!!)
                fail("An enqueued or active generation cannot be deleted")
            } catch (_: DownloadFilesBusyException) { }
            assertEquals("retain", partial.readText())
            assertEquals(FailedDownloadPartialPolicy.PENDING, rows.row.stage)
        } finally { root.deleteRecursively() }
    }

    @Test fun terminalWorkInfoNeverSubstitutesForActualIndependentNativeReturn() = runBlocking {
        val root = Files.createTempDirectory("terminal-still-owned").toFile()
        try {
            val partial = root.resolve("owned.part").apply { writeText("retain") }
            val lease = DownloadPrivateFileOwners.acquire(DownloadPrivateFileOwner(root, "owned"))
            val rows = Rows(row()); val cleaner = FailedDownloadPartialCleaner(root, rows, { true }, { _, _ -> false })
            try { cleaner.clean(rows.get("owned")!!); fail("Native owner is still active") } catch (_: DownloadFilesBusyException) { }
            assertEquals("retain", partial.readText())
            lease.close()
            assertEquals(1, cleaner.clean(rows.get("owned")!!).filesRemoved)
        } finally { root.deleteRecursively() }
    }

    @Test fun favouriteProtectionRefusesBeforeAnyFileIsDeleted() = runBlocking {
        val root = Files.createTempDirectory("favourite-partial").toFile()
        try {
            val partial = root.resolve("owned.part").apply { writeText("valuable") }
            val rows = Rows(row())
            try { FailedDownloadPartialCleaner(root, rows, { true }, { _, files -> files.contains(partial) }).clean(rows.get("owned")!!)
                fail("Favourite content must be retained") } catch (_: IllegalStateException) { }
            assertEquals("valuable", partial.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun aChangedSourceReceiptCannotClaimOrDeleteTheNewRepresentation() = runBlocking {
        val root = Files.createTempDirectory("changed-row").toFile()
        try {
            val partial = root.resolve("owned.part").apply { writeText("new source") }
            val rows = object : FailedDownloadPartialRows {
                val original = row()
                override suspend fun get(id: String) = original
                override suspend fun claim(captured: DownloadEntity) = false
                override suspend fun complete(captured: DownloadEntity) = false
            }
            try { FailedDownloadPartialCleaner(root, rows, { true }, { _, _ -> false }).clean(rows.get("owned")!!)
                fail("Changed rows must be recaptured") } catch (_: IllegalStateException) { }
            assertEquals("new source", partial.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun staleConfirmationCannotCleanAResumedThenFailedNewGeneration() = runBlocking {
        val root = Files.createTempDirectory("stale-confirmation").toFile()
        try {
            val partial = root.resolve("owned.part").apply { writeText("new generation") }
            val requested = row()
            val rows = Rows(requested.copy(sourceUrl = "https://example.invalid/new-token.mp4",
                createdAt = requested.createdAt + 1, bytesDownloaded = partial.length(), stage = "New transfer failed"))
            var taskEffects = 0
            val cleaner = FailedDownloadPartialCleaner(root, rows, { true }, { _, _ -> false }, { taskEffects++ })
            try { cleaner.clean(requested); fail("An old confirmation cannot claim a newer FAILED transfer") }
            catch (_: IllegalStateException) { }
            assertEquals(0, taskEffects); assertEquals(DownloadState.FAILED, rows.row.state)
            assertEquals("New transfer failed", rows.row.stage); assertEquals("new generation", partial.readText())
        } finally { root.deleteRecursively() }
    }
}
