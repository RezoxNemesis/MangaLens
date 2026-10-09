package com.mangalens

import com.mangalens.ui.web.WebNavigationObservation
import com.mangalens.ui.web.WebPageLoadState
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WebNavigationFixtureDiagnosticsTest {
    private val scope = WebNavigationFixtureScope("http://localhost:9876/")

    @Test fun recordedNativeFieldsAreRestrictedToTheExactControlledOrigin() {
        assertTrue(scope.accepts("http://localhost:9876/first"))
        assertTrue(scope.accepts("about:blank"))
        assertTrue(scope.accepts(null))
        for (url in listOf("https://provider.example/private", "http://localhost:9999/first",
            "http://localhost.evil.example:9876/", "http://user:secret@localhost:9876/first")) {
            assertFalse("Non-fixture recording allowed: $url", scope.accepts(url))
        }
    }

    @Test fun retryObservationPreservesTheTicketAndPreviousControlledDocument() {
        val loading = WebPageLoadState().start("http://localhost:9876/recover")
        val failed = loading.failed(loading.navigation!!, loading.navigation.url, true, "Connection failed")
        val event = WebNavigationObservation("reload_requested", failed, loading.navigation.url,
            "http://localhost:9876/first", null, loading.navigation.url, "load_url", 42)
        val values = scope.observationValues(event)!!
        assertEquals("http://localhost:9876/recover", values["ticket_url"])
        assertEquals("http://localhost:9876/first", values["visible_url"])
        assertEquals("FAILED", values["phase"])
        assertEquals("Connection failed", values["error"])
        assertEquals("load_url", values["detail"])
    }

    @Test fun unrelatedProviderEventsAreNotRecordedAndAnOldVisibleUrlIsRedacted() {
        val provider = "https://provider.example/private?session=secret"
        val providerState = WebPageLoadState().start(provider)
        assertNull(scope.observationValues(WebNavigationObservation("page_started", providerState,
            provider, provider, provider, null, "", 1)))
        val controlled = WebPageLoadState().start("http://localhost:9876/recover")
        val values = scope.observationValues(WebNavigationObservation("reload_requested", controlled,
            controlled.navigation!!.url, provider, null, controlled.navigation.url, "load_url", 1))!!
        assertEquals("[non-fixture]", values["visible_url"])
        assertFalse(values.toString().contains("provider.example"))
        assertFalse(values.toString().contains("secret"))
    }

    @Test fun stdoutChunksReassembleACompleteUtf8TraceWithoutArtifactAccess() {
        val events = listOf("{\"kind\":\"recovery_error\",\"values\":{\"text\":\"नमस्ते\"}}",
            "{\"kind\":\"reload_dispatched\",\"values\":{\"command\":\"load_url\"}}")
        val lines = webNavigationTraceStdout(events, 2, chunkChars = 32)
        assertTrue("Expected fragmented stdout transport", lines.size > 1)
        assertTrue(lines.all { it.startsWith("MANGALENS_WEB_TRACE part=") })
        val decoded = decode(lines)
        assertTrue(decoded.contains("नमस्ते"))
        assertTrue(decoded.contains("\"dropped_events\":2"))
        assertTrue(decoded.contains("\"stdout_omitted_events\":0"))
        assertTrue(decoded.indexOf("recovery_error") < decoded.indexOf("reload_dispatched"))
    }

    @Test fun boundedStdoutKeepsLatestRecoveryEventsAndMakesOmissionExplicit() {
        val old = "{\"kind\":\"old\",\"data\":\"${"x".repeat(1200)}\"}"
        val terminal = "{\"kind\":\"test_failure\",\"values\":{\"phase\":\"FAILED\"}}"
        val decoded = decode(webNavigationTraceStdout(listOf(old, terminal), 0, maxBytes = 512))
        assertTrue(decoded.toByteArray(Charsets.UTF_8).size <= 512)
        assertTrue(decoded.contains("test_failure"))
        assertFalse(decoded.contains("\"old\""))
        assertTrue(decoded.contains("\"stdout_omitted_events\":1"))
    }

    @Test fun emptyTraceStillHasAMachineReadableCompletedTransport() {
        val lines = webNavigationTraceStdout(emptyList(), 0)
        assertEquals(1, lines.size)
        assertTrue(decode(lines).contains("\"events\":[]"))
    }

    private fun decode(lines: List<String>): String = String(Base64.getDecoder().decode(
        lines.joinToString("") { it.substringAfter(" data=") }), Charsets.UTF_8)
}
