package com.mangalens.orez.agent

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class OrezMemoryToolsTest {
    @Test fun exactExplicitSelectedChapterSearchReturnsOriginalCoordinateEvidenceWithoutPrivatePaths() = runBlocking {
        val host = Host(); val result = OrezMemoryTools(host, scope()).execute(step(), "request") as OrezToolResult.Completed
        assertEquals(listOf(Triple(chapter, "Jin", 8)), host.calls)
        assertEquals("1", result.outputs["memoryHitCount"])
        val row = JSONArray(result.outputs.getValue("memoryHits")).getJSONObject(0)
        assertEquals(chapter, row.getString("chapterId")); assertEquals(0, row.getInt("pageIndex"))
        assertEquals(listOf(100, 100, 900, 300), (0 until 4).map { row.getJSONArray("bounds").getInt(it) })
        assertEquals("a".repeat(64), row.getString("sourceSha256"))
        assertFalse(result.outputs.values.any { it.contains("private/chapters") || it.contains("sourcePath") })
    }

    @Test fun anotherChapterAndUntrustedContentCannotInvokeTheNativeSearchHost() = runBlocking {
        for (authorization in listOf(scope().copy(chapterIds = setOf("b".repeat(32))), scope().copy(origin = OrezTrustOrigin.WEB_CONTENT),
            scope().copy(origin = OrezTrustOrigin.IMPORTED_CONTENT), scope().copy(explicitUserRequest = false))) {
            val host = Host()
            assertTrue(OrezMemoryTools(host, authorization).execute(step(), "request") is OrezToolResult.Failed)
            assertTrue(host.calls.isEmpty())
        }
    }

    @Test fun malformedLimitQueryUnknownArgumentsAndMutationMetadataFailBeforeHostAccess() = runBlocking {
        val invalid = listOf(step(mapOf("chapterId" to chapter, "query" to "Jin", "limit" to "nine")),
            step(mapOf("chapterId" to chapter, "query" to "Jin", "limit" to "9")),
            step(mapOf("chapterId" to chapter, "query" to " ")),
            step(mapOf("chapterId" to chapter, "query" to "Jin", "seriesId" to "invented")),
            step().copy(call = step().call.copy(risk = OrezToolRisk.LOCAL_MUTATION)))
        for (next in invalid) {
            val host = Host(); assertTrue(OrezMemoryTools(host, scope()).execute(next, "request") is OrezToolResult.Failed)
            assertTrue(host.calls.isEmpty())
        }
    }

    @Test fun outOfScopeExcessAndMalformedNativeResultsAreRejected() = runBlocking {
        for (response in listOf(snapshot().copy(chapterId = "b".repeat(32)), snapshot().copy(hits = List(9) { hit() }),
            snapshot().copy(hits = listOf(hit().copy(source = hit().source.copy(chapterId = "b".repeat(32))))),
            snapshot().copy(hits = listOf(hit().copy(source = hit().source.copy(bounds = MemoryRegionBounds(0, 0, 1001, 400))))),
            snapshot().copy(hits = listOf(hit().copy(targetLanguage = "target-not-supported"))))) {
            assertTrue(OrezMemoryTools(Host(response), scope()).execute(step(), "request") is OrezToolResult.Failed)
        }
    }

    @Test fun escapedTextRemainsWithinTheActualDurableReceiptLimitAndMarksTruncation() = runBlocking {
        val response = snapshot().copy(hits = List(8) { hit().copy(text = "Jin\n".repeat(1000)) })
        val result = OrezMemoryTools(Host(response), scope()).execute(step(), "request") as OrezToolResult.Completed
        assertTrue(result.outputs.values.all { it.length <= 8192 }); assertEquals("true", result.outputs["memoryIncomplete"])
        assertEquals(8, JSONArray(result.outputs.getValue("memoryHits")).length())
    }

    @Test fun pauseDuringHeldReadSuppressesPublicationAndCancellationIsNotReclassified() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var running = true
        val tools = OrezMemoryTools(object : OrezMemoryHost {
            override suspend fun search(chapterId: String, query: String, limit: Int): OrezMemorySearchSnapshot {
                entered.complete(Unit); release.await(); return snapshot()
            }
        }, scope(), isExecuting = { running })
        val pending = async { tools.execute(step(), "request") }
        entered.await(); running = false; release.complete(Unit)
        assertTrue(pending.await() is OrezToolResult.Pending)
        val cancelled = OrezMemoryTools(object : OrezMemoryHost {
            override suspend fun search(chapterId: String, query: String, limit: Int): OrezMemorySearchSnapshot = throw CancellationException("cancel")
        }, scope())
        assertTrue(runCatching { cancelled.execute(step(), "request") }.exceptionOrNull() is CancellationException)
    }

    private class Host(private val result: OrezMemorySearchSnapshot = snapshot()) : OrezMemoryHost {
        val calls = mutableListOf<Triple<String, String, Int>>()
        override suspend fun search(chapterId: String, query: String, limit: Int): OrezMemorySearchSnapshot {
            calls += Triple(chapterId, query, limit); return result
        }
    }
    companion object {
        private val chapter = "a".repeat(32)
        private fun scope() = OrezTaskAuthorization(OrezTrustOrigin.USER, true, chapterIds = setOf(chapter))
        private fun step(arguments: Map<String, String> = mapOf("chapterId" to chapter, "query" to "Jin")) = OrezPlanStep(0,
            OrezToolCall("search_saved_memory", OrezCapability.LIBRARY, OrezToolRisk.READ_ONLY, "Search selected chapter memory", arguments, null))
        private fun hit() = MemorySearchHit("b".repeat(64), MemorySourceProof(chapter, 0, "/private/chapters/original.png", "a".repeat(64),
            1000, 400, MemoryRegionBounds(100, 100, 900, 300)), null, MemorySearchKind.OCR, "Jin, hello.", 1, "hi", "c".repeat(64))
        private fun snapshot() = OrezMemorySearchSnapshot(chapter, "d".repeat(64), 1, listOf(hit()), false)
    }
}
