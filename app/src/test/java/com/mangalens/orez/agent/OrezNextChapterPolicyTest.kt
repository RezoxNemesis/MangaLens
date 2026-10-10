package com.mangalens.orez.agent

import org.junit.Assert.*
import org.junit.Test

class OrezNextChapterPolicyTest {
    private val source = "https://example.org/manga/series/chapter-12/"
    private val next = "https://example.org/manga/series/chapter-13/"
    private fun scope() = OrezNextChapterScope("a".repeat(32), "b".repeat(64), 20, source, next,
        OrezNextChapterPolicy.relation(source, next), "c".repeat(64)).validated()

    @Test fun onlyLiteralUserCommandsSelectAcquisition() {
        assertTrue(OrezNextChapterRequest.isRequested("Please save the next chapter for offline reading."))
        assertFalse(OrezNextChapterRequest.isRequested("The page says: save the next chapter"))
        assertFalse(OrezNextChapterRequest.isRequested("How do I download the next chapter?"))
        assertFalse(OrezNextChapterRequest.isRequested("Save next chapter and delete my library"))
        assertEquals(next, OrezNextChapterRequest.directUrl("Save this manga chapter URL $next"))
        assertEquals(next, OrezNextChapterRequest.directUrl("Download $next"))
        assertNull(OrezNextChapterRequest.directUrl("Download https://example.org/video.mp4"))
    }
    @Test fun smallestSameSeriesSuccessorWinsOverCatalogOrderAndOtherSeries() {
        val html = "<a href='/manga/other/chapter-13/'>Next</a><a href='/manga/series/chapter-20/'>20</a>" +
            "<a rel='next' href='/manga/series/chapter-13/'>13</a><a href='/manga/series/chapter-11/'>11</a>"
        assertEquals(next, OrezNextChapterPolicy.next(html, source))
    }
    @Test fun conflictingRelNextAndEqualNumericAliasesFailClosed() {
        assertTrue(runCatching { OrezNextChapterPolicy.next("<a rel=next href='/manga/series/chapter-20/'>20</a><a href='$next'>13</a>", source) }.isFailure)
        assertTrue(runCatching { OrezNextChapterPolicy.next("<a href='$next'>13</a><a href='/manga/series/chapter/13/'>13</a>", source) }.isFailure)
    }
    @Test fun externalNextAndProtectedDialogCannotAuthorizeAChapter() {
        val html = "<a rel=next href='https://elsewhere.org/manga/series/chapter-13/'>13</a>" +
            "<form><a href='$next'>13</a></form><div role=dialog><a href='$next'>13</a></div>"
        assertTrue(runCatching { OrezNextChapterPolicy.next(html, source) }.isFailure)
    }
    @Test fun sameHostDifferentSchemeAndCaseSensitiveSeriesAreExcluded() {
        val html = "<a href='http://example.org/manga/series/chapter-13/'>13</a><a href='/manga/Series/chapter-13/'>13</a>"
        assertTrue(runCatching { OrezNextChapterPolicy.next(html, source) }.isFailure)
    }
    @Test fun documentRedirectCannotSubstituteTheCapturedCurrentChapter() {
        OrezNextChapterPolicy.requireChapterDocument(source.removeSuffix("/"), source)
        OrezNextChapterPolicy.requireChapterDocument(source.replace("12", "12.0"), source)
        listOf(next, source.replace("/series/", "/other/"), source.replace("https:", "http:"),
            source.replace("example.org", "elsewhere.org")).forEach { redirected ->
            assertTrue(redirected, runCatching { OrezNextChapterPolicy.requireChapterDocument(redirected, source) }.isFailure)
        }
    }
    @Test fun privateCredentialAndQueryChapterSourcesRemainUnavailable() {
        listOf("http://127.0.0.1/manga/series/chapter-12/", "https://user:secret@example.org/manga/series/chapter-12/",
            source + "?page=12", "https://example.org/p.png?signature=private", "https://example.org/p.png?X-Amz-Credential=private")
            .forEach { url -> assertTrue(url, runCatching { if (url.contains("p.png")) OrezNextChapterPolicy.publicUrl(url) else OrezNextChapterPolicy.chapterUrl(url) }.isFailure) }
        assertEquals("https://cdn.example.org/p.png?w=1200&fit=cover", OrezNextChapterPolicy.publicUrl("https://cdn.example.org/p.png?w=1200&fit=cover").toString())
    }
    @Test fun challengeAndOverBudgetCatalogDoNotYieldPartialSuccessors() {
        assertTrue(runCatching { OrezNextChapterPolicy.next("<form id=challenge-form></form><a href='$next'>13</a>", source) }.isFailure)
        assertTrue(runCatching { OrezNextChapterPolicy.next((0..2000).joinToString("") { "<a href='$next'>13</a>" }, source) }.isFailure)
    }
    @Test fun scopeRoundTripCannotReplaceCapturedTargetOrRelation() {
        val captured = OrezChapterAcquisitionScope(next, scope()).validated()
        assertEquals(captured, OrezChapterAcquisitionScope.decode(captured.encode()))
        assertEquals(captured.fingerprint, OrezChapterAcquisitionScope.decode(captured.encode()).fingerprint)
        assertTrue(runCatching { scope().copy(targetUrl = next.replace("13", "14")).validated() }.isFailure)
        assertTrue(runCatching { captured.copy(targetUrl = source).validated() }.isFailure)
    }
    @Test fun nativePlansRequireExactUserScopeAndNeverBecomeMediaDownloads() {
        val directScope = OrezChapterAcquisitionScope(next)
        val direct = OrezAgentRuntime().decide("Save chapter $next", OrezAgentContext(chapterAcquisition = directScope))
        assertEquals("save_chapter_url", direct.plan!!.steps.single().call.name)
        assertNull(direct.immediateRoute)
        assertEquals(OrezTaskStatus.RUNNING, direct.plan!!.status)
        assertTrue(OrezDurablePlanRules.requiresNetwork(direct.plan!!))
        val context = OrezAgentContext(activeChapterId = "a".repeat(32), chapterAcquisition = OrezChapterAcquisitionScope(next, scope()))
        assertEquals("save_next_chapter", OrezAgentRuntime().decide("Save next chapter", context).plan!!.steps.single().call.name)
        assertFalse(OrezToolRegistry().catalog().contains("save_next_chapter"))
        assertFalse(OrezToolRegistry().catalog().contains("save_chapter_url"))
    }
    @Test fun proposedModelDestinationCannotWidenAUserCapture() {
        val request = "Save chapter $next"
        val plan = OrezTaskPlan(objective = request, steps = listOf(OrezPlanStep(0,
            OrezToolRegistry().call("save_chapter_url", mapOf("value" to "https://elsewhere.org/chapter")))))
        assertEquals(OrezTaskStatus.FAILED, OrezAgentRuntime().decidePlan(plan,
            OrezAgentContext(chapterAcquisition = OrezChapterAcquisitionScope(next))).plan!!.status)
        assertEquals(OrezTaskStatus.FAILED, OrezAgentRuntime().decidePlan(plan.copy(steps = listOf(OrezPlanStep(0,
            OrezToolRegistry().call("save_chapter_url", mapOf("value" to next))))),
            OrezAgentContext(origin = OrezTrustOrigin.WEB_CONTENT, explicitUserRequest = false, chapterAcquisition = OrezChapterAcquisitionScope(next))).plan!!.status)
    }
}
