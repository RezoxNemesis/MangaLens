package com.mangalens.core.adblock

import org.junit.Assert.*
import org.junit.Test

class AdBlockInjectionGateTest {
    private val first = "http://localhost:9876/first"
    private val second = "http://localhost:9876/second"

    @Test fun queuedInjectionCannotRunAfterDocumentReplacement() {
        val gate = AdBlockInjectionGate()
        val old = gate.started(first)!!
        gate.started(second)
        assertFalse(gate.permits(old, second, true))
        assertFalse(gate.permits(old, first, true))
    }
    @Test fun currentDocumentCanReceiveItsStartAndFinishFallbackWithoutChangingIdentity() {
        val gate = AdBlockInjectionGate()
        val ticket = gate.started(first)!!
        assertEquals(ticket, gate.currentFor(first))
        assertTrue(gate.permits(ticket, "$first#heading", true))
    }
    @Test fun disableAfterQueueingIsRecheckedBeforeJavascript() {
        val gate = AdBlockInjectionGate()
        val ticket = gate.started(first)!!
        assertFalse(gate.permits(ticket, first, false))
    }
    @Test fun stoppedOrRedirectedNativeDocumentDoesNotReceiveThePreviousInjection() {
        val gate = AdBlockInjectionGate()
        val ticket = gate.started(first)!!
        assertFalse(gate.permits(ticket, "about:blank", true))
        assertNull(gate.currentFor(second))
    }
    @Test fun terminalCleanupFencesHeldCallbacksAndLaterNativeStarts() {
        val gate = AdBlockInjectionGate()
        val ticket = gate.started(first)!!
        gate.close()
        assertFalse(gate.permits(ticket, first, true))
        assertNull(gate.currentFor(first))
        assertNull(gate.started(second))
    }
    @Test fun sameUrlNewNavigationStillRejectsTheOldCapturedCallback() {
        val gate = AdBlockInjectionGate()
        val old = gate.started(first)!!
        val new = gate.started(first)!!
        assertFalse(gate.permits(old, first, true))
        assertTrue(gate.permits(new, first, true))
    }
}
