package com.mangalens.core.translation.inpainting

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN actual production close-ledger algorithm controls; injected failures are explicit. */
class LaMaIoOwnershipTest {
    @Test fun successfulUseAcknowledgesActualCloseExactlyOnce() {
        var closes = 0; val handle = AutoCloseable { closes++ }
        assertEquals(7, withLaMaIoHandle(handle) { 7 }); assertEquals(1, closes)
    }
    @Test fun failedActualCloseRetainsTheHandleAndPriorBodyFailure() {
        val body = IllegalStateException("body"); val close = IllegalStateException("close"); val handle = AutoCloseable { throw close }
        val failure = assertThrows(LaMaIoCloseUnproven::class.java) { withLaMaIoHandle(handle) { throw body } }
        assertSame(close, failure.cause); assertSame(handle, failure.retained[0]); assertSame(body, failure.retained[1])
    }
    @Test fun nestedCloseFailurePreservesBothIncompleteHandleOwners() {
        val outer = AutoCloseable { throw IllegalStateException("outer") }; val inner = AutoCloseable { throw IllegalStateException("inner") }
        val failure = assertThrows(LaMaIoCloseUnproven::class.java) { withLaMaIoHandle(outer) { withLaMaIoHandle(inner) { 1 } } }
        assertSame(outer, failure.retained[0]); val nested = failure.retained[1] as LaMaIoCloseUnproven
        assertSame(inner, nested.retained[0])
    }
    @Test fun producerReturnCannotReleaseWhileItsIndependentCloserIsStillActive() {
        val ledger = LaMaTransferReleaseLedger(); val closer = Any()
        ledger.registered(closer); ledger.producerReturned(); assertFalse(ledger.mayRelease)
        ledger.closeReturned(closer); assertTrue(ledger.mayRelease)
    }
    @Test fun closerReturnCannotReleaseBeforeTheActualProducerReturns() {
        val ledger = LaMaTransferReleaseLedger(); val closer = Any()
        ledger.registered(closer); ledger.closeReturned(closer); assertFalse(ledger.mayRelease)
        ledger.producerReturned(); assertTrue(ledger.mayRelease)
    }
    @Test fun everyRegisteredCloserMustAcknowledgeItsOwnReturn() {
        val ledger = LaMaTransferReleaseLedger(); val first = Any(); val second = Any()
        ledger.registered(first); ledger.registered(second); ledger.producerReturned()
        ledger.closeReturned(first); assertFalse(ledger.mayRelease)
        ledger.closeReturned(second); assertTrue(ledger.mayRelease)
    }
    @Test fun equalReplacementTicketCannotAcknowledgeAnotherResourceOwner() {
        data class Ticket(val value: Int)
        val ledger = LaMaTransferReleaseLedger(); val original = Ticket(1)
        ledger.registered(original); ledger.producerReturned()
        assertThrows(IllegalStateException::class.java) { ledger.closeReturned(Ticket(1)) }
        assertFalse(ledger.mayRelease); ledger.closeReturned(original); assertTrue(ledger.mayRelease)
    }
    @Test fun unprovenClosePermanentlyPreventsReleaseEvenAfterBothReturns() {
        val ledger = LaMaTransferReleaseLedger(); val closer = Any()
        ledger.registered(closer); ledger.closeUnproven(); ledger.producerReturned(); ledger.closeReturned(closer)
        assertFalse(ledger.mayRelease)
    }
    /** Actual coroutine scheduling plus production ledger; no Android manager/network/model is invoked. */
    @Test fun cancelledLazyProducerCanAcknowledgeReturnWithoutEverEnteringItsBody() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ledger = LaMaTransferReleaseLedger(); var entered = false
        val producer = scope.launch(start = CoroutineStart.LAZY) { entered = true }
        producer.invokeOnCompletion { ledger.producerReturned() }
        try {
            assertFalse(ledger.mayRelease); producer.cancel(); withTimeout(2000) { producer.join() }
            assertFalse(entered); assertTrue(ledger.mayRelease)
        } finally { scope.cancel() }
    }
}
