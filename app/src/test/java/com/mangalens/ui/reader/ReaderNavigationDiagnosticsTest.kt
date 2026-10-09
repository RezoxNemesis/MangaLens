package com.mangalens.ui.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderNavigationDiagnosticsTest {
    private val event = ReaderNavigationObservation("pager_edge_tap", "fixture", ReaderPositionState.initial("ltr", 0, 0),
        "ltr", 0, 0, true, true, true, targetPage = 1)

    @Test fun sourceObservationKeepsPhysicalViewportSeparateFromTheAcceptedCommand() {
        var seen: ReaderNavigationObservation? = null
        ReaderNavigationDiagnostics.install { seen = it }.use {
            ReaderNavigationDiagnostics.observe(event.copy(position = event.position.navigate(1, 3)))
            assertEquals(1, seen!!.position.page)
            assertEquals(0, seen!!.physicalPage)
            assertTrue(seen!!.position.restoring)
            assertEquals("pager_edge_tap", seen!!.action)
        }
        assertFalse(ReaderNavigationDiagnostics.enabled)
    }

    @Test fun evidenceFailureCannotReplaceARealNavigationOutcome() {
        ReaderNavigationDiagnostics.install { throw AssertionError("fixture storage failed") }.use {
            ReaderNavigationDiagnostics.observe(event)
        }
        assertFalse(ReaderNavigationDiagnostics.enabled)
    }

    @Test fun anOlderFixtureFinalizerCannotRemoveANewerObserver() {
        val old = ReaderNavigationDiagnostics.install { fail("obsolete observer must not run") }
        var calls = 0
        ReaderNavigationDiagnostics.install { calls++ }.use {
            old.close()
            ReaderNavigationDiagnostics.observe(event)
            assertEquals(1, calls)
            assertTrue(ReaderNavigationDiagnostics.enabled)
        }
        assertFalse(ReaderNavigationDiagnostics.enabled)
    }
}
