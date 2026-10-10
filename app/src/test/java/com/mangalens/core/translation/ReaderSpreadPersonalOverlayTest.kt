package com.mangalens.core.translation
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.translation.memory.MemoryCorrectionEdit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Authored UNRUN. Actual Store/adapter flow; no bitmap or Compose acceptance claim. */
class ReaderSpreadPersonalOverlayTest {
    @Test fun bothVisiblePagesReceivePersonalTextWithoutChangingNativeBytes() = environment { f, _, c, task ->
        val journal=f.journal(task.id).readBytes(); val sources=task.pages.map { File(it.sourcePath!!).readBytes() }
        c.onVisiblePage(0); c.onVisiblePages(setOf(0,1))
        for (page in 0..1) { c.openPage(page); c.selectBubble(0); c.save(MemoryCorrectionEdit(translated=if (page==0) "नमस्ते, मित्र।" else "नमस्ते, साथी।")) }
        c.refreshVisiblePage()
        await { c.state.value.personalOverlays[0]?.get(0)?.personal?.translated=="नमस्ते, मित्र।" && c.state.value.personalOverlays[1]?.get(0)?.personal?.translated=="नमस्ते, साथी।" }
        assertArrayEquals(journal,f.journal(task.id).readBytes())
        task.pages.forEachIndexed { index,page -> assertArrayEquals(sources[index],File(page.sourcePath!!).readBytes()) }
    }
    @Test fun singlePageRetiresTheOtherPersonalProjection() = environment { _, _, c, _ ->
        c.onVisiblePage(0); c.onVisiblePages(setOf(0,1)); await { c.state.value.personalOverlays.keys==setOf(0,1) }
        c.onVisiblePages(setOf(0)); await { c.state.value.personalOverlays.keys==setOf(0) }
    }
    @Test fun emptyVisibleSetAndRouteExitRemoveSpreadProjections() = environment { _, _, c, _ ->
        c.onVisiblePage(0); c.onVisiblePages(setOf(0,1)); await { c.state.value.personalOverlays.keys==setOf(0,1) }
        c.onVisiblePages(emptySet()); assertTrue(c.state.value.personalOverlays.isEmpty())
        c.leaveReader(); assertTrue(c.state.value.personalOverlays.isEmpty()); assertNull(c.selectionForTest())
    }
    @Test fun heldSpreadReadCannotDeliverAfterReaderLeaves() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store=f.store(); val task=twoPages(f,store); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val hold=AtomicBoolean(false); val entered=CountDownLatch(1); val release=CountDownLatch(1)
            val c=ReaderMemoryController(f.root,scope,{ if (hold.getAndSet(false)) { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }; store })
            try {
                c.bindAccepted(task,ReaderTranslationPresentation.receipt(task)); c.enterReader(); c.onVisiblePage(0)
                await { c.state.value.personalOverlays.keys==setOf(0) }
                hold.set(true); c.onVisiblePages(setOf(0,1)); assertTrue(entered.await(5,TimeUnit.SECONDS))
                c.leaveReader(); release.countDown(); delay(50)
                assertTrue(c.state.value.personalOverlays.isEmpty()); assertNull(c.selectionForTest())
            } finally { release.countDown(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
    private fun twoPages(f:NativeMemoryPublicationAdapterTest.Fixture,store:ChapterTranslationStore):ChapterTranslationTask {
        val old=f.completed(nativeStore=store)
        val second=File(f.sources,"page-one.jpg").apply { writeText("original source page one") }
        val chapter=f.chapter.copy(pages=f.chapter.pages+ChapterPage(1,"local:one",second.path))
        val started=store.start(chapter,f.config,ownerRequestId="reader:fixture"); store.markRunning(started.id,started.generation)
        val page=store.beginPage(started.id,started.generation,1)!!
        val output=store.createOutputFile(started.id,started.generation,1).apply { writeText("valid surface: saved page one") }
        assertTrue(store.commitPage(started.id,started.generation,old.pages.single().copy(index=1,sourcePath=page.sourcePath,sourceSha256=page.sourceSha256,cleanedPath=output.path,cleanedSha256=ChapterTranslationStore.sha256(output))))
        return store.finish(started.id,started.generation)!!
    }
    private fun environment(action:suspend (NativeMemoryPublicationAdapterTest.Fixture,ChapterTranslationStore,ReaderMemoryController,ChapterTranslationTask)->Unit)=runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store=f.store(); val task=twoPages(f,store); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            try { val c=ReaderMemoryController(f.root,scope,{store}); c.bindAccepted(task,ReaderTranslationPresentation.receipt(task)); c.enterReader(); action(f,store,c,task) }
            finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
    private suspend fun await(test:()->Boolean)=withTimeout(2_000) { while(!test()) delay(10) }
}
