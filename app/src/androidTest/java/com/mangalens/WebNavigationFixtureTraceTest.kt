package com.mangalens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Host-runnable tests of the same held-response gate used by the real WebView smoke fixture. */
class WebNavigationFixtureTraceTest {
    @Test fun heldCaptureReleasesTheResponseOnlyAfterItsEvidenceIsComplete() = runBlocking {
        val clock = AtomicLong(0)
        val trace = WebNavigationFixtureTrace(clock::get)
        val requestEntered = CountDownLatch(1)
        val captureEntered = CountDownLatch(1)
        val finishCapture = CountDownLatch(1)
        val gate = WebFirstDocumentGate(trace) { latch, timeout, unit ->
            assertEquals(30L, timeout); assertEquals(TimeUnit.SECONDS, unit)
            requestEntered.countDown(); latch.await(2, TimeUnit.SECONDS)
        }
        val response = async(Dispatchers.IO) { gate.awaitRelease() }
        val capture = async(Dispatchers.IO) {
            gate.captureLoading {
                captureEntered.countDown()
                check(finishCapture.await(2, TimeUnit.SECONDS))
                clock.set(9000) // The actual cp4 capture/release fit well within the unchanged hold.
                trace.record("loading_capture_finished")
            }
        }
        try {
            assertTrue(requestEntered.await(2, TimeUnit.SECONDS))
            assertTrue(captureEntered.await(2, TimeUnit.SECONDS))
            assertFalse(response.isCompleted)
            finishCapture.countDown()
            withTimeout(1000) { capture.await() }
            assertTrue(withTimeout(1000) { response.await() })
            val events = trace.snapshot()
            assertTrue(events.indexOfFirst { it.kind == "loading_capture_finished" } < events.indexOfFirst { it.kind == "first_release" })
            assertEquals("200", events.single { it.kind == "first_gate_completed" }.values["response_status"])
            assertEquals("9000", events.single { it.kind == "first_gate_duration" }.values["hold_ms"])
        } finally { finishCapture.countDown(); gate.release("test-cleanup"); capture.cancelAndJoin(); response.cancelAndJoin() }
    }

    @Test fun failedEvidenceCaptureReleasesTheResponseBeforeFailureCollectionCanStall() = runBlocking {
        val trace = WebNavigationFixtureTrace { 0L }
        val requestEntered = CountDownLatch(1)
        val gate = WebFirstDocumentGate(trace) { latch, _, _ -> requestEntered.countDown(); latch.await(2, TimeUnit.SECONDS) }
        val response = async(Dispatchers.IO) { gate.awaitRelease() }
        try {
            assertTrue(requestEntered.await(2, TimeUnit.SECONDS))
            val original = AssertionError("screenshot capture failed")
            try { gate.captureLoading<Unit> { throw original }; fail("capture failure was swallowed") }
            catch (failure: AssertionError) { assertSame(original, failure) }
            assertTrue("a failed screenshot kept the fixture blocked until later test teardown", withTimeout(500) { response.await() })
            assertEquals("loading-capture-complete", trace.snapshot().single { it.kind == "first_release" }.values["reason"])
        } finally { gate.release("test-cleanup"); response.cancelAndJoin() }
    }

    @Test fun aRequestArrivingAfterCaptureReleaseDoesNotStartAnotherHold() {
        val trace = WebNavigationFixtureTrace { 0L }
        val gate = WebFirstDocumentGate(trace) { latch, timeout, unit ->
            assertEquals(30L, timeout); assertEquals(TimeUnit.SECONDS, unit)
            assertEquals(0L, latch.count)
            latch.await(0, TimeUnit.MILLISECONDS)
        }
        gate.captureLoading { trace.record("loading_capture_finished") }
        assertTrue(gate.awaitRelease())
        assertEquals("true", trace.snapshot().single { it.kind == "first_request" }.values["already_released"])
        assertEquals("200", trace.snapshot().single { it.kind == "first_gate_completed" }.values["response_status"])
    }

    @Test fun anActualGateTimeoutRecords408RatherThanACompletedDocument() {
        val clock = AtomicLong(0)
        val trace = WebNavigationFixtureTrace(clock::get)
        val gate = WebFirstDocumentGate(trace) { _, timeout, unit ->
            assertEquals(30L, timeout); assertEquals(TimeUnit.SECONDS, unit)
            clock.set(30000); false
        }
        assertFalse(gate.awaitRelease())
        assertEquals("408", trace.snapshot().single { it.kind == "first_gate_completed" }.values["response_status"])
        assertEquals("30000", trace.snapshot().single { it.kind == "first_gate_duration" }.values["hold_ms"])
        assertFalse(trace.snapshot().any { it.kind == "first_release" })
    }
}
