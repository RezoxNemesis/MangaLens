package com.mangalens.orez.agent
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrezLibraryTaskStoreTest {
    private fun plan():OrezTaskPlan {
        val id="a".repeat(32);val scope=OrezLibraryScope.capture(OrezLibraryRequest(OrezLibraryOperation.BOOKMARK,bookmarked=true),id,
            listOf(OrezLibraryManifestRevision(id,"b".repeat(64),100,"c".repeat(64))),false)
        return OrezAgentRuntime().decide("Bookmark this chapter",OrezAgentContext(activeChapterId=id,libraryScope=scope)).plan!!
    }
    @Test fun schemaSixRoundTripsExactMetadataScopeWithoutRecapture()=runTest {
        val dao=Dao();val store=OrezTaskStore(dao);val plan=plan();assertTrue(store.checkpoint(plan))
        assertEquals(6,JSONObject(dao.saved!!.planJson).getInt("schema"));assertEquals(plan,store.load(plan.id))
    }
    @Test fun malformedOrMissingSchemaSixLibraryScopeFailsClosed()=runTest {
        val dao=Dao();val store=OrezTaskStore(dao);store.checkpoint(plan())
        val root=JSONObject(dao.saved!!.planJson);root.getJSONObject("authorization").remove("libraryScope")
        assertTrue(runCatching { store.decode(root.toString()) }.isFailure)
    }
    @Test fun pauseBeforePreparedPublicationPreventsActualCallback()=runTest {
        val store=OrezTaskStore(Dao());val plan=plan();store.checkpoint(plan);store.pause(plan.id);var wrote=false
        assertTrue(runCatching { store.publishLibrary(plan.id,plan.executionEpoch,plan.authorization!!.libraryScope!!) { wrote=true } }.isFailure);assertFalse(wrote)
    }
    @Test fun executingDifferentScopeOrEpochCannotPublish()=runTest {
        val store=OrezTaskStore(Dao());val plan=plan();store.checkpoint(plan);var wrote=false
        val scope=plan.authorization!!.libraryScope!!
        val other=OrezLibraryScope.capture(scope.request,scope.selectedChapterId,scope.inventory,true)
        assertTrue(runCatching { store.publishLibrary(plan.id,plan.executionEpoch,other) { wrote=true } }.isFailure)
        assertTrue(runCatching { store.publishLibrary(plan.id,plan.executionEpoch+1,scope) { wrote=true } }.isFailure);assertFalse(wrote)
    }
    @Test fun existingSchemaFiveAcquisitionStillRoundTrips()=runTest {
        val scope=OrezChapterAcquisitionScope("https://example.org/manga/chapter-2/")
        val plan=OrezAgentRuntime().decide("Save chapter ${scope.targetUrl}",OrezAgentContext(chapterAcquisition=scope)).plan!!
        val dao=Dao();val store=OrezTaskStore(dao);store.checkpoint(plan)
        assertEquals(5,JSONObject(dao.saved!!.planJson).getInt("schema"));assertEquals(plan,store.load(plan.id))
    }
    private class Dao:OrezTaskDao {
        var saved:OrezTaskEntity?=null
        override fun observeActive():Flow<List<OrezTaskEntity>> = flowOf(saved?.let(::listOf).orEmpty())
        override suspend fun get(id:String)=saved?.takeIf { it.id==id }
        override suspend fun upsert(task:OrezTaskEntity){saved=task}
        override suspend fun pruneFinished(before:Long){}
    }
}
