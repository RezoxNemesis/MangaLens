package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Scheduling seams hold real prepared journal proofs; none manufactures native authority. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedTextSearchControllerTest {
    @Test fun delayedSearchDoesNotPublishAfterQueryEditOrClearTheNewRequestBusyState() = fixture { f, _, _, _, found, _ ->
        runTest {
            val release = CompletableDeferred<Unit>()
            val controller = SavedTextSearchController(f.chapter.id, backgroundScope,
                search = { _, query -> if (query == "Hello") withContext(NonCancellable) { release.await() }; found },
                prepareOpen = { _, _ -> error("No opening") })
            controller.setQuery("Hello"); controller.search()
            assertTrue(controller.state.value.busy)
            controller.setQuery("friend")
            assertNull(controller.state.value.snapshot); assertFalse(controller.state.value.busy)
            controller.search(); runCurrent()
            assertTrue("A snapshot from another query is never promoted", controller.state.value.snapshot == null)
            release.complete(Unit); runCurrent()
            assertNull(controller.state.value.snapshot)
            assertFalse(controller.state.value.busy)
            controller.close()
        }
    }

    @Test fun routeLeaveAndReenterRetireHeldSearchEvenWhenIoIgnoresCancellation() = fixture { f, _, _, _, found, _ ->
        runTest {
            val release = CompletableDeferred<Unit>()
            val controller = SavedTextSearchController(f.chapter.id, backgroundScope,
                { _, _ -> withContext(NonCancellable) { release.await() }; found }, { _, _ -> error("No opening") })
            controller.setQuery("Hello"); controller.search()
            controller.setActive(false); controller.setActive(true)
            release.complete(Unit); runCurrent()
            assertNull(controller.state.value.snapshot); assertFalse(controller.state.value.busy)
            assertNull(controller.state.value.error)
            controller.close()
        }
    }

    @Test fun sameQueryRapidClicksStartOnlyOneRealSearchAndExactKindsAreDelivered() = fixture { f, _, _, _, found, _ ->
        runTest {
            var searches = 0
            val release = CompletableDeferred<Unit>()
            val controller = SavedTextSearchController(f.chapter.id, backgroundScope,
                { _, _ -> searches++; release.await(); found }, { _, _ -> error("No opening") })
            controller.setQuery("Hello"); controller.search(); controller.search()
            assertEquals(1, searches); assertTrue(controller.state.value.busy)
            release.complete(Unit); runCurrent()
            assertSame(found, controller.state.value.snapshot)
            assertEquals(MemorySearchKind.OCR, controller.state.value.snapshot!!.rows.single().hit.kind)
            assertFalse(controller.state.value.busy)
            controller.close()
        }
    }

    @Test fun generationReplacementAfterIoBeforeMainSearchDeliveryCannotPublishNativeContext() = fixture { f, native, _, _, found, _ ->
        runTest {
            val release = CompletableDeferred<Unit>()
            val controller = SavedTextSearchController(f.chapter.id, backgroundScope,
                { _, _ -> release.await(); found }, { _, _ -> error("No opening") })
            controller.setQuery("Hello"); controller.search()
            f.completed(nativeStore = native, owner = "reader:G2", forceReprocess = true)
            release.complete(Unit); runCurrent()
            assertNull(controller.state.value.snapshot)
            assertEquals(SavedTextSearchController.STALE_MESSAGE, controller.state.value.error)
            controller.close()
        }
    }

    @Test fun heldOpenAfterNewQueryOrRouteLeaveCannotInvokeNavigation() = fixture { f, _, _, _, found, prepared ->
        for (leave in listOf(false, true)) runTest {
            val release = CompletableDeferred<Unit>(); var opened = false
            val controller = SavedTextSearchController(f.chapter.id, backgroundScope,
                { _, _ -> found }, { _, _ -> withContext(NonCancellable) { release.await() }; prepared })
            controller.setQuery("Hello"); controller.search(); runCurrent()
            controller.open(found.rows.single().id) { opened = true; it.tryDeliver { true } }
            if (leave) controller.setActive(false) else controller.setQuery("friend")
            release.complete(Unit); runCurrent()
            assertFalse(opened); assertFalse(controller.state.value.busy)
            controller.close()
        }
    }

    @Test fun finalOpenProofRejectsReplacementWithoutPromotingEqualG2Text() = fixture { f, native, _, service, found, _ ->
        runTest {
            val release = CompletableDeferred<Unit>(); var selected: SavedTextReaderSelection? = null
            val pending = service.prepareOpen(found, found.rows.single().id)!!
            val controller = SavedTextSearchController(f.chapter.id, backgroundScope,
                { _, _ -> found }, { _, _ -> release.await(); pending })
            controller.setQuery("Hello"); controller.search(); runCurrent()
            controller.open(found.rows.single().id) { it.tryDeliver { value -> selected = value; true } }
            f.completed(nativeStore = native, owner = "reader:G2", forceReprocess = true)
            release.complete(Unit); runCurrent()
            assertNull(selected); assertNull(controller.state.value.snapshot)
            assertEquals(SavedTextSearchController.STALE_MESSAGE, controller.state.value.error)
            controller.close()
        }
        val fresh = service.search(f.chapter.id, "Hello") // Retired indexed receipt is omitted, never replaced by equal text.
        assertTrue(fresh!!.rows.isEmpty())
    }

    @Test fun unchangedOpenDeliversActualPageZeroSelectionAndOneActionCannotReplay() = fixture { f, _, _, _, found, prepared ->
        runTest {
            var selected: SavedTextReaderSelection? = null
            val controller = SavedTextSearchController(f.chapter.id, backgroundScope,
                { _, _ -> found }, { _, _ -> prepared })
            controller.setQuery("Hello"); controller.search(); runCurrent()
            controller.open(found.rows.single().id) { it.tryDeliver { value -> selected = value; true } }; runCurrent()
            assertEquals(f.chapter.id, selected!!.chapterId)
            assertEquals(0, selected!!.pageIndex); assertEquals(0, selected!!.pageOrdinal)
            assertEquals("hi", selected!!.targetLanguage)
            assertFalse(prepared.tryDeliver { error("A consumed navigation action cannot replay") })
            controller.close()
        }
    }

    @Test fun invalidQueryAndUnknownOpaqueResultCannotRunIoOrNavigate() = fixture { f, _, _, _, _, _ ->
        runTest {
            var reads = 0; var opens = 0
            val controller = SavedTextSearchController(f.chapter.id, backgroundScope,
                { _, _ -> reads++; null }, { _, _ -> opens++; null })
            for (query in listOf("", " ", "x".repeat(257), "Hello\u0000")) {
                controller.setQuery(query); controller.search()
            }
            controller.open("private/path") { error("No navigation") }; runCurrent()
            assertEquals(0, reads); assertEquals(0, opens); assertNull(controller.state.value.snapshot)
            controller.close()
        }
    }

    private fun fixture(body: suspend CoroutineScope.(NativeMemoryPublicationAdapterTest.Fixture, ChapterTranslationStore,
        NativeMemoryPublicationAdapter, NativeSavedTextSearchService, SavedTextSearchSnapshot, PreparedSavedTextOpen) -> Unit) = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val native = f.store(); val task = f.completed(nativeStore = native)
            val authority = ReaderMemoryPublicationAuthority(); val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, native, authority)
            check(adapter.openEditor(selected, 0, 0) != null)
            val service = NativeSavedTextSearchService(native, adapter.memory) { id ->
                f.chapter.takeIf { it.id == id }?.let { SavedTextChapterScope.inspect(it, f.sources) }
            }
            val found = service.search(f.chapter.id, "Hello")!!
            val prepared = service.prepareOpen(found, found.rows.single().id)!!
            body(f, native, adapter, service, found, prepared)
        }
    }
}
