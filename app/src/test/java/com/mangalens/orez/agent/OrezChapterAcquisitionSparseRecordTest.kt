package com.mangalens.orez.agent

import com.mangalens.core.acquisition.ChapterImageCandidate
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.ChapterPageAcquisitionPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** AUTHORED_UNRUN: exact sparse-record contract; no completed original acquisition is inferred. */
class OrezChapterAcquisitionSparseRecordTest {
    @Test fun sparseVerifiedOriginalsRetainTheirCatalogOrdinals() = fixture { f ->
        val record = f.record(listOf(f.success(1), f.success(3)))
        assertSame(record, record.validate(f.directory))
        assertEquals(listOf(1, 3), record.pages.map { it.index })
        assertFalse(record.allPagesAcquired)
    }

    @Test fun middleFailurePreservesLaterOriginalAndCannotCompleteByListLength() = fixture { f ->
        val record = f.record(listOf(f.success(1), f.failure(2), f.success(3)))
        record.validate(f.directory)
        assertEquals(3, record.pages.size)
        assertFalse(record.allPagesAcquired)
        reject { record.copy(completed = true).validate(f.directory) }
    }

    @Test fun allExactVerifiedRowsCanCompleteAndSparseHoleCannot() = fixture { f ->
        val complete = f.record((1..3).map(f::success)).copy(completed = true)
        complete.validate(f.directory)
        assertTrue(complete.allPagesAcquired)
        reject { complete.copy(pages = listOf(complete.pages[0], complete.pages[2])).validate(f.directory) }
    }

    @Test fun duplicateIndicesCannotStandInForMissingCandidate() = fixture { f ->
        reject { f.record(listOf(f.success(1), f.success(1), f.success(3))).validate(f.directory) }
    }

    @Test fun outOfRangeIndexIsRejectedBeforeCandidateAccess() = fixture { f ->
        reject { f.record(listOf(f.success(1).copy(index = 0))).validate(f.directory) }
        reject { f.record(listOf(f.success(3).copy(index = 4))).validate(f.directory) }
    }

    @Test fun exactSourceUrlMustMatchOriginalIndexRatherThanRowOffset() = fixture { f ->
        reject { f.record(listOf(f.success(3).copy(sourceUrl = f.candidates[0].url))).validate(f.directory) }
        reject { f.record(listOf(f.failure(2).copy(sourceUrl = f.candidates[2].url))).validate(f.directory) }
    }

    @Test fun ownedFilenameOrdinalMustMatchSparseCatalogIndex() = fixture { f ->
        val wrongPath = File(f.directory, "owned_${f.namespace}_1_${OrezNextChapterPolicy.sha(f.candidates[2].url).take(16)}.img")
        reject { f.record(listOf(f.success(3).copy(localPath = wrongPath.path))).validate(f.directory) }
    }

    @Test fun fixedFailureRowsCannotBorrowOriginalBytesOrProof() = fixture { f ->
        val failed = f.failure(2)
        f.record(listOf(failed)).validate(f.directory)
        reject { f.record(listOf(failed.copy(localPath = f.success(2).localPath))).validate(f.directory) }
        reject { f.record(listOf(failed.copy(contentRevision = "d".repeat(64)))).validate(f.directory) }
        reject { f.record(listOf(failed.copy(error = "foreign diagnostic"))).validate(f.directory) }
    }

    @Test fun successMustHaveRevisionAndMustNotCarryFailure() = fixture { f ->
        reject { f.record(listOf(f.success(2).copy(contentRevision = null))).validate(f.directory) }
        reject { f.record(listOf(f.success(2).copy(error = ChapterPageAcquisitionPolicy.FAILURE))).validate(f.directory) }
    }

    @Test fun repairingOnlyTheMiddleRowPreservesOtherVerifiedOriginals() = fixture { f ->
        val first = f.success(1)
        val last = f.success(3)
        val before = f.record(listOf(first, f.failure(2), last))
        before.validate(f.directory)
        val repaired = before.copy(pages = before.pages.map { if (it.index == 2) f.success(2) else it })
        repaired.validate(f.directory)
        assertSame(first, repaired.pages[0])
        assertSame(last, repaired.pages[2])
        assertEquals(before.candidates, repaired.candidates)
        assertEquals(before.scopeFingerprint, repaired.scopeFingerprint)
        assertTrue(repaired.allPagesAcquired)
        repaired.copy(completed = true).validate(f.directory)
    }

    private class Fixture(val directory: File) {
        val request = "orez-sparse-owned"
        val namespace = OrezNextChapterPolicy.sha(request).take(16)
        val candidates = (1..3).map { ChapterImageCandidate("https://cdn.example.org/page-$it.png") }
        fun success(index: Int): ChapterPage {
            val url = candidates[index - 1].url
            val path = File(directory, "owned_${namespace}_${index}_${OrezNextChapterPolicy.sha(url).take(16)}.img")
            val bytes = byteArrayOf(index.toByte(), 7, 9)
            path.writeBytes(bytes)
            return ChapterPage(index, url, path.path, contentRevision = OrezNextChapterPolicy.sha(bytes))
        }
        fun failure(index: Int) = ChapterPageAcquisitionPolicy.failure(index, candidates[index - 1].url)
        fun record(pages: List<ChapterPage>) = OrezChapterAcquisitionRecord(
            request, "a".repeat(64), "Chapter", "b".repeat(64), candidates, pages
        )
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("native-sparse-record-").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Invalid sparse native evidence was accepted") }
        catch (_: IllegalArgumentException) { /* Expected shape/proof rejection. */ }
    }
}
