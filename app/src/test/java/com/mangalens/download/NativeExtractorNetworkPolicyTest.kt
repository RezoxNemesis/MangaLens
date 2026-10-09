package com.mangalens.download

import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class NativeExtractorNetworkPolicyTest {
    private val source = "https://video.example/watch?v=public"
    private val systemRoot get() = certificate("system-root-fixture.pem")
    private val userRoot get() = certificate("user-root-fixture.pem")

    @Test fun theSelectedSystemHttpProxyIsPassedWithoutResolvingItsHost() {
        val route = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))
        assertEquals("http://proxy.example:8080", NativeExtractorNetworkPolicy.proxyArgument(source, selector(route)))
    }

    @Test fun proxySelectionReceivesTheActualSourceUri() {
        var selected: URI? = null
        val selector = object : ProxySelector() {
            override fun select(uri: URI): MutableList<Proxy> {
                selected = uri
                return mutableListOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
        }
        NativeExtractorNetworkPolicy.proxyArgument(source, selector)
        assertEquals(URI(source), selected)
    }

    @Test fun nativeRequestsExplicitlyHonorDirectConnections() {
        val request = YoutubeDLRequest(source)
        NativeExtractorNetworkPolicy.applyOptions(request, "")
        val command = request.buildCommand()
        val index = command.indexOf("--proxy")
        assertTrue("DIRECT must override inherited Python proxy variables", index >= 0)
        assertEquals("", command[index + 1])
    }

    @Test fun theFirstSystemRouteIsKeptInsteadOfSkippingToDirect() {
        val route = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))
        assertEquals("http://proxy.example:8080", NativeExtractorNetworkPolicy.proxyArgument(source, selector(route, Proxy.NO_PROXY)))
    }

    @Test fun ipv6SystemProxyAddressesRemainValidProxyUris() {
        val route = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("2001:db8::1", 8080))
        assertEquals("http://[2001:db8::1]:8080", NativeExtractorNetworkPolicy.proxyArgument(source, selector(route)))
    }

    @Test fun aSystemSocksRouteUsesItsNativeProxyProtocol() {
        val route = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("proxy.example", 1080))
        assertEquals("socks5://proxy.example:1080", NativeExtractorNetworkPolicy.proxyArgument(source, selector(route)))
    }

    @Test fun preferSystemRootsKeepsCertificateAndHostnameVerificationEnabled() {
        val request = YoutubeDLRequest(source)
        NativeExtractorNetworkPolicy.applyOptions(request, "http://proxy.example:8080")
        assertEquals("no-certifi", request.getOption("--compat-options"))
        assertEquals("http://proxy.example:8080", request.getOption("--proxy"))
        assertFalse(request.hasOption("--no-check-certificates"))
        assertFalse(request.hasOption("--legacy-server-connect"))
        assertFalse(request.hasOption("--prefer-insecure"))
    }

    @Test fun aRequestWithDisabledTlsVerificationIsRejected() {
        val request = YoutubeDLRequest(source).addOption("--no-check-certificates")
        assertThrows(IOException::class.java) { NativeExtractorNetworkPolicy.applyOptions(request, "") }
    }

    @Test fun onlySystemStoreEntriesAreExported() {
        val bundle = NativeExtractorNetworkPolicy.systemTrustBundle(listOf(
            NativeSystemCaEntry("system:trusted-root", systemRoot),
            NativeSystemCaEntry("user:user-root", userRoot),
            NativeSystemCaEntry("unclassified-root", userRoot)
        ))
        val exported = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bundle.pem))
        assertEquals(1, exported.size)
        assertArrayEquals(systemRoot.encoded, exported.single().encoded)
        assertEquals(listOf("system:trusted-root"), bundle.roots.single().aliases)
    }

    @Test fun anEmptySystemStoreDoesNotFallBackToOtherRoots() {
        assertThrows(IOException::class.java) {
            NativeExtractorNetworkPolicy.systemTrustBundle(listOf(NativeSystemCaEntry("user:user-root", userRoot)))
        }
    }

    @Test fun exportedSystemTrustRejectsAnUntrustedUserRoot() {
        val bundle = NativeExtractorNetworkPolicy.systemTrustBundle(listOf(
            NativeSystemCaEntry("system:trusted-root", systemRoot), NativeSystemCaEntry("user:user-root", userRoot)
        ))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bundle.pem))
            .forEachIndexed { index, certificate -> store.setCertificateEntry("root-$index", certificate) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()

        manager.checkServerTrusted(arrayOf(systemRoot), "RSA")
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(arrayOf(userRoot), "RSA") }
    }

    @Test fun certificateOrderAndDuplicateSystemAliasesDoNotChangeTheBundle() {
        val one = NativeSystemCaEntry("system:a", systemRoot)
        val duplicate = NativeSystemCaEntry("system:b", systemRoot)
        val bundle = NativeExtractorNetworkPolicy.systemTrustBundle(listOf(one, duplicate))
        val reordered = NativeExtractorNetworkPolicy.systemTrustBundle(listOf(duplicate, one))
        assertArrayEquals(bundle.pem, reordered.pem)
        assertEquals(1, bundle.roots.size)
        assertEquals(listOf("system:a", "system:b"), bundle.roots.single().aliases)
    }

    @Test fun anInheritedReadableOpenSslCaDirectoryCannotExpandSystemTrust() {
        assertThrows(IOException::class.java) {
            NativeExtractorNetworkPolicy.requireNoExtraCaDirectories("/custom/roots", "/unreadable/default") { it == "/custom/roots" }
        }
    }

    @Test fun allInheritedOpenSslCaDirectoryComponentsAreChecked() {
        assertThrows(IOException::class.java) {
            NativeExtractorNetworkPolicy.requireNoExtraCaDirectories("/missing:/custom/roots", "/unreadable/default") { it == "/custom/roots" }
        }
    }

    @Test fun anUnexpectedReadableCompiledOpenSslDirectoryIsRejected() {
        assertThrows(IOException::class.java) {
            NativeExtractorNetworkPolicy.requireNoExtraCaDirectories(null, "/unexpected/default") { it == "/unexpected/default" }
        }
    }

    @Test fun inaccessibleExtraDirectoriesDoNotAddTrust() {
        NativeExtractorNetworkPolicy.requireNoExtraCaDirectories(null, "/another/apps/private/roots") { false }
    }

    private fun certificate(name: String): X509Certificate = javaClass.getResourceAsStream(name).use {
        CertificateFactory.getInstance("X.509").generateCertificate(requireNotNull(it)) as X509Certificate
    }

    private fun selector(vararg routes: Proxy) = object : ProxySelector() {
        override fun select(uri: URI): MutableList<Proxy> = routes.toMutableList()
        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
    }
}
