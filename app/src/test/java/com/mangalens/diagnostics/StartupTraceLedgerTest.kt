package com.mangalens.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class StartupTraceLedgerTest {
    private fun sample(elapsed: Long = 10, cpu: Long = 4, tid: Int = 7) = StartupClockSample(elapsed, cpu, tid)

    @Test fun everyFixedStageHasAtMostOneEventOrStartEndPairAndDrainsOnce() {
        val ledger = StartupTraceLedger()
        for (stage in StartupStage.entries) {
            if (stage.span) {
                val span = ledger.begin(stage, sample())!!
                assertNull(ledger.begin(stage, sample()))
                assertTrue(ledger.end(span, sample(30, 9), true))
                assertFalse(ledger.end(span, sample(40, 12), true))
                assertFalse(ledger.mark(stage, sample()))
            } else {
                assertTrue(ledger.mark(stage, sample()))
                assertFalse(ledger.mark(stage, sample()))
                assertNull(ledger.begin(stage, sample()))
            }
        }
        val records = ledger.drainUnemitted()
        assertEquals(StartupStage.entries.size + StartupStage.entries.count { it.span }, records.size)
        assertTrue(ledger.drainUnemitted().isEmpty())
        records.filter { it.phase == StartupTracePhase.END }.forEach {
            assertEquals(20L, it.elapsedDurationNs ?: -1L)
            assertEquals(5L, it.cpuDurationNs ?: -1L)
            assertEquals(true, it.completed)
        }
    }

    @Test fun endArrivingAfterTheInitialOptInDrainIsStillAvailableExactlyOnce() {
        val ledger = StartupTraceLedger()
        val span = ledger.begin(StartupStage.ACTIVITY_CREATE, sample())!!
        assertEquals(listOf(StartupTracePhase.START), ledger.drainUnemitted().map { it.phase })
        ledger.end(span, sample(30, 9), false)
        val end = ledger.drainUnemitted().single()
        assertEquals(StartupTracePhase.END, end.phase)
        assertEquals(false, end.completed)
        assertTrue(ledger.drainUnemitted().isEmpty())
    }

    @Test fun foreignSpanAndCrossThreadCpuCannotBecomeAcceptedTimingEvidence() {
        val first = StartupTraceLedger()
        val second = StartupTraceLedger()
        val span = first.begin(StartupStage.VM_LOOKUP, sample())!!
        assertFalse(second.end(span, sample(30, 9), true))
        assertTrue(second.drainUnemitted().isEmpty())
        first.end(span, sample(30, 90, tid = 8), true)
        val end = first.drainUnemitted().single { it.phase == StartupTracePhase.END }
        assertEquals(20L, end.elapsedDurationNs ?: -1L)
        assertNull(end.cpuDurationNs)
    }

    @Test fun concurrentClaimsAndEmissionCannotDuplicateAStageOrLoseItsCompletion() {
        val ledger = StartupTraceLedger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val spans = pool.invokeAll((1..32).map { Callable { ledger.begin(StartupStage.VM_REPOSITORY_CREATE, sample()) } })
                .mapNotNull { it.get() }
            assertEquals(1, spans.size)
            val first = pool.invokeAll((1..16).map { Callable { ledger.drainUnemitted() } }).flatMap { it.get() }
            assertEquals(1, first.size)
            val results = pool.invokeAll((1..32).map { Callable { ledger.end(spans.single(), sample(30, 9), true) } }).map { it.get() }
            assertEquals(1, results.count { it })
            val last = pool.invokeAll((1..16).map { Callable { ledger.drainUnemitted() } }).flatMap { it.get() }
            assertEquals(listOf(StartupTracePhase.END), last.map { it.phase })
        } finally { pool.shutdownNow() }
    }
}
