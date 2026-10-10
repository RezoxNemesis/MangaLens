package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test

class BrowserLifecycleDiagnosticsTest {
    private val event = BrowserLifecycleObservation(BrowserLifecyclePhase.VIEW_MEASURE_BEGIN,
        viewOrdinal = 1, attached = true)

    @Test fun disabledDiagnosticAllocatesNoIdentityOrObservation() {
        assertFalse(BrowserLifecycleDiagnostics.enabled)
        assertEquals(0, BrowserLifecycleDiagnostics.nextViewOrdinal())
        BrowserLifecycleDiagnostics.observe(event)
    }

    @Test fun obsoleteFinalizerCannotRemoveNewCaptureAndObserverFailureCannotEscape() {
        val old = BrowserLifecycleDiagnostics.install { fail("old observer") }
        var actual = 0
        BrowserLifecycleDiagnostics.install {
            actual++
            throw IllegalStateException("diagnostic failure")
        }.use {
            old.close()
            BrowserLifecycleDiagnostics.observe(event)
            assertEquals(1, actual)
            assertTrue(BrowserLifecycleDiagnostics.enabled)
        }
        assertFalse(BrowserLifecycleDiagnostics.enabled)
    }

    @Test fun bufferKeepsRepeatedRealPhasesAndRecordsBoundedLoss() {
        val buffer = BrowserLifecycleTraceBuffer(2)
        assertNotNull(buffer.append(event, 100, true))
        assertNotNull(buffer.append(event, 101, true))
        assertNull(buffer.append(event.copy(phase = BrowserLifecyclePhase.VIEW_MEASURE_END), 102, true))
        val saved = buffer.finish()
        assertEquals(2, saved.rows.size)
        assertEquals(1, saved.dropped)
        assertEquals(listOf(100L, 101L), saved.rows.map { it.uptimeMillis })
        assertNull(buffer.append(event, 103, false))
        assertEquals(saved, buffer.finish())
    }

    @Test fun viewOrdinalsRestartWithNewCaptureInsteadOfRetainingViewIdentity() {
        BrowserLifecycleDiagnostics.install {}.use {
            assertEquals(1, BrowserLifecycleDiagnostics.nextViewOrdinal())
            assertEquals(2, BrowserLifecycleDiagnostics.nextViewOrdinal())
        }
        BrowserLifecycleDiagnostics.install {}.use {
            assertEquals(1, BrowserLifecycleDiagnostics.nextViewOrdinal())
        }
    }
}
