package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrezChapterAcquisitionStoreTest {
    private val url = "https://example.org/manga/series/chapter-2/"
    private fun plan() = OrezAgentRuntime().decide("Save chapter $url", OrezAgentContext(chapterAcquisition = OrezChapterAcquisitionScope(url))).plan!!
    @Test fun schemaFiveRestoresCapturedScopeWithoutAmbientResolution() = runTest {
        val dao = FakeDao(); val store = OrezTaskStore(dao); val request = plan()
        assertTrue(store.checkpoint(request))
        assertEquals(5, JSONObject(dao.saved!!.planJson).getInt("schema"))
        assertEquals(request, store.load(request.id))
        val legacy = OrezTaskPlan(objective = "Open library", steps = listOf(OrezPlanStep(0, OrezToolRegistry().call("open_library", emptyMap()))))
        val other = OrezTaskStore(FakeDao()); assertTrue(other.checkpoint(legacy))
        assertNull(other.load(legacy.id)!!.authorization?.chapterAcquisition)
    }
    @Test fun pauseBeforePublicationPreventsTheLibraryCallback() = runTest {
        val store = OrezTaskStore(FakeDao()); val request = plan(); assertTrue(store.checkpoint(request))
        assertNotNull(store.pause(request.id))
        var wroteLibrary = false
        val outcome = runCatching { store.publishAcquisition(request.id, request.executionEpoch, request.authorization!!.chapterAcquisition!!) { wroteLibrary = true } }
        assertTrue(outcome.isFailure); assertFalse(wroteLibrary)
    }
    @Test fun publicationBeforePauseRetainsItsCompletedOriginals() = runTest {
        val store = OrezTaskStore(FakeDao()); val request = plan(); store.checkpoint(request)
        var originalsSaved = false
        store.publishAcquisition(request.id, request.executionEpoch, request.authorization!!.chapterAcquisition!!) { originalsSaved = true }
        assertNotNull(store.pause(request.id)); assertTrue(originalsSaved)
        var lateReceipt = false
        assertTrue(runCatching { store.publishAcquisition(request.id, request.executionEpoch, request.authorization!!.chapterAcquisition!!) { lateReceipt = true } }.isFailure)
        assertFalse(lateReceipt)
    }
    @Test fun differentTargetCannotBorrowAnExecutingEpoch() = runTest {
        val store = OrezTaskStore(FakeDao()); val request = plan(); store.checkpoint(request)
        var wrote = false
        assertTrue(runCatching { store.publishAcquisition(request.id, request.executionEpoch,
            OrezChapterAcquisitionScope("https://example.org/manga/series/chapter-3/")) { wrote = true } }.isFailure)
        assertFalse(wrote)
    }
    @Test fun completionRejectsNavigationAndWrongOriginalOrOwnerProof() {
        val request = plan(); val id = OrezDurablePlanRules.requestId(request.id, 0); val scope = request.authorization!!.chapterAcquisition!!
        val chapter = com.mangalens.core.reader.ChapterLibrary.id(url)
        val proof = mapOf("requestId" to id, "ownerRequestId" to id, "chapterId" to chapter, "title" to "Chapter 2",
            "sourceFingerprint" to "a".repeat(64), "pageCount" to "2", "originalBytes" to "12345", "acquisitionScopeFingerprint" to scope.fingerprint,
            "targetUrlSha256" to OrezNextChapterPolicy.sha(url), "status" to "COMPLETED", "destination" to "library:$chapter")
        OrezDurablePlanRules.validateReceipt(request, request.steps.single(), proof, true)
        listOf("destination" to "reader:$url", "ownerRequestId" to "orez-another", "originalBytes" to "0", "pageCount" to "301", "acquisitionScopeFingerprint" to "b".repeat(64))
            .forEach { (key, value) -> assertTrue(key, runCatching { OrezDurablePlanRules.validateReceipt(request, request.steps.single(), proof + (key to value), true) }.isFailure) }
    }
    private class FakeDao : OrezTaskDao {
        var saved: OrezTaskEntity? = null
        override fun observeActive(): Flow<List<OrezTaskEntity>> = flowOf(saved?.let(::listOf).orEmpty())
        override suspend fun get(id: String) = saved?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { saved = task }
        override suspend fun pruneFinished(before: Long) { }
    }
}
