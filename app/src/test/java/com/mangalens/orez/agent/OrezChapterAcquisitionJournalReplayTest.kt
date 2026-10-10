package com.mangalens.orez.agent

import com.mangalens.core.acquisition.ChapterImageCandidate
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.ChapterPageAcquisitionPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** AUTHORED_UNRUN: uses the production bounded serializers, not Android/private stream authority. */
class OrezChapterAcquisitionJournalReplayTest {
    @Test fun schemaOneSuccessfulPrefixRetainsLegacyOrdinalAndProof() = fixture { f ->
        val first = f.success(1)
        val old = f.envelope(1).put("pages", JSONArray().put(JSONObject()
            .put("path", first.localPath).put("revision", first.contentRevision)))
        val restored = f.decode(old)
        assertEquals(listOf(first), restored.pages)
        assertFalse(restored.completed)
        assertFalse(restored.allPagesAcquired)
        assertEquals(f.candidates, restored.candidates)
    }

    @Test fun schemaTwoKeepsSparseFailureAndLaterVerifiedOrdinal() = fixture { f ->
        val record = f.record(listOf(f.success(1), f.failure(2), f.success(3)))
        val encoded = OrezChapterAcquisitionJournal.encodeRecord(record, f.request, f.images)
        assertEquals(2, JSONObject(encoded.toString(Charsets.UTF_8)).getInt("schema"))
        val restored = OrezChapterAcquisitionJournal.decodeRecord(encoded, f.request, f.images)
        assertEquals(record, restored)
        assertEquals(listOf(1, 2, 3), restored.pages.map { it.index })
        assertNull(restored.pages[1].localPath)
        assertNull(restored.pages[1].contentRevision)
        assertEquals(ChapterPageAcquisitionPolicy.FAILURE, restored.pages[1].error)
        assertFalse(restored.completed)
        assertFalse(restored.allPagesAcquired)
    }

    @Test fun journalNeverReplaysCompletedTrueWithAFailedCatalogRow() = fixture { f ->
        val record = f.record(listOf(f.success(1), f.failure(2), f.success(3)))
        reject { OrezChapterAcquisitionJournal.encodeRecord(record.copy(completed = true), f.request, f.images) }
        val json = JSONObject(OrezChapterAcquisitionJournal.encodeRecord(record, f.request, f.images).toString(Charsets.UTF_8))
        reject { f.decode(json.put("completed", true)) }
    }

    @Test fun isolatedRepairPreservesOtherOriginalPathsAndRevisionsInCompleteReplay() = fixture { f ->
        val before = f.record(listOf(f.success(1), f.failure(2), f.success(3)))
        val repaired = before.copy(pages = before.pages.map { if (it.index == 2) f.success(2) else it }, completed = true)
        val restored = OrezChapterAcquisitionJournal.decodeRecord(
            OrezChapterAcquisitionJournal.encodeRecord(repaired, f.request, f.images), f.request, f.images)
        assertEquals(repaired, restored)
        assertEquals(before.pages[0].localPath, restored.pages[0].localPath)
        assertEquals(before.pages[0].contentRevision, restored.pages[0].contentRevision)
        assertEquals(before.pages[2].localPath, restored.pages[2].localPath)
        assertEquals(before.pages[2].contentRevision, restored.pages[2].contentRevision)
        assertTrue(restored.allPagesAcquired)
        assertTrue(restored.completed)
    }

    @Test fun wrongRequestCannotReplayOrWriteAnotherOwnersOriginals() = fixture { f ->
        val record = f.record(listOf(f.success(1)))
        val bytes = OrezChapterAcquisitionJournal.encodeRecord(record, f.request, f.images)
        reject { OrezChapterAcquisitionJournal.decodeRecord(bytes, "orez-other-request", f.images) }
        reject { OrezChapterAcquisitionJournal.encodeRecord(record, "orez-other-request", f.images) }
    }

    @Test fun futureSchemaFailsClosedInsteadOfPretendingItIsLegacyPrefix() = fixture { f ->
        val bytes = OrezChapterAcquisitionJournal.encodeRecord(f.record(listOf(f.success(1))), f.request, f.images)
        val json = JSONObject(bytes.toString(Charsets.UTF_8)).put("schema", 3)
        reject { f.decode(json) }
    }

    private class Fixture(val images: File) {
        val request = "orez-journal-replay"
        val namespace = OrezNextChapterPolicy.sha(request).take(16)
        val candidates = (1..3).map { ChapterImageCandidate("https://cdn.example.org/page-$it.png") }
        fun success(index: Int): ChapterPage {
            val url = candidates[index - 1].url
            val path = File(images, "owned_${namespace}_${index}_${OrezNextChapterPolicy.sha(url).take(16)}.img")
            val bytes = byteArrayOf(index.toByte(), 3, 4)
            path.writeBytes(bytes)
            return ChapterPage(index, url, path.path, contentRevision = OrezNextChapterPolicy.sha(bytes))
        }
        fun failure(index: Int) = ChapterPageAcquisitionPolicy.failure(index, candidates[index - 1].url)
        fun record(pages: List<ChapterPage>) = OrezChapterAcquisitionRecord(request, "a".repeat(64), "Chapter", "b".repeat(64), candidates, pages)
        fun envelope(schema: Int) = JSONObject().put("schema", schema).put("requestId", request)
            .put("scopeFingerprint", "a".repeat(64)).put("documentSha256", "b".repeat(64)).put("title", "Chapter")
            .put("completed", false).put("candidates", JSONArray(candidates.map {
                JSONObject().put("url", it.url).put("promotion", JSONObject.NULL)
            }))
        fun decode(json: JSONObject) = OrezChapterAcquisitionJournal.decodeRecord(json.toString().toByteArray(Charsets.UTF_8), request, images)
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("native-journal-replay-").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Invalid native journal replay was accepted") }
        catch (_: IllegalArgumentException) { /* Expected scope/schema/proof rejection. */ }
    }
}
