package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import com.mangalens.orez.research.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Actual executor/journal codec and real held HTTP calls, not a scalar status-only completion. */
class OrezResearchDurabilityTest {
    private fun plan(): OrezTaskPlan = OrezAgentRuntime().decide("Research \"Android offline downloads\"", OrezAgentContext()).plan!!
    private fun responses(server: okhttp3.mockwebserver.MockWebServer) {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<div class='result'><a class='result__a' href='https://source.test/article'>Android guide</a></div>"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<p>Android downloads remain available offline after they are saved to local application storage.</p>"))
    }
    @Test fun completedActualResearchReceiptSurvivesRestartAndReplayDoesNoHttpWork() = OrezResearchHttpTest().fixture { server, transport, _ ->
        val dao = FakeDao(); val store = OrezTaskStore(dao); val p = plan(); assertTrue(store.checkpoint(p))
        responses(server)
        val tools = OrezResearchTools(p, OrezResearchHost(transport, { 1000L })) { store.isExecuting(p.id, p.executionEpoch) }
        val result = OrezTaskExecutor(store, tools).run(p.id, p.executionEpoch)
        assertTrue(result is OrezTaskExecutor.Result.Completed)
        val committed = store.load(p.id)!!
        val outputs = committed.steps.single().outputs
        assertEquals(2, server.requestCount)
        val restarted = OrezTaskStore(dao)
        val saved = OrezResearchResults.evidence(restarted.load(p.id)!!).single()
        assertEquals(1000L, saved.citations.single().source.capturedAtMs)
        assertTrue(saved.citations.single().excerpt.contains("available offline"))
        val replay = OrezTaskExecutor(restarted, OrezDurableTools { _, _ -> error("Completed research must not contact a provider again") }).run(p.id)
        assertEquals(OrezTaskExecutor.Result.AlreadyFinished, replay)
        assertEquals(outputs, restarted.load(p.id)!!.steps.single().outputs)
        assertEquals(2, server.requestCount)
        assertFalse(saved.metadataCompletion().contains(saved.citations.single().excerpt))
    }
    @Test fun pauseAndG2ResumeFenceAnActualHeldNetworkCallWithoutStaleCompletion() = OrezResearchHttpTest().fixture { server, transport, _ ->
        val store = OrezTaskStore(FakeDao()); val p = plan(); assertTrue(store.checkpoint(p))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        coroutineScope {
            var delivered = false
            val tools = OrezResearchTools(p, OrezResearchHost(transport, { 1000L })) { store.isExecuting(p.id, p.executionEpoch) }
            val job = launch(Dispatchers.IO) { OrezTaskExecutor(store, tools).run(p.id, p.executionEpoch); delivered = true }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val paused = store.pause(p.id)!!
            val resumed = store.resume(p.id)!!
            assertTrue(paused.executionEpoch > p.executionEpoch); assertTrue(resumed.executionEpoch > paused.executionEpoch)
            withTimeout(2500) { job.join() }
            val newest = store.load(p.id)!!
            assertEquals(resumed.executionEpoch, newest.executionEpoch)
            assertFalse(delivered); assertTrue(job.isCancelled)
            assertTrue(newest.steps.single().outputs.isEmpty()); assertFalse(newest.steps.single().status == OrezStepStatus.COMPLETED)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun cancellationOfTheDurableTaskCannotPublishACompletedReceiptAfterHeadersStall() = OrezResearchHttpTest().fixture { server, transport, _ ->
        val store = OrezTaskStore(FakeDao()); val p = plan(); assertTrue(store.checkpoint(p))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        coroutineScope {
            val tools = OrezResearchTools(p, OrezResearchHost(transport, { 1000L })) { store.isExecuting(p.id, p.executionEpoch) }
            val job = launch(Dispatchers.IO) { OrezTaskExecutor(store, tools).run(p.id, p.executionEpoch) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); store.cancel(p.id)
            withTimeout(2500) { job.join() }
            assertEquals(OrezTaskStatus.CANCELLED, store.load(p.id)!!.status)
            assertTrue(store.load(p.id)!!.steps.single().outputs.isEmpty()); assertEquals(1, server.requestCount)
        }
    }
    @Test fun completedReceiptCannotBeReplacedByAnotherQueryOrPromotedFromAnIncompleteTask() = runBlocking {
        val p = plan(); val evidence = OrezResearchEvidenceTest().evidence()
        val receipt = OrezResearchEvidenceCodec.outputs(OrezDurablePlanRules.requestId(p.id, 0), evidence)
        val committed = p.copy(status = OrezTaskStatus.COMPLETED, steps = p.steps.map { it.copy(status = OrezStepStatus.COMPLETED, outputKind = OrezOutputKind.RESEARCH_EVIDENCE, outputs = receipt) })
        val store = OrezTaskStore(FakeDao()); assertTrue(store.checkpoint(committed))
        assertTrue(OrezResearchResults.evidence(committed).isNotEmpty())
        assertTrue(OrezResearchResults.evidence(committed.copy(status = OrezTaskStatus.RUNNING)).isEmpty())
        assertFalse(store.checkpoint(committed.copy(steps = committed.steps.map { it.copy(outputs = receipt + ("querySha256" to "b".repeat(64))) })))
        assertEquals(receipt, store.load(p.id)!!.steps.single().outputs)
    }
    @Test fun criticalResourcesKeepAnUnfinishedDurableTaskRetryableWithoutAnyNetworkEffect() = OrezResearchHttpTest().fixture { server, transport, _ ->
        val store = OrezTaskStore(FakeDao()); val p = plan(); assertTrue(store.checkpoint(p))
        val host = OrezResearchHost(transport, { 1000L }, beforeBoundary = { throw com.mangalens.core.compute.ResourcePausedException("controlled critical pressure") })
        val result = OrezTaskExecutor(store, OrezResearchTools(p, host) { store.isExecuting(p.id, p.executionEpoch) }).run(p.id)
        assertTrue(result is OrezTaskExecutor.Result.Pending)
        assertFalse((result as OrezTaskExecutor.Result.Pending).needsResume)
        assertEquals(OrezTaskStatus.RUNNING, store.load(p.id)!!.status)
        assertTrue(store.load(p.id)!!.steps.single().outputs.isEmpty()); assertEquals(0, server.requestCount)
    }
    @Test fun aResearchCitationOrUrlMentionCannotAuthorizeAdditionalDownloadTools() {
        val p = plan()
        val download = OrezToolRegistry().call("enqueue_download", mapOf("value" to "https://source.test/article"))
        val mixed = p.copy(authorization = p.authorization!!.copy(urls = setOf("https://source.test/article")),
            steps = p.steps + OrezPlanStep(1, download))
        assertTrue(runCatching { OrezDurablePlanRules.validate(mixed) }.isFailure)
    }
    private class FakeDao : OrezTaskDao {
        private val rows = java.util.concurrent.ConcurrentHashMap<String, OrezTaskEntity>()
        override fun observeActive(): Flow<List<OrezTaskEntity>> = flowOf(rows.values.toList())
        override suspend fun get(id: String) = rows[id]
        override suspend fun upsert(task: OrezTaskEntity) { rows[task.id] = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
}
