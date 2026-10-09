package com.mangalens.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class OcrRecognizerSessionTest {
    @Test fun explicitLatinTilesAndTheirCropReuseOneNativeClient() = runBlocking {
        val tracker = Tracker()
        val session = tracker.session()
        val ids = mutableListOf<Int>()
        try {
            for (phase in listOf("tile-1", "crop-1", "tile-2", "tile-3")) {
                session.read("LATIN") { ids += it.id; tracker.events += "read:$phase" }
                assertEquals("Do not dispose and reload between same-script reads", 0, tracker.disposed)
            }
        } finally { session.close() }
        assertEquals(listOf(1, 1, 1, 1), ids)
        assertEquals(1, tracker.opened)
        assertEquals(1, tracker.disposed)
        assertEquals(0, tracker.active)
    }

    @Test fun theLastAutoScriptAndNextSameScriptCropDoNotNeedAnotherLoad() = runBlocking {
        val tracker = Tracker()
        val session = tracker.session()
        for (script in listOf("LATIN", "JAPANESE", "KOREAN", "KOREAN")) session.read(script) { }
        session.close()
        assertEquals(3, tracker.opened)
        assertEquals(3, tracker.disposed)
        assertEquals(1, tracker.maxActive)
    }

    @Test fun aScriptSwitchDisposesItsPredecessorBeforeOpeningAnotherClient() = runBlocking {
        val tracker = Tracker()
        val session = tracker.session()
        session.read("LATIN") { }
        session.read("JAPANESE") { }
        session.read("LATIN") { }
        session.close()
        assertEquals(listOf("open:LATIN:1", "close:LATIN:1", "open:JAPANESE:2",
            "close:JAPANESE:2", "open:LATIN:3", "close:LATIN:3"), tracker.events)
        assertEquals(1, tracker.maxActive)
    }

    @Test fun aFailedNativeReadIsDisposedAndTheNextReadGetsAFreshClient() = runBlocking {
        val tracker = Tracker()
        val session = tracker.session()
        val failure = IllegalStateException("native read failed")
        try { session.read("LATIN") { throw failure }; fail("Expected native failure") }
        catch (observed: IllegalStateException) { assertSame(failure, observed) }
        assertEquals(1, tracker.disposed)
        assertEquals(0, tracker.active)
        session.read("LATIN") { assertEquals(2, it.id) }
        session.close()
        assertEquals(2, tracker.disposed)
    }

    @Test fun aFailedNewScriptLoadCannotLeaveThePreviousClientAliveOrPoisonTheSession() = runBlocking {
        val tracker = Tracker()
        val session = tracker.session(failScript = "JAPANESE")
        session.read("LATIN") { }
        try { session.read("JAPANESE") { }; fail("Expected load failure") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, tracker.active)
        session.read("KOREAN") { }
        session.close()
        assertEquals(2, tracker.opened)
        assertEquals(2, tracker.disposed)
        assertEquals(1, tracker.maxActive)
    }

    @Test fun cancellationWaitsForActualNativeCompletionBeforeDisposingTheClient() = runBlocking {
        val tracker = Tracker()
        val session = tracker.session()
        var complete: ((Result<Unit>) -> Unit)? = null
        val started = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                session.read("LATIN") {
                    awaitOcrCompletion<Unit> { callback -> complete = callback; started.complete(Unit) }
                }
            } finally { session.close() }
        }
        started.await()
        owner.cancel()
        yield()
        assertFalse("Owner cannot finish while native still retains the client/image", owner.isCompleted)
        assertEquals(0, tracker.disposed)
        assertEquals(1, tracker.active)
        tracker.events += "native:completed"
        complete!!(Result.success(Unit))
        owner.join()
        assertEquals(listOf("open:LATIN:1", "native:completed", "close:LATIN:1"), tracker.events)
        assertEquals(0, tracker.active)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun anAlreadyCancelledOwnerDoesNotOpenOrInvokeANativeClient() = runBlocking {
        val tracker = Tracker()
        val session = tracker.session()
        val parent = Job().apply { cancel() }
        val owner = launch(parent, start = CoroutineStart.ATOMIC) {
            try { session.read("LATIN") { fail("Cancelled owner entered native") } }
            finally { session.close() }
        }
        owner.cancelAndJoin()
        assertEquals(0, tracker.opened)
        assertEquals(0, tracker.disposed)
    }

    @Test fun terminalCloseIsIdempotentAndCannotReopenTheReleasedPageSession() = runBlocking {
        val tracker = Tracker()
        val session = tracker.session()
        session.read("LATIN") { }
        session.close()
        session.close()
        try { session.read("LATIN") { }; fail("Closed session reopened") }
        catch (_: IllegalStateException) { }
        assertEquals(1, tracker.opened)
        assertEquals(1, tracker.disposed)
    }

    private data class Client(val script: String, val id: Int)
    private class Tracker {
        val events = mutableListOf<String>()
        var active = 0
        var maxActive = 0
        var opened = 0
        var disposed = 0
        fun session(failScript: String? = null) = OcrRecognizerSession<Client>(open = { script ->
            if (script == failScript) throw IllegalArgumentException("load failed")
            active++
            maxActive = maxOf(maxActive, active)
            Client(script, ++opened).also { events += "open:$script:${it.id}" }
        }, dispose = { client ->
            disposed++
            active--
            events += "close:${client.script}:${client.id}"
        })
    }
}
