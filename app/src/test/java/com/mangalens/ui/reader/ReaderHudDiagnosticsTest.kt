package com.mangalens.ui.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderHudDiagnosticsTest {
    private val state = ReaderHudSnapshot(ReaderHudMode.VERTICAL, 0, 0, 0, false, true,
        false, true, false, 1f, 0f, 0f)
    private val event = ReaderHudObservation(ReaderHudPhase.WRITE, state, ReaderHudWriter.VERTICAL_TAP,
        previousHudVisible = false)

    @Test fun privateReaderAndDifferentFixtureCannotEnterSelectedSyntheticTrace() {
        var calls = 0
        ReaderHudDiagnostics.install(setOf(ReaderHudFixture.MODE)) { _, _ -> calls++ }.use {
            ReaderHudDiagnostics.observe("private-user-chapter", "Private title", event)
            ReaderHudDiagnostics.observe("0123456789abcdef0123456789abcdef", "Reader geometry QA", event)
            ReaderHudDiagnostics.observe("wrong-mode-chapter", "Reader mode QA", event)
            ReaderHudDiagnostics.observe("mode-qa", "Reader mode QA", event)
            assertEquals(1, calls)
        }
        assertFalse(ReaderHudDiagnostics.enabled)
    }

    @Test fun obsoleteFinalizerAndThrowingObserverCannotChangeCurrentWriter() {
        val old = ReaderHudDiagnostics.install(setOf(ReaderHudFixture.MODE)) { _, _ -> fail("stale observer") }
        var writes = 0
        ReaderHudDiagnostics.install(setOf(ReaderHudFixture.MODE)) { _, _ ->
            writes++
            throw IllegalStateException("trace storage failure")
        }.use {
            old.close()
            ReaderHudDiagnostics.observe("mode-qa", "Reader mode QA", event)
            assertEquals(1, writes)
            assertTrue(ReaderHudDiagnostics.enabled)
        }
        assertFalse(ReaderHudDiagnostics.enabled)
    }

    @Test fun repeatedWriterCallsRemainVisibleWhileDuplicateCompositionAndBufferAreBounded() {
        val buffer = ReaderHudTraceBuffer(4)
        val committed = event.copy(phase = ReaderHudPhase.COMMITTED, writer = ReaderHudWriter.NONE)
        buffer.append(ReaderHudFixture.MODE, committed, 100)
        buffer.append(ReaderHudFixture.MODE, committed, 110)
        buffer.append(ReaderHudFixture.MODE, event, 120)
        buffer.append(ReaderHudFixture.MODE, event, 130)
        buffer.append(ReaderHudFixture.MODE, event.copy(writer = ReaderHudWriter.PARENT_TAP), 140)
        buffer.append(ReaderHudFixture.MODE, event, 150)
        val trace = buffer.finish()
        assertEquals(4, trace.rows.size)
        assertEquals(1, trace.dropped)
        assertEquals(listOf(ReaderHudWriter.VERTICAL_TAP, ReaderHudWriter.VERTICAL_TAP,
            ReaderHudWriter.PARENT_TAP), trace.rows.drop(1).map { it.event.writer })
        buffer.append(ReaderHudFixture.MODE, event, 160)
        assertEquals(trace, buffer.finish())
    }

    @Test fun documentScopeRequiresSameGeneratedUuidInTitleAndChapter() {
        val id = "00000000-0000-4000-8000-000000000001"
        assertEquals(ReaderHudFixture.DOCUMENT,
            ReaderHudDiagnostics.fixture("document-$id", "Imported document $id"))
        assertNull(ReaderHudDiagnostics.fixture("document-$id",
            "Imported document 00000000-0000-4000-8000-000000000002"))
        assertNull(ReaderHudDiagnostics.fixture("document-../private", "Imported document ../private"))
    }
}
