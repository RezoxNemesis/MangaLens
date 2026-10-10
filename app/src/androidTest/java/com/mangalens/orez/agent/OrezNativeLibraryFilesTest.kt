package com.mangalens.orez.agent

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.core.reader.*
import com.mangalens.core.translation.memory.*
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Authored actual Android FD/filesystem controls with a fake task DAO. No execution/Room/UI claim. */
@RunWith(AndroidJUnit4::class)
class OrezNativeLibraryFilesTest {
    private class App(base:Context,private val root:File):ContextWrapper(base) {
        override fun getFilesDir()=root
        override fun getApplicationContext():Context=this
    }
    private class Dao:OrezTaskDao {
        var saved:OrezTaskEntity?=null
        override fun observeActive():Flow<List<OrezTaskEntity>> = flowOf(saved?.let(::listOf).orEmpty())
        override suspend fun get(id:String)=saved?.takeIf { it.id==id }
        override suspend fun upsert(task:OrezTaskEntity){saved=task}
        override suspend fun pruneFinished(before:Long){}
    }
    private class Journal:ChapterLibraryJournalIo {
        override fun read(file:File):InputStream=file.inputStream()
        override fun write(file:File,bytes:ByteArray){file.outputStream().use { it.write(bytes);it.fd.sync() }}
        override fun delete(file:File){file.delete()}
    }
    private fun fixture(body:suspend(App,ChapterLibrary,SavedChapter)->Unit)=runBlocking {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val root=Files.createTempDirectory(base.cacheDir.toPath(),"native-library-fixture-").toFile()
        try {
            val app=App(base,root);val library=ChapterLibrary(root,Journal())
            val image=File(root,"chapters/page.bin").apply {parentFile!!.mkdirs();writeText("untouched-original")}
            val saved=SavedChapter("a".repeat(32),"Actual saved chapter","https://example.org/chapter/",listOf(ChapterPage(1,"https://example.org/page.png",image.path)))
            library.save(saved);body(app,library,saved)
        } finally { root.deleteRecursively() }
    }
    private suspend fun request(app:Context,input:String,id:String):Pair<OrezTaskStore,OrezTaskPlan> {
        val scope=OrezLibraryCapture.capture(app,input,id)!!
        val plan=OrezAgentRuntime().decide(input,OrezAgentContext(activeChapterId=id,libraryScope=scope)).plan!!
        val store=OrezTaskStore(Dao());assertTrue(store.checkpoint(plan));return store to plan
    }
    private suspend fun run(app:Context,store:OrezTaskStore,plan:OrezTaskPlan)=OrezLibraryTools(app,store,plan).execute(plan.steps.single(),OrezDurablePlanRules.requestId(plan.id,0))
    @Test fun realBookmarkRenameReadbackAndSameRequestReplayPreserveOriginalBytes()=fixture { app,library,saved ->
        val before=File(saved.pages.single().localPath!!).readBytes();val (store,plan)=request(app,"Bookmark this chapter",saved.id)
        val first=run(app,store,plan);assertTrue(first is OrezToolResult.Completed)
        assertTrue(library.findMetadata(saved.id)!!.bookmarked)
        val journal=File(app.filesDir,"chapter_library/${saved.id}.json");val native=journal.readBytes()
        assertTrue(run(app,store,plan) is OrezToolResult.Completed);assertArrayEquals(native,journal.readBytes())
        assertArrayEquals(before,File(saved.pages.single().localPath!!).readBytes())
    }
    @Test fun laterUserBookmarkChangeCannotBeRewrittenByAnOldRequest()=fixture { app,library,saved ->
        val (store,plan)=request(app,"Bookmark this chapter",saved.id);assertTrue(run(app,store,plan) is OrezToolResult.Completed)
        library.save(library.findMetadata(saved.id)!!.copy(bookmarked=false))
        assertTrue(run(app,store,plan) is OrezToolResult.Failed);assertFalse(library.findMetadata(saved.id)!!.bookmarked)
    }
    @Test fun staleManifestAndCancelledEpochCannotPublishBookmark()=fixture { app,library,saved ->
        val (store,plan)=request(app,"Bookmark this chapter",saved.id);library.save(saved.copy(notes="later edit"))
        assertTrue(run(app,store,plan) is OrezToolResult.Failed);assertFalse(library.findMetadata(saved.id)!!.bookmarked)
        val (freshStore,fresh)=request(app,"Bookmark this chapter",saved.id);freshStore.pause(fresh.id)
        assertTrue(runCatching { run(app,freshStore,fresh) }.isFailure);assertFalse(library.findMetadata(saved.id)!!.bookmarked)
    }
    @Test fun realLinkedSeriesLiteralWriteAndReplayHaveNoInventedOrigin()=fixture { app,_,saved ->
        val memory=SeriesMemoryStore(app.filesDir);val profile=memory.createSeries("Real linked series")
        memory.associateChapter(saved.id,profile.id,1)
        val (store,plan)=request(app,"Set series term \"Yeorum\" to \"येओरुम\" in hi",saved.id)
        assertTrue(run(app,store,plan) is OrezToolResult.Completed)
        val written=memory.profile(profile.id)!!.glossary.single();assertEquals("येओरुम",written.preferred);assertNull(written.origin);assertNull(written.originSourceSha256)
        val journal=File(app.filesDir,"reader_memory/series/${profile.id}.json");val before=journal.readBytes()
        assertTrue(run(app,store,plan) is OrezToolResult.Completed);assertArrayEquals(before,journal.readBytes())
    }
    @Test fun relinkRetiresCapturedTermWrite()=fixture { app,_,saved ->
        val memory=SeriesMemoryStore(app.filesDir);val a=memory.createSeries("A");val b=memory.createSeries("B")
        memory.associateChapter(saved.id,a.id,1)
        val (store,plan)=request(app,"Set series term \"x\" to \"y\" in en",saved.id)
        memory.associateChapter(saved.id,b.id,1)
        assertTrue(run(app,store,plan) is OrezToolResult.Failed);assertTrue(memory.profile(a.id)!!.glossary.isEmpty());assertTrue(memory.profile(b.id)!!.glossary.isEmpty())
    }
    @Test fun unrelatedProfileEditRetiresCapturedTermWriteWithoutLosingThatEdit()=fixture { app,_,saved ->
        val memory=SeriesMemoryStore(app.filesDir);val profile=memory.createSeries("Linked")
        memory.associateChapter(saved.id,profile.id,1)
        val (store,plan)=request(app,"Set series term \"x\" to \"y\" in en",saved.id)
        memory.upsertTerm(profile.id,SeriesGlossaryTerm("other","keep","retained","en",MemoryTermKind.CUSTOM))
        assertTrue(run(app,store,plan) is OrezToolResult.Failed)
        assertEquals(listOf("keep"),memory.profile(profile.id)!!.glossary.map { it.source })
    }
    @Test fun passiveReadingCheckpointPreservesAlreadyCommittedBookmarkAndUserDetails()=fixture { app,library,cached ->
        val (store,plan)=request(app,"Bookmark this chapter",cached.id);assertTrue(run(app,store,plan) is OrezToolResult.Completed)
        library.updateMetadata(cached.id,LibraryChapterMetadata(notes="new notes",collections=listOf("Keep")))
        val saved=OrezLibraryReaderCheckpoint.save(app,cached.copy(position=0,lastReadAt=123))
        assertTrue(saved.bookmarked);assertEquals("new notes",saved.notes);assertEquals(listOf("Keep"),saved.collections)
        val actual=library.findMetadata(cached.id)!!;assertTrue(actual.bookmarked);assertEquals(123L,actual.lastReadAt)
    }
    @Test fun noFollowAndNonblockingDescriptorAdmissionRejectActualSymlinkAndFifo()=fixture { app,_,saved ->
        val alias=File(app.filesDir,"chapter_library/${"b".repeat(32)}.json")
        Os.symlink(File(app.filesDir,"chapter_library/${saved.id}.json").path,alias.path)
        assertTrue(runCatching { OrezLibraryOwnedIo.project(app.filesDir) { it.readLibrary(alias,2_000_000) } }.isFailure)
        alias.delete();Os.mkfifo(alias.path,384)
        assertTrue(runCatching { OrezLibraryOwnedIo.project(app.filesDir) { it.readLibrary(alias,2_000_000) } }.isFailure)
    }
    @Test fun savedMetadataQueryNeedsNoOriginalImageReadOrNetwork()=fixture { app,_,saved ->
        File(saved.pages.single().localPath!!).delete()
        val (store,plan)=request(app,"Search library for \"Actual\"",saved.id)
        val result=run(app,store,plan) as OrezToolResult.Completed
        assertEquals(1,org.json.JSONObject(result.outputs.getValue("metadata")).getJSONArray("chapters").length())
        assertFalse(OrezDurablePlanRules.requiresNetwork(plan))
    }
}
