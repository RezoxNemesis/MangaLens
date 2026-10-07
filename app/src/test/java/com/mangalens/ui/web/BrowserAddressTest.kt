package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test

class BrowserAddressTest {
    @Test fun domainsAndSearchWorkWithoutChangingExplicitUrls() {
        assertEquals("https://example.com/read/2", BrowserAddress.resolve("example.com/read/2"))
        assertEquals("https://example.com/v?a=1", BrowserAddress.resolve("https://example.com/v?a=1"))
        assertTrue(BrowserAddress.resolve("manga reading order")!!.contains("q=manga+reading+order"))
    }
    @Test fun addressBarCannotExecuteScriptsOrReadLocalFiles() {
        assertNull(BrowserAddress.resolve("javascript:alert(1)"))
        assertNull(BrowserAddress.resolve("file:///data/data/com.mangalens/secret"))
        assertNull(BrowserAddress.resolve("https://user:password@example.com"))
        assertNull(BrowserAddress.resolve(""))
    }
}
