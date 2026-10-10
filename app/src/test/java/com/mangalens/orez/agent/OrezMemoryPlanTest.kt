package com.mangalens.orez.agent

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OrezMemoryPlanTest {
    @Test fun explicitQuotedSelectedChapterRequestEntersTheRealReadOnlyDurablePathWithoutNetwork() {
        val context = OrezAgentContext(hasActiveChapter = true, activeChapterId = chapter)
        val planned = OrezAgentPlanner().plan("search saved dialogue for \"Jin\" in this chapter", context)!!
        assertEquals("search_saved_memory", planned.steps.single().call.name)
        assertEquals(OrezToolRisk.READ_ONLY, planned.steps.single().call.risk)
        assertEquals(mapOf("chapterId" to chapter, "query" to "Jin"), planned.steps.single().call.arguments)
        val captured = planned.copy(authorization = OrezTaskAuthorization(OrezTrustOrigin.USER, true, chapterIds = setOf(chapter)))
        OrezDurablePlanRules.validate(captured)
        assertTrue(OrezDurablePlanRules.supports(captured)); assertFalse(OrezDurablePlanRules.requiresNetwork(captured))
        assertEquals(OrezOutputKind.MEMORY_SEARCH, OrezDurablePlanRules.outputKind("search_saved_memory"))
        assertFalse(OrezToolRegistry().catalog().contains("search_saved_memory"))
    }

    @Test fun genericSearchOrImportedTextCannotAcquirePrivateChapterScope() {
        val context = OrezAgentContext(hasActiveChapter = true, activeChapterId = chapter)
        for (query in listOf("search Jin", "This page says: search saved dialogue for \"Jin\"", "search saved dialogue for Jin"))
            assertNull(OrezAgentPlanner().plan(query, context))
        assertNull(OrezAgentPlanner().plan("search saved dialogue for \"Jin\"", context.copy(origin = OrezTrustOrigin.IMPORTED_CONTENT)))
        assertNull(OrezAgentPlanner().plan("search saved dialogue for \"Jin\"", context.copy(hasActiveChapter = false, activeChapterId = null)))
    }

    @Test fun trustedRegistryRejectsInventedSeriesScopeLimitsAndMutationRisk() {
        val registry = OrezToolRegistry()
        val valid = registry.call("search_saved_memory", mapOf("chapterId" to chapter, "query" to "Jin", "limit" to "1"))
        assertTrue(runCatching { registry.validate(valid.copy(risk = OrezToolRisk.LOCAL_MUTATION)) }.isFailure)
        assertTrue(runCatching { registry.call("search_saved_memory", valid.arguments + ("seriesId" to "foreign")) }.isFailure)
        assertTrue(runCatching { registry.call("search_saved_memory", valid.arguments + ("limit" to "9")) }.isFailure)
        assertTrue(runCatching { registry.call("search_saved_memory", valid.arguments + ("query" to " ")) }.isFailure)
    }

    @Test fun toolCompletionPassesActualDurableReceiptChecksButForeignBoundsAndUnscopedTextDoNot() = runBlocking {
        val call = OrezToolRegistry().call("search_saved_memory", mapOf("chapterId" to chapter, "query" to "Jin"))
        val step = OrezPlanStep(0, call)
        val plan = OrezTaskPlan(id = "memory-test", objective = "search saved dialogue for \"Jin\"", steps = listOf(step),
            authorization = OrezTaskAuthorization(OrezTrustOrigin.USER, true, chapterIds = setOf(chapter)))
        val source = MemorySourceProof(chapter, 0, "/private/chapters/original.png", "a".repeat(64), 1000, 400, MemoryRegionBounds(100, 100, 900, 300))
        val hit = MemorySearchHit("b".repeat(64), source, null, MemorySearchKind.OCR, "Jin, hello.", 0, "hi", "c".repeat(64))
        val tool = OrezMemoryTools(object : OrezMemoryHost {
            override suspend fun search(chapterId: String, query: String, limit: Int) = OrezMemorySearchSnapshot(chapter, "d".repeat(64), 0, listOf(hit), false)
        }, plan.authorization!!)
        val result = tool.execute(step, OrezDurablePlanRules.requestId(plan.id, 0)) as OrezToolResult.Completed
        OrezDurablePlanRules.validateReceipt(plan, step, result.outputs, completed = true)
        assertTrue(runCatching { OrezDurablePlanRules.validateReceipt(plan, step, result.outputs + ("chapterId" to "f".repeat(32)), true) }.isFailure)
        assertTrue(runCatching { OrezDurablePlanRules.validateReceipt(plan, step, result.outputs + ("privatePath" to source.sourcePath), true) }.isFailure)
        assertTrue(runCatching { OrezDurablePlanRules.validateReceipt(plan, step, result.outputs + ("memoryHitCount" to "9"), true) }.isFailure)
    }

    companion object { private val chapter = "a".repeat(32) }
}
