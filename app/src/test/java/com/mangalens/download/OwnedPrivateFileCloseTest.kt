package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Authored UNRUN: real close lifetime and callback distinctions, not arbitrary IO failures. */
class OwnedPrivateFileCloseTest {
    @Test fun ordinaryNetworkFailureWithSuccessfulFileCloseDoesNotRetainTheFileLease() {
        var retained = false; var closes = 0
        val resource = Closeable { closes++ }
        val network = IOException("controlled network failure")
        val actual = assertThrows(IOException::class.java) {
            resource.useOwnedPrivateFile({ retained = true }) { throw network }
        }
        assertSame(network, actual); assertEquals(1, closes); assertFalse(retained)
    }
    @Test fun actualPrivateCloseFailureRetainsTheLeaseAndFailsClosed() {
        var retained = false; var closes = 0
        val resource = Closeable { closes++; throw IOException("controlled failed private close") }
        assertThrows(UnprovenPrivateFileCloseException::class.java) {
            resource.useOwnedPrivateFile({ retained = true }) { "copied" }
        }
        assertEquals(1, closes); assertTrue(retained)
    }
    @Test fun bodyAndCloseFailurePreserveBothAndStillRetainOwnership() {
        var retained = false
        val body = IOException("controlled body failure")
        val resource = Closeable { throw IOException("controlled close failure") }
        val actual = assertThrows(IOException::class.java) {
            resource.useOwnedPrivateFile({ retained = true }) { throw body }
        }
        assertSame(body, actual); assertTrue(retained)
        assertTrue(actual.suppressed.any { it is UnprovenPrivateFileCloseException })
    }
    @Test fun wrapperCreationFailureReturnsTheActualRawHandleExactlyOnce() {
        val closes = AtomicInteger(); var retained = false
        val raw = Closeable { closes.incrementAndGet() }
        val original = IllegalStateException("controlled wrapper creation failure")
        val actual = assertThrows(IllegalStateException::class.java) {
            raw.wrapOwnedPrivateFile<Closeable, Closeable>({ retained = true }) { throw original }
        }
        assertSame(original, actual); assertEquals(1, closes.get()); assertFalse(retained)
    }
}
