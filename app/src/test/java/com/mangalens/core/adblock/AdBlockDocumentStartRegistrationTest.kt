package com.mangalens.core.adblock

import org.junit.Assert.*
import org.junit.Test

class AdBlockDocumentStartRegistrationTest {
    @Test fun supportedProviderRegistersOnceUntilTheClientDisablesIt() {
        val registration = AdBlockDocumentStartRegistration()
        val view = Any()
        var installs = 0; var removals = 0
        val install = { installs++; { removals++; Unit } }
        assertTrue(registration.update(view, true, install))
        assertTrue(registration.update(view, true, install))
        assertEquals(1, installs)
        assertFalse(registration.update(view, false, install))
        assertEquals(1, removals)
        assertTrue(registration.update(view, true, install))
        assertEquals(2, installs)
    }
    @Test fun ownerReplacementRemovesOnlyItsOwnLeaseBeforeInstallingTheNext() {
        val registration = AdBlockDocumentStartRegistration()
        val events = mutableListOf<String>()
        registration.update(Any(), true) { events += "install-first"; { events += "remove-first" } }
        registration.update(Any(), true) { events += "install-second"; { events += "remove-second" } }
        assertEquals(listOf("install-first", "remove-first", "install-second"), events)
        registration.close()
        assertEquals("remove-second", events.last())
    }
    @Test fun unsupportedOrFailingProviderKeepsTheCallbackFallbackAvailable() {
        val registration = AdBlockDocumentStartRegistration()
        assertFalse(registration.update(Any(), true) { null })
        assertFalse(registration.installed)
        assertFalse(registration.update(Any(), true) { throw IllegalStateException("unsupported provider") })
        assertFalse(registration.installed)
    }
    @Test fun terminalCleanupIsIdempotentAndCannotRegisterFromAHeldCallback() {
        val registration = AdBlockDocumentStartRegistration()
        val view = Any(); var removes = 0; var installs = 0
        registration.update(view, true) { installs++; { removes++ } }
        registration.close(); registration.close()
        assertFalse(registration.update(view, true) { installs++; { removes++ } })
        assertEquals(1, installs); assertEquals(1, removes)
    }
}
