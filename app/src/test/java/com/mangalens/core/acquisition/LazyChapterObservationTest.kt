package com.mangalens.core.acquisition

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class LazyChapterObservationTest {
    private fun pages(vararg names: String) = names.map { ChapterImageCandidate("https://example.org/$it.png") }
    private val bottom = ChapterObservationViewport(900, 600, 1500)
    private fun snapshot(page: String, top: Long = 0, height: Long = 600, total: Long = 1000) = JSONObject.quote(
        JSONObject().put("pageUrl", page).put("images", org.json.JSONArray()).put("videos", org.json.JSONArray())
            .put("viewport", JSONObject().put("top", top).put("height", height).put("total", total)).toString())
    @Test fun staleDocumentCannotSupplyBottomOrStabilityEvidence() {
        assertNull(ChapterImageCandidates.decodeViewport(snapshot("https://example.org/old"), "https://example.org/new"))
    }
    @Test fun malformedAndUnboundedViewportAreRejected() {
        assertNull(ChapterImageCandidates.decodeViewport("null", "https://example.org/chapter"))
        assertNull(ChapterImageCandidates.decodeViewport(snapshot("https://example.org/chapter", height = 0), "https://example.org/chapter"))
        assertNull(ChapterImageCandidates.decodeViewport(snapshot("https://example.org/chapter", total = Long.MAX_VALUE), "https://example.org/chapter"))
    }
    @Test fun currentDocumentViewportIsRealBoundedBottomEvidence() {
        val result = ChapterImageCandidates.decodeViewport(snapshot("https://example.org/chapter", 400, 600, 1000), "https://example.org/chapter")
        assertEquals(ChapterObservationViewport(400, 600, 1000), result)
        assertTrue(requireNotNull(result).atBottom)
    }
    @Test fun aFirstImageDoesNotCompleteLazyDiscovery() {
        val policy = LazyChapterObservation()
        assertFalse(policy.observe(pages("one"), bottom, 0).stop)
        assertEquals(1, policy.observations)
    }
    @Test fun earlierAndMiddleLazyNodesKeepTheirObservedDocumentOrder() {
        val policy = LazyChapterObservation()
        policy.observe(pages("two", "four"), bottom, 0)
        policy.observe(pages("one", "two", "three", "four", "five"), bottom, 0)
        assertEquals(pages("one", "two", "three", "four", "five"), policy.images)
    }
    @Test fun virtualizedLaterNodesDoNotDiscardEarlierPages() {
        val policy = LazyChapterObservation()
        policy.observe(pages("one", "two"), bottom, 0)
        policy.observe(pages("two", "three"), bottom, 0)
        assertEquals(pages("one", "two", "three"), policy.images)
    }
    @Test fun threeUnchangedBottomObservationsAreRequiredAfterNewEvidence() {
        val policy = LazyChapterObservation()
        policy.observe(pages("one"), bottom, 0)
        repeat(2) { assertFalse(policy.observe(pages("one"), bottom, 0).stop) }
        val result = policy.observe(pages("one"), bottom, 0)
        assertTrue(result.stop); assertFalse(result.limited)
    }
    @Test fun heightGrowthAndNewVideosRestartStability() {
        val policy = LazyChapterObservation()
        policy.observe(pages("one"), bottom, 0)
        policy.observe(pages("one"), bottom, 0)
        assertFalse(policy.observe(pages("one"), ChapterObservationViewport(1200, 600, 1800), 0).stop)
        assertFalse(policy.observe(pages("one"), ChapterObservationViewport(1200, 600, 1800), 1).stop)
    }
    @Test fun incompleteScrollAndMalformedViewportCannotClaimStability() {
        val policy = LazyChapterObservation()
        repeat(5) { assertFalse(policy.observe(pages("one"), ChapterObservationViewport(0, 600, 10000), 0).stop) }
        repeat(5) { assertFalse(policy.observe(pages("one"), null, 0).stop) }
    }
    @Test fun anEmptyStableBottomCannotFinishBeforeDeferredChapterMediaArrives() {
        val policy = LazyChapterObservation()
        repeat(5) { assertFalse(policy.observe(emptyList(), bottom, 0).stop) }
        assertFalse(policy.observe(pages("late"), bottom, 0).stop)
        repeat(2) { assertFalse(policy.observe(pages("late"), bottom, 0).stop) }
        val end = policy.observe(pages("late"), bottom, 0)
        assertTrue(end.stop); assertFalse(end.limited)
    }
    @Test fun actualPassCapReturnsExplicitPartialStatus() {
        val policy = LazyChapterObservation()
        var result = LazyChapterObservation.Decision(false, false)
        repeat(LazyChapterObservation.MAX_OBSERVATIONS) { result = policy.observe(pages("one"), null, 0) }
        assertTrue(result.stop); assertTrue(result.limited)
        assertEquals(LazyChapterObservation.MAX_OBSERVATIONS, policy.observations)
    }
    @Test fun contradictoryObservedOrderingRemainsBoundedAndIsNeverCalledStable() {
        val policy = LazyChapterObservation()
        policy.observe(pages("one", "two"), bottom, 0)
        repeat(LazyChapterObservation.MAX_OBSERVATIONS - 1) { policy.observe(pages("two", "one"), bottom, 0) }
        assertTrue(policy.orderConflict)
        assertEquals(pages("one", "two"), policy.images)
        assertTrue(policy.limited)
    }
    @Test fun retainedUrlBytesAndCandidatesAreBoundedAcrossObservations() {
        val policy = LazyChapterObservation()
        repeat(LazyChapterObservation.MAX_OBSERVATIONS) { pass ->
            policy.observe((0 until 3000).map { ChapterImageCandidate("https://example.org/" + "a".repeat(2000) + "$pass-$it") }, null, 0)
        }
        assertTrue(policy.images.size <= ChapterImageCandidates.MAX_IMAGES)
        assertTrue(policy.images.sumOf { it.url.length.toLong() } <= LazyChapterObservation.MAX_RETAINED_URL_CHARS)
        assertTrue(policy.limited)
    }
    @Test fun explicitExtractorLimitCannotTurnAStablePrefixIntoCompleteDiscovery() {
        val policy = LazyChapterObservation()
        repeat(LazyChapterObservation.MAX_OBSERVATIONS) {
            val result = policy.observe(pages("prefix"), bottom, 0, extractionLimited = true)
            if (it < LazyChapterObservation.MAX_OBSERVATIONS - 1) assertFalse(result.stop)
            else { assertTrue(result.stop); assertTrue(result.limited) }
        }
        assertEquals(pages("prefix"), policy.images)
    }
    @Test fun structuredCurrentDocumentLimitIsPreservedAndMalformedLimitIsConservative() {
        val document = "https://example.org/chapter"
        val payload = JSONObject().put("pageUrl", document).put("images", org.json.JSONArray())
            .put("videos", org.json.JSONArray()).put("limited", true)
        assertTrue(ChapterImageCandidates.decodeLimited(JSONObject.quote(payload.toString()), document))
        assertTrue(ChapterImageCandidates.decodeLimited("null", document))
        assertTrue(ChapterImageCandidates.decodeLimited(snapshot("https://example.org/old"), document))
        assertFalse(ChapterImageCandidates.decodeLimited(snapshot(document), document))
    }
    @Test fun anExactImageCapIsConservativelyPartialEvenWithoutANewObservedNode() {
        val policy = LazyChapterObservation()
        val catalog = (0 until ChapterImageCandidates.MAX_IMAGES).map { ChapterImageCandidate("https://example.org/$it.png") }
        val result = policy.observe(catalog, bottom, 0)
        assertTrue(result.limited); assertEquals(ChapterImageCandidates.MAX_IMAGES, policy.images.size)
    }

}
