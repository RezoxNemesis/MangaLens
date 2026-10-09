package com.mangalens

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class WebFixtureEvent(val sequence: Int, val elapsedMs: Long, val kind: String, val values: Map<String, String>)

/** Test-only ordered observations; no Android dependency, network calls, or user-page storage. */
internal class WebNavigationFixtureTrace(private val clock: () -> Long) {
    private val started = clock()
    private val events = ArrayList<WebFixtureEvent>()
    private var sequence = 0
    private var dropped = 0

    @Synchronized fun record(kind: String, values: Map<String, String> = emptyMap()): WebFixtureEvent {
        val event = WebFixtureEvent(++sequence, clock() - started, kind,
            values.mapValues { (_, value) -> value.take(4096) }.toMap())
        if (events.size < 256) events.add(event) else dropped++
        return event
    }

    @Synchronized fun snapshot(): List<WebFixtureEvent> = events.toList()
    @Synchronized fun droppedCount(): Int = dropped
}

/** Preserves the smoke fixture's existing 30-second response hold and records its actual decision. */
internal class WebFirstDocumentGate(
    private val trace: WebNavigationFixtureTrace,
    private val wait: (CountDownLatch, Long, TimeUnit) -> Boolean = { latch, timeout, unit -> latch.await(timeout, unit) }
) {
    private val release = CountDownLatch(1)
    private val released = AtomicBoolean(false)

    fun awaitRelease(): Boolean {
        val request = trace.record("first_request", mapOf("already_released" to (release.count == 0L).toString()))
        val allowed = wait(release, 30, TimeUnit.SECONDS)
        val completed = trace.record("first_gate_completed", mapOf("released" to allowed.toString(),
            "response_status" to if (allowed) "200" else "408", "request_sequence" to request.sequence.toString()))
        trace.record("first_gate_duration", mapOf("hold_ms" to (completed.elapsedMs - request.elapsedMs).toString()))
        return allowed
    }

    fun release(reason: String) {
        if (released.compareAndSet(false, true)) {
            trace.record("first_release", mapOf("reason" to reason))
            release.countDown()
        }
    }

    fun <T> captureLoading(capture: () -> T): T = try { capture() }
        finally { release("loading-capture-complete") }
}
