package com.mangalens.core.adblock

import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN policy controls; these do not qualify an actual browser/provider. */
class AdBlockProtectionPolicyTest {
    @Test fun hostKeysNormalizeCaseAndDnsDotWithoutCollapsingSubdomains() {
        assertEquals("reader.example", AdBlockSite.from("https://READER.example.:443/chapter?token=secret#panel")?.host)
        assertEquals("www.reader.example", AdBlockSite.from("https://www.reader.example/")?.host)
        assertEquals("https://reader.example", AdBlockSite.from("https://reader.example:443/")?.origin)
        assertEquals("https://reader.example:8443", AdBlockSite.from("https://reader.example:8443/")?.origin)
    }
    @Test fun credentialsNonWebAndMalformedPortsHaveNoSettingAuthority() {
        for (url in listOf("https://user:secret@reader.example/", "javascript:alert(1)", "file:///tmp/a", "https://reader.example:99999/"))
            assertNull(url, AdBlockSite.from(url))
    }
    @Test fun standardRetainsTheExistingPolicyAndAllowOnlyDisablesAdRules() {
        val url = "https://cdn.doubleclick.net/ad.js"
        assertEquals(AdBlockRequestPolicy.blockingReason(url, "script"), AdBlockProtectionPolicy.reason(url, "script", AdBlockMode.STANDARD))
        assertNull(AdBlockProtectionPolicy.reason(url, "script", AdBlockMode.ALLOW))
    }
    @Test fun strictHasDistinctExplicitAnalyticsWithoutBlockingSharedMediaOrAuthentication() {
        val collect = "https://www.google-analytics.com/g/collect?cid=secret"
        assertNull(AdBlockProtectionPolicy.reason(collect, "fetch", AdBlockMode.STANDARD))
        assertNotNull(AdBlockProtectionPolicy.reason(collect, "fetch", AdBlockMode.STRICT))
        for (url in listOf("https://rr.googlevideo.com/videoplayback?mime=video/mp4", "https://cdninstagram.com/media/clip.mp4", "https://challenges.cloudflare.com/g/collect", "https://reader.example/account/login"))
            assertNull(url, AdBlockProtectionPolicy.reason(url, "fetch", AdBlockMode.STRICT))
    }
    @Test fun strictDoesNotBlockAnalyticsEditorialNavigationOrLookalikeHosts() {
        assertNull(AdBlockProtectionPolicy.reason("https://www.google-analytics.com/g/collect", "document", AdBlockMode.STRICT))
        assertNull(AdBlockProtectionPolicy.reason("https://google-analytics.com.example/g/collect", "fetch", AdBlockMode.STRICT))
        assertNull(AdBlockProtectionPolicy.reason("https://reader.example/analytics/story.mp4", "video", AdBlockMode.STRICT))
    }
    @Test fun heldPolicyTicketAndKnownRetiredRefererCannotPublishAfterNavigation() {
        val gate = AdBlockDocumentPolicyGate()
        val old = gate.started("https://old.example/chapter?secret=1", AdBlockMode.STRICT, true)
        val current = gate.started("https://new.example/chapter", AdBlockMode.ALLOW, true)
        assertNull(gate.withCurrent(old) { "retired" })
        assertNull(gate.forRequest("https://old.example/chapter?secret=1"))
        assertEquals(current, gate.forRequest("https://new.example/chapter"))
        assertEquals(AdBlockMode.ALLOW, gate.withCurrent(current) { it.mode })
        gate.close(); assertNull(gate.forRequest(null))
    }
    @Test fun registrationReplacesOnlyItsOwnedOriginAndModeLease() {
        val registration = AdBlockDocumentStartRegistration(); val view = Any(); var closed = 0
        assertTrue(registration.updateScoped(view, true, "reader:standard") { { closed++ } })
        assertTrue(registration.updateScoped(view, true, "reader:strict") { { closed++ } })
        assertEquals(1, closed)
        registration.updateScoped(view, false, "reader:allow") { error("Allow cannot install filtering") }
        assertEquals(2, closed)
    }
}
