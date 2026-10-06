package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrezTaskStoreTest {
    @Test
    fun checkpointPersistsStructuredPlanInsteadOfFreeFormChat() = runTest {
        val dao = FakeTaskDao()
        val store = OrezTaskStore(dao)
        val plan = OrezTaskPlan(
            id = "task-test",
            objective = "Open my library",
            steps = listOf(
                OrezPlanStep(
                    index = 0,
                    call = OrezToolCall(
                        name = "open_library",
                        capability = OrezCapability.LIBRARY,
                        risk = OrezToolRisk.READ_ONLY,
                        summary = "Open MangaLens Library"
                    )
                )
            )
        )

        store.checkpoint(plan, OrezTaskStatus.RUNNING)

        val saved = dao.saved
        assertNotNull(saved)
        assertEquals("task-test", saved!!.id)
        assertEquals("RUNNING", saved.status)

        val json = JSONObject(saved.planJson)
        assertEquals(1, json.getInt("schema"))
        assertEquals("Open my library", json.getString("objective"))
        assertEquals("open_library", json.getJSONArray("steps").getJSONObject(0).getString("tool"))
    }

    private class FakeTaskDao : OrezTaskDao {
        var saved: OrezTaskEntity? = null

        override fun observeActive(): Flow<List<OrezTaskEntity>> =
            flowOf(saved?.let(::listOf) ?: emptyList())

        override suspend fun get(id: String): OrezTaskEntity? =
            saved?.takeIf { it.id == id }

        override suspend fun upsert(task: OrezTaskEntity) {
            saved = task
        }

        override suspend fun pruneFinished(before: Long) {
            if (saved?.updatedAt?.let { it < before } == true) saved = null
        }
    }
}
