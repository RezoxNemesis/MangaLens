package com.mangalens.orez.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OrezChapterSourceEvidenceTest {
    @Test fun sourceContentPageIdentityAndManagedFilenameBindTheReceipt() = runTest {
        val root = Files.createTempDirectory("orez-source-evidence").toFile()
        try {
            val first = File(root, "one.png").apply { writeText("original explicit source") }
            val second = File(root, "two.png").apply { writeText("second explicit source") }
            val id = "a".repeat(32)
            val pages = listOf(OrezChapterSource(1, first.path), OrezChapterSource(2, second.path))
            val before = OrezChapterSourceEvidence.inspect(root, id, "Chapter", pages)
            assertEquals(before.sourceFingerprint, OrezChapterSourceEvidence.inspect(root, id, "Renamed chapter", pages.reversed()).sourceFingerprint)
            first.writeText("replaced explicit source")
            assertNotEquals(before.sourceFingerprint, OrezChapterSourceEvidence.inspect(root, id, "Chapter", pages).sourceFingerprint)
            assertNotEquals(before.sourceFingerprint, OrezChapterSourceEvidence.inspect(root, id, "Chapter", pages.map { it.copy(index = it.index + 1) }).sourceFingerprint)
        } finally { root.deleteRecursively() }
    }

    @Test fun arbitraryFilesTraversalAndSymlinksCannotEnterNativeScope() = runTest {
        val root = Files.createTempDirectory("orez-source-scope").toFile()
        try {
            val managed = File(root, "chapters").apply { mkdirs() }
            val outside = File(root, "private.txt").apply { writeText("must not be read by a chapter tool") }
            val linked = File(managed, "link.png")
            Files.createSymbolicLink(linked.toPath(), outside.toPath())
            for (path in listOf(outside.path, File(managed, "../private.txt").path, linked.path)) {
                val result = runCatching { OrezChapterSourceEvidence.inspect(managed, "a".repeat(32), "Chapter", listOf(OrezChapterSource(0, path))) }
                assertTrue("Outside sources must fail before their bytes become a receipt", result.exceptionOrNull() is IllegalArgumentException)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun missingDuplicateAndEmptyPagesCannotCreateACompleteSourceScope() = runTest {
        val root = Files.createTempDirectory("orez-source-missing").toFile()
        try {
            val empty = File(root, "empty.png").apply { createNewFile() }
            val valid = File(root, "valid.png").apply { writeText("source") }
            for (pages in listOf(listOf(OrezChapterSource(0, null)), listOf(OrezChapterSource(0, empty.path)),
                listOf(OrezChapterSource(0, valid.path), OrezChapterSource(0, valid.path)))) {
                assertTrue(runCatching { OrezChapterSourceEvidence.inspect(root, "a".repeat(32), "Chapter", pages) }.isFailure)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun cancelledInspectionCannotPublishAReceipt() = runTest {
        val root = Files.createTempDirectory("orez-source-cancelled").toFile()
        try {
            val source = File(root, "valid.png").apply { writeText("source") }
            var published = false
            val job = launch {
                cancel()
                try {
                    OrezChapterSourceEvidence.inspect(root, "a".repeat(32), "Chapter", listOf(OrezChapterSource(0, source.path)))
                    published = true
                } catch (_: CancellationException) { }
            }
            job.join()
            assertFalse(published)
        } finally { root.deleteRecursively() }
    }
}
