package com.mangalens.ui.web

import com.mangalens.core.adblock.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Authored UNRUN storage/readback controls, with explicit in-memory IO rather than Android filesystem proof. */
class BrowserProtectionStoreTest {
    private class Io : BrowserWorkspaceIo {
        var bytes: ByteArray? = null
        var fail = false
        var changedReadback = false
        override fun read(maxBytes: Int) = bytes?.copyOf()?.let { if (changedReadback) "broken".toByteArray() else it }
        override fun write(bytes: ByteArray) { if (fail) throw IOException("held storage failure"); this.bytes = bytes.copyOf() }
    }
    @Test fun coldReadbackRetainsOnlyExactHostModesAndNoUrlSecrets() {
        val io = Io(); val store = BrowserProtectionStore(io)
        store.set(requireNotNull(AdBlockSite.from("https://reader.example/chapter?token=secret")), AdBlockMode.ALLOW) { true }
        val reopened = BrowserProtectionStore(io).snapshot()
        assertEquals(AdBlockMode.ALLOW, reopened.modeFor("http://reader.example/another"))
        assertEquals(AdBlockMode.STANDARD, reopened.modeFor("https://www.reader.example/another"))
        assertFalse(String(requireNotNull(io.bytes)).contains("secret"))
        assertFalse(String(requireNotNull(io.bytes)).contains("chapter"))
    }
    @Test fun standardRemovesOverrideAndOtherHostsRemainUnchanged() {
        val store = BrowserProtectionStore(Io()); val a = requireNotNull(AdBlockSite.from("https://a.example/")); val b = requireNotNull(AdBlockSite.from("https://b.example/"))
        store.set(a, AdBlockMode.ALLOW) { true }; store.set(b, AdBlockMode.STRICT) { true }; store.set(a, AdBlockMode.STANDARD) { true }
        assertEquals(mapOf(b.host to AdBlockMode.STRICT), store.snapshot().modes)
    }
    @Test fun retiredOwnerAndFailedWriteCannotBecomeReportedSavedModes() {
        val io = Io(); val store = BrowserProtectionStore(io); val site = requireNotNull(AdBlockSite.from("https://a.example/"))
        assertThrows(IllegalStateException::class.java) { store.set(site, AdBlockMode.ALLOW) { false } }
        assertNull(io.bytes); io.fail = true
        assertThrows(IOException::class.java) { store.set(site, AdBlockMode.ALLOW) { true } }
        assertEquals(AdBlockMode.STANDARD, store.snapshot().modeFor("https://a.example/"))
    }
    @Test fun changedReadbackAndCorruptCodecAreVisibleFailures() {
        val io = Io(); val store = BrowserProtectionStore(io); io.changedReadback = true
        assertThrows(IllegalStateException::class.java) { store.set(requireNotNull(AdBlockSite.from("https://a.example/")), AdBlockMode.STRICT) { true } }
        assertEquals(AdBlockMode.STANDARD, store.snapshot().modeFor("https://a.example/"))
        assertThrows(IllegalArgumentException::class.java) { BrowserProtectionStore(io) }
    }
}
