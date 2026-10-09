package com.mangalens

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProviderFailureDiagnosticsTest {
    @Test fun swallowedHarnessExceptionRetainsAppAndFrameworkLocationsWithoutTheMessage() {
        val failure = IllegalStateException("https://signed.example/token=private Cookie: private").apply {
            stackTrace = arrayOf(
                StackTraceElement("com.mangalens.ProviderUiHarness", "activity", "ProviderUiHarness.kt", 194),
                StackTraceElement("android.app.ActivityThread", "handleResumeActivity", "ActivityThread.java", 3100)
            )
        }
        val nodes = ProviderFailureDiagnostics.capture(failure)
        assertEquals("java.lang.IllegalStateException", nodes.single().type)
        assertEquals(listOf("com.mangalens.ProviderUiHarness", "android.app.ActivityThread"),
            nodes.single().frames.map { it.owner })
        assertEquals(194, nodes.single().frames.first().line)
        val rendered = nodes.toString()
        assertFalse(rendered.contains("private"))
        assertFalse(rendered.contains("signed.example"))
        assertFalse(rendered.contains("Cookie"))
    }

    @Test fun nativeCauseAndCompatibilitySuppressedFailureRemainDistinguishable() {
        val cause = IOException("secret native stderr")
        val fallback = IllegalArgumentException("secret response body")
        val root = IllegalStateException("secret wrapper", cause).apply { addSuppressed(fallback) }
        val nodes = ProviderFailureDiagnostics.capture(root)
        assertEquals(listOf("root", "cause", "suppressed"), nodes.map { it.relation })
        assertEquals(listOf(null, 0, 0), nodes.map { it.parent })
        assertFalse(nodes.toString().contains("secret"))
    }

    @Test fun arbitraryFrameFieldsAndAbsoluteFileNamesCannotExportUrlOrHeaders() {
        val failure = IOException("https://private/source Cookie: token").apply {
            stackTrace = arrayOf(
                StackTraceElement("com.mangalens.https://private", "load", "Token.kt", 1),
                StackTraceElement("com.mangalens.ProviderUiHarness", "Cookie: private", "Token.kt", 1),
                StackTraceElement("com.mangalens.ProviderUiHarness", "state", "/private/cookie/Secret.kt", 72),
                StackTraceElement("unapproved.provider.Custom", "request", "https://private", 42)
            )
        }
        val nodes = ProviderFailureDiagnostics.capture(failure)
        assertEquals(1, nodes.single().frames.size)
        assertEquals("state", nodes.single().frames.single().method)
        assertFalse(nodes.toString().contains("private"))
        assertFalse(nodes.toString().contains("Secret.kt"))
    }

    @Test fun cyclicAndWideFailureGraphsHaveFiniteNodeAndFrameBudgets() {
        val root = IOException("private")
        val second = IOException("private", root)
        root.initCause(second)
        repeat(100) { index ->
            root.addSuppressed(IOException("private $index").apply {
                stackTrace = Array(100) {
                    StackTraceElement("com.mangalens.ProviderUiHarness", "state", "ProviderUiHarness.kt", 72)
                }
            })
        }
        val nodes = ProviderFailureDiagnostics.capture(root)
        assertTrue(nodes.size <= 16)
        assertTrue(nodes.sumOf { it.frames.size } <= 96)
        assertTrue(nodes.all { it.frames.size <= 8 })
        assertTrue(nodes.size > 2)
    }
}
