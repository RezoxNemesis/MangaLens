package com.mangalens.orez.agent

import com.mangalens.core.acquisition.ChapterImageCandidate
import com.mangalens.core.reader.ChapterPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OrezChapterAcquisitionRecordTest {
    @Test fun exactNativeFileOwnerRejectsOtherRequestAndUnmanagedPaths() {
        val directory = Files.createTempDirectory("native-record-").toFile()
        try {
            val request = "orez-owned"; val url = "https://cdn.example.org/one.png"
            val namespace = OrezNextChapterPolicy.sha(request).take(16)
            val image = File(directory, "owned_${namespace}_1_${OrezNextChapterPolicy.sha(url).take(16)}.img").apply { writeBytes(byteArrayOf(1, 2)) }
            val record = OrezChapterAcquisitionRecord(request, "a".repeat(64), "Chapter", "b".repeat(64), listOf(ChapterImageCandidate(url)),
                listOf(ChapterPage(1, url, image.path, contentRevision = OrezNextChapterPolicy.sha(image.readBytes()))))
            record.validate(directory)
            assertTrue(runCatching { record.copy(requestId = "orez-different").validate(directory) }.isFailure)
            assertTrue(runCatching { record.copy(pages = listOf(record.pages.single().copy(localPath = File(directory, "../elsewhere.img").path))).validate(directory) }.isFailure)
            assertTrue(runCatching { record.copy(pages = emptyList(), completed = true).validate(directory) }.isFailure)
        } finally { directory.deleteRecursively() }
    }
    @Test fun sameLengthOriginalMutationCannotReuseItsSavedFingerprint() = runTest {
        val directory = Files.createTempDirectory("native-source-mutation-").toFile()
        try {
            val image = File(directory, "owned_0123456789abcdef_1_0123456789abcdef.img").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val id = "a".repeat(32)
            val expected = OrezChapterSourceEvidence.snapshot(id, "Chapter", listOf(OrezChapterSource(1, image.path, OrezNextChapterPolicy.sha(image.readBytes()))))
            image.writeBytes(byteArrayOf(3, 2, 1))
            val actual = OrezChapterSourceEvidence.inspectBounded(directory, id, "Chapter", listOf(OrezChapterSource(1, image.path)), 32)
            assertNotEquals(expected.sourceFingerprint, actual.first.sourceFingerprint)
            assertEquals(3L, actual.second)
        } finally { directory.deleteRecursively() }
    }
}
