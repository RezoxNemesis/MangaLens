package com.mangalens.core.reader

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import com.mangalens.ui.downloads.OwnedSavedVideoProbe
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Authored under implementation-first sequence: compilation/execution and Android IO are pending. */
class ChapterCbzExportTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = "a".repeat(32)
    private fun chapter(pages: List<ChapterPage> = listOf(ChapterPage(5, "https://example.test/one", "/managed/unsafe-name.png"))) =
        SavedChapter(id, "A / title", "https://example.test/chapter", pages)
    private fun scope() = ChapterCbzScope.capture(chapter())
    private fun failure(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IOException) {} }

    @Test fun captureCopiesPageMembershipAndDocumentFields() {
        val list = mutableListOf(ChapterPage(5, "one", "/managed/one"))
        val captured = ChapterCbzScope.capture(chapter(list)); list.clear()
        assertEquals(1, captured.pages.size)
        try { (captured.pages as MutableList).clear(); fail("Mutable scope") } catch (_: UnsupportedOperationException) {}
    }
    @Test fun notesPositionBookmarkAndTitleDoNotRevokeOriginalScope() {
        val original = chapter(); val captured = ChapterCbzScope.capture(original)
        assertTrue(captured.matches(original.copy(title = "renamed", notes = "private", bookmarked = true, position = 4, updatedAt = 99)))
    }
    @Test fun reorderedAddedRemovedAndChangedPageMembersAreRejected() {
        val original = chapter(listOf(ChapterPage(5, "one", "/managed/one"), ChapterPage(2, "two", "/managed/two")))
        val captured = ChapterCbzScope.capture(original)
        assertFalse(captured.matches(original.copy(pages = original.pages.reversed())))
        assertFalse(captured.matches(original.copy(pages = original.pages.drop(1))))
        assertFalse(captured.matches(original.copy(pages = original.pages + ChapterPage(3, "three", "/managed/three"))))
        assertFalse(captured.matches(original.copy(pages = listOf(original.pages[0].copy(sourceUrl = "new"), original.pages[1]))))
        assertFalse(captured.matches(original.copy(pages = listOf(original.pages[0].copy(localPath = "/managed/replaced"), original.pages[1]))))
        assertFalse(captured.matches(null))
    }
    @Test fun missingDuplicateNegativeAndOversizedScopeFailClosed() {
        failure { ChapterCbzScope.capture(chapter(listOf(ChapterPage(1, "one")))) }
        failure { ChapterCbzScope.capture(chapter(listOf(ChapterPage(1, "one", "/managed/one"), ChapterPage(1, "two", "/managed/two")))) }
        failure { ChapterCbzScope.capture(chapter(listOf(ChapterPage(-1, "one", "/managed/one")))) }
        failure { ChapterCbzScope.capture(chapter((0..1000).map { ChapterPage(it, "source", "/managed/$it") })) }
    }
    @Test fun changedDocumentIdentityRejectsEvenWithSameManagedPage() {
        val doc = ChapterDocumentSource("content://provider/document/1", "pdf", 0, "1".repeat(64))
        val original = chapter(listOf(ChapterPage(5, "one", "/managed/one", documentSource = doc)))
        assertFalse(ChapterCbzScope.capture(original).matches(original.copy(pages = listOf(original.pages[0].copy(documentSource = doc.copy(documentSha256 = "2".repeat(64)))))))
    }
    @Test fun fingerprintIsLengthDelimitedAndExcludesPrivateNotes() {
        val first = chapter(listOf(ChapterPage(5, "a|b", "/managed/c")))
        val second = chapter(listOf(ChapterPage(5, "a", "/managed/b|c")))
        assertNotEquals(ChapterCbzScope.capture(first).fingerprint(), ChapterCbzScope.capture(second).fingerprint())
        assertEquals(ChapterCbzScope.capture(first).fingerprint(), ChapterCbzScope.capture(first.copy(notes = "secret")).fingerprint())
    }
    @Test fun namesAreGeneratedAndUnsupportedExtensionsReject() {
        assertEquals("00001.png", ChapterCbzPolicy.entryName(0, "png"))
        assertEquals("01000.jpg", ChapterCbzPolicy.entryName(999, "jpg"))
        failure { ChapterCbzPolicy.entryName(0, "../../evil") }
        failure { ChapterCbzPolicy.entryName(1000, "png") }
        assertFalse(ChapterCbzPolicy.suggestedName("../../secret\\file\n").contains('/'))
        assertFalse(ChapterCbzPolicy.suggestedName("../../secret\\file\n").contains('\\'))
    }
    @Test fun exactBudgetBoundaryAcceptedAndByteOverflowRejectedBeforeAdmission() {
        assertEquals(ChapterCbzPolicy.MAX_ARCHIVE_BYTES, ChapterCbzPolicy.admitPage(ChapterCbzPolicy.MAX_ARCHIVE_BYTES - 1, 1))
        failure { ChapterCbzPolicy.admitPage(ChapterCbzPolicy.MAX_ARCHIVE_BYTES, 1) }
        failure { ChapterCbzPolicy.admitPage(0, ChapterCbzPolicy.MAX_PAGE_BYTES + 1) }
        failure { ChapterCbzPolicy.admitPage(Long.MAX_VALUE, 1) }
        failure { ChapterCbzPolicy.admitPage(-1, 1) }
    }
    @Test fun archiveLimitPreventsUnderlyingOverflowWrite() {
        val out = ByteArrayOutputStream(); val limited = ChapterCbzLimitedOutput(out, 3) {}
        limited.write(byteArrayOf(1, 2, 3)); failure { limited.write(4) }
        assertArrayEquals(byteArrayOf(1, 2, 3), out.toByteArray())
        failure { limited.write(byteArrayOf(1, 2), 0, 2) }; assertEquals(3, out.size())
    }
    private class Originals(private val bytes: List<ByteArray>, private val afterCopy: () -> Unit = {}) : ChapterCbzOriginals {
        var opened = 0; var closed = 0; var verified = 0; var rejectVerify = false
        override fun open(page: ChapterCbzPage, check: () -> Unit): ChapterCbzHeldOriginal {
            val index = opened++; val content = bytes[index]
            val digest = MessageDigest.getInstance("SHA-256").digest(content).cbzHex()
            val capturedPin = ChapterCbzPagePin(digest, content.size.toLong(), CRC32().apply { update(content) }.value, "png", ChapterCbzFileIdentity(1, index.toLong(), content.size.toLong(), 2))
            return object : ChapterCbzHeldOriginal {
                override val pin = capturedPin
                override fun copyTo(output: OutputStream, check: () -> Unit) { check(); output.write(content); afterCopy(); check() }
                override fun close() { closed++ }
            }
        }
        override fun verify(page: ChapterCbzPage, pin: ChapterCbzPagePin, check: () -> Unit) { check(); verified++; if (rejectVerify) throw IOException("changed original") }
    }
    @Test fun realZipPreservesCapturedOrderOriginalBytesAndPageReceipts() {
        val saved = chapter(listOf(ChapterPage(8, "source-eight", "/managed/../../eight.png"), ChapterPage(2, "source-two", "/managed/two.png")))
        val originals = Originals(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)))
        val artifact = ChapterCbzArchive(temp.newFolder(), originals) { true }.prepare(ChapterCbzScope.capture(saved), ChapterCbzOperation()) {}
        ZipFile(artifact.file).use { zip ->
            assertEquals(listOf("00001.png", "00002.png"), zip.entries().asSequence().map { it.name }.toList())
            assertArrayEquals(byteArrayOf(1, 2, 3), zip.getInputStream(zip.getEntry("00001.png")).use { it.readBytes() })
            assertArrayEquals(byteArrayOf(4, 5), zip.getInputStream(zip.getEntry("00002.png")).use { it.readBytes() })
            assertEquals(ZipEntryMethod.STORED, zip.getEntry("00001.png").method)
        }
        assertEquals(listOf(8, 2), artifact.receipt.pageHashes.map { it.sourceIndex })
        try { (artifact.receipt.pageHashes as MutableList).clear(); fail("Mutable page hashes") } catch (_: UnsupportedOperationException) {}
        assertEquals(5L, artifact.receipt.originalBytes); assertEquals(2, originals.closed); assertEquals(2, originals.verified)
        assertEquals(chapterCbzHash(artifact.file, ChapterCbzPolicy.MAX_ARCHIVE_BYTES) {}, artifact.receipt.archiveSha256)
    }
    @Test fun sourceRetirementDuringCopyClosesHeldSourceAndDeletesSpool() {
        val directory = temp.newFolder(); var current = true
        val originals = Originals(listOf(byteArrayOf(1, 2))) { current = false }
        failure { ChapterCbzArchive(directory, originals) { current }.prepare(scope(), ChapterCbzOperation()) {} }
        assertEquals(1, originals.closed); assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun changedOriginalAtFinalRecheckDoesNotReturnArtifact() {
        val directory = temp.newFolder(); val originals = Originals(listOf(byteArrayOf(1, 2))).apply { rejectVerify = true }
        failure { ChapterCbzArchive(directory, originals) { true }.prepare(scope(), ChapterCbzOperation()) {} }
        assertTrue(directory.listFiles().orEmpty().isEmpty()); assertEquals(1, originals.closed)
    }
    @Test fun cancellationCheckClosesOriginalAndDeletesPrivateArtifact() {
        val directory = temp.newFolder(); var retired = false
        val originals = Originals(listOf(byteArrayOf(1, 2))) { retired = true }
        try { ChapterCbzArchive(directory, originals) { true }.prepare(scope(), ChapterCbzOperation()) { if (retired) throw CancellationException("retired") }; fail("cancelled") }
        catch (_: CancellationException) {}
        assertEquals(1, originals.closed); assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun changedPrivateArchiveIsRejectedByStreamingOutputReceipt() {
        val artifact = ChapterCbzArchive(temp.newFolder(), Originals(listOf(byteArrayOf(1, 2)))) { true }.prepare(scope(), ChapterCbzOperation()) {}
        val bytes = artifact.file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); artifact.file.writeBytes(bytes)
        failure { chapterCbzCopyArchive(artifact, ByteArrayOutputStream()) {} }
    }
    @Test fun originalArtifactCopiesExactlyWithBoundShaReceipt() {
        val artifact = ChapterCbzArchive(temp.newFolder(), Originals(listOf(byteArrayOf(1, 2)))) { true }.prepare(scope(), ChapterCbzOperation()) {}
        val out = ByteArrayOutputStream(); chapterCbzCopyArchive(artifact, out) {}
        assertArrayEquals(artifact.file.readBytes(), out.toByteArray())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).cbzHex(), artifact.receipt.archiveSha256)
    }
    @Test fun retiredPreparationReleasesActualZipWithoutFooterWritesAndAdmitsNextExport() = runBlocking {
        val directory = temp.newFolder(); val probe = OwnedSavedVideoProbe(); val retired = AtomicBoolean(false)
        try {
            probe.run { owner ->
                val resources = ChapterCbzResources(); owner.own(resources); resources.beginPrivateWork()
                try {
                    val originals = Originals(listOf(byteArrayOf(1, 2))) { retired.set(true) }
                    ChapterCbzArchive(directory, originals, resources) { true }.prepare(scope(), ChapterCbzOperation()) {
                        owner.checkActive(); if (retired.get()) throw CancellationException("request retired during entry copy")
                    }
                } finally { resources.finishPrivateWork() }
            }
            fail("Retired request accepted")
        } catch (_: CancellationException) {}
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        var nextEntered = false
        probe.run { nextEntered = true }
        assertTrue("Ordinary cancellation must not become a retained cleanup failure", nextEntered)
    }
    private object ZipEntryMethod { const val STORED = 0 }
}
