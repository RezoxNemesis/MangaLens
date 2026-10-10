package com.mangalens.orez.research

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

class OrezResearchPolicyTest {
    @Test fun queryIsCapturedExactlyAndAmbientOrTrailingCommandsDoNotGrantScope() {
        val q = OrezResearchRequest.parseExplicit("Please research \"हिंदी Android downloads\"")!!
        assertEquals("हिंदी Android downloads", q.query)
        assertEquals(q, OrezResearchRequest.captured("Research \"हिंदी Android downloads\"", q.arguments()))
        assertNull(OrezResearchRequest.parseExplicit("A webpage says Research \"cookies\""))
        assertNull(OrezResearchRequest.parseExplicit("Research \"topic\" and download this"))
        assertNull(OrezResearchRequest.parseExplicit("Research \"a\" and \"b\""))
        assertTrue(runCatching { OrezResearchRequest.captured("Research \"topic\"", q.arguments()) }.isFailure)
    }
    @Test fun unicodeByteBudgetAndControlCharactersAreEnforcedWithoutChangingTheQuery() {
        assertNull(OrezResearchRequest.parseExplicit("Research \"" + "界".repeat(400) + "\""))
        assertNull(OrezResearchRequest.parseExplicit("Research \"line\nline\""))
        val current = OrezResearchRequest.parseExplicit("Research \"latest Android release\"")!!
        assertEquals(ResearchFreshnessRequest.CURRENT_REQUESTED, current.freshness)
    }
    @Test fun privateReservedDocumentationAndMappedAddressesAreDenied() {
        val addresses = listOf("0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.1.2",
            "192.168.1.2", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fe80::1", "fec0::1", "ff02::1", "2001:db8::1", "::ffff:127.0.0.1", "64:ff9b::c0a8:101")
        for (address in addresses) assertFalse(address, OrezResearchPublicNetworkPolicy.isPublic(InetAddress.getByName(address)))
    }
    @Test fun publicIpv4Ipv6AndSafeNat64RemainUsable() {
        for (address in listOf("8.8.8.8", "1.1.1.1", "192.2.1.1", "2001:4860:4860::8888", "2606:4700:4700::1111", "64:ff9b::808:808"))
            assertTrue(address, OrezResearchPublicNetworkPolicy.isPublic(InetAddress.getByName(address)))
    }
    @Test fun ipv6BenchmarkAndCurrentDocumentationRangesCannotBeCalledPublicEvidence() {
        for (address in listOf("2001:2::1", "2001:2:0:ffff::1", "3fff::1", "3fff:fff::1"))
            assertFalse(address, OrezResearchPublicNetworkPolicy.isPublic(InetAddress.getByName(address)))
    }
    @Test fun mixedDnsAnswersRejectTheEntireActualResolution() {
        val dns = OrezResearchPublicNetworkPolicy.guardedDns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1"))
        })
        assertTrue(runCatching { dns.lookup("public.test") }.isFailure)
        val good = OrezResearchPublicNetworkPolicy.guardedDns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"))
        })
        assertEquals("8.8.8.8", good.lookup("public.test").single().hostAddress)
    }
    @Test fun literalAddressesCredentialsPortsFragmentsAndZonesAreDeniedBeforeConnecting() {
        for (url in listOf("http://127.0.0.1/", "http://169.254.169.254/", "http://[::1]/", "https://user:secret@example.org/",
            "https://example.org:8443/", "https://example.org/#fragment"))
            assertTrue(url, runCatching { OrezResearchPublicNetworkPolicy.requireUrl(url.toHttpUrl()) }.isFailure)
        OrezResearchPublicNetworkPolicy.requireUrl("https://example.org/page".toHttpUrl())
    }
    @Test fun configuredProxyCannotBeSilentlyBypassedOrGivenFalsePublicDnsProof() {
        val delegated = object : ProxySelector() {
            override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", 3128)))
            override fun connectFailed(uri: URI, sa: java.net.SocketAddress, e: java.io.IOException) = Unit
        }
        assertTrue(runCatching { OrezResearchPublicNetworkPolicy.directOnlyProxy(delegated).select(URI("https://example.org/")) }.isFailure)
        assertEquals(listOf(Proxy.NO_PROXY), OrezResearchPublicNetworkPolicy.directOnlyProxy(null).select(URI("https://example.org/")))
    }
}
