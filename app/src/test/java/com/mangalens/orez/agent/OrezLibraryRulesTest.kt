package com.mangalens.orez.agent
import org.junit.Assert.*
import org.junit.Test

class OrezLibraryRulesTest {
    private val id="a".repeat(32)
    private val request=OrezLibraryRequest(OrezLibraryOperation.BOOKMARK,bookmarked=true)
    private val scope=OrezLibraryScope.capture(request,id,listOf(OrezLibraryManifestRevision(id,"b".repeat(64),100,"c".repeat(64))),false)
    private fun plan()=OrezAgentRuntime().decide("Bookmark this chapter",OrezAgentContext(activeChapterId=id,hasActiveChapter=true,libraryScope=scope)).plan!!
    @Test fun trustedCapturedCommandIsDurableOfflineAndModelExcluded() {
        val plan=plan();OrezDurablePlanRules.validate(plan);assertTrue(OrezDurablePlanRules.supports(plan));assertFalse(OrezDurablePlanRules.requiresNetwork(plan))
        assertEquals(OrezOutputKind.LIBRARY_METADATA,OrezDurablePlanRules.outputKind(plan.steps.single().call.name))
        assertFalse(OrezToolRegistry().catalog().contains("set_chapter_bookmark"))
    }
    @Test fun capturedLibraryRequestDoesNotPersistAnUnrelatedAmbientBrowserUrl() {
        val decision=OrezAgentRuntime().decide("Bookmark this chapter",OrezAgentContext(activeChapterId=id,hasActiveChapter=true,
            activeUrl="https://example.com/private-source",libraryScope=scope))
        val plan=decision.plan!!;assertTrue(plan.authorization!!.urls.isEmpty());assertNull(plan.authorization!!.translation)
        assertTrue(runCatching { OrezLibraryRules.validate(plan.copy(authorization=plan.authorization!!.copy(urls=setOf("https://example.com")))) }.isFailure)
    }
    @Test fun runtimeDiscardsModelProposedScopeInsteadOfBorrowingAuthority() {
        val decision=OrezAgentRuntime().decidePlan(plan(),OrezAgentContext(activeChapterId=id))
        assertEquals(OrezTaskStatus.FAILED,decision.plan!!.status);assertNull(decision.plan!!.authorization!!.libraryScope)
    }
    @Test fun importedOrPageContentCannotGrantLiteralLibraryMutation() {
        val plan=plan()
        for(origin in listOf(OrezTrustOrigin.WEB_CONTENT,OrezTrustOrigin.IMPORTED_CONTENT,OrezTrustOrigin.APP_STATE)) {
            assertTrue(runCatching { OrezDurablePlanRules.validate(plan.copy(authorization=plan.authorization!!.copy(origin=origin))) }.isFailure)
        }
    }
    @Test fun changedArgumentsChapterOrObjectiveCannotReuseCapturedScope() {
        val plan=plan()
        listOf(plan.copy(objective="Unbookmark this chapter"),plan.copy(authorization=plan.authorization!!.copy(chapterIds=setOf("d".repeat(32)))),
            plan.copy(steps=listOf(plan.steps.single().copy(call=plan.steps.single().call.copy(arguments=mapOf("chapterId" to id,"bookmarked" to "false"))))))
            .forEach { assertTrue(runCatching { OrezDurablePlanRules.validate(it) }.isFailure) }
    }
    @Test fun metadataCannotAuthorizeChainedNetworkOrTranslationEffects() {
        val plan=plan();val extra=OrezPlanStep(1,OrezToolRegistry().call("open_library",emptyMap()))
        assertTrue(runCatching { OrezDurablePlanRules.validate(plan.copy(steps=plan.steps+extra)) }.isFailure)
        assertTrue(runCatching { OrezDurablePlanRules.validate(plan.copy(steps=listOf(plan.steps.single().copy(references=mapOf("chapterId" to OrezOutputReference(0,OrezOutputField.CHAPTER_ID)))))) }.isFailure)
    }
}
