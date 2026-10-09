package com.mangalens.download

import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

internal data class NativeSystemCaEntry(val alias: String, val certificate: X509Certificate)
internal data class NativeTrustRoot(val aliases: List<String>, val certificateSha256: String)
internal data class NativeSystemTrustBundle(val pem: ByteArray, val roots: List<NativeTrustRoot>)

/** Android's selected route and system trust are transferred without weakening native TLS. */
internal object NativeExtractorNetworkPolicy {
    fun proxyArgument(source: String, selector: ProxySelector?): String {
        val uri = URI(source)
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw IOException("Native networking requires an HTTP(S) source without credentials")
        }
        val route = selector?.select(uri)?.firstOrNull() ?: Proxy.NO_PROXY
        if (route.type() == Proxy.Type.DIRECT) return ""
        val address = route.address() as? InetSocketAddress
            ?: throw IOException("The Android proxy has an unsupported address")
        val scheme = when (route.type()) {
            Proxy.Type.HTTP -> "http"
            Proxy.Type.SOCKS -> "socks5"
            else -> throw IOException("The Android proxy has an unsupported protocol")
        }
        // hostString preserves an unresolved configured name; hostName could perform DNS.
        return URI(scheme, null, address.hostString, address.port, null, null, null).toASCIIString()
    }

    fun applyOptions(request: YoutubeDLRequest, proxy: String) {
        if (listOf("--no-check-certificates", "--legacy-server-connect", "--prefer-insecure", "--prefer-unsecure")
                .any(request::hasOption)) {
            throw IOException("Native extraction must retain certificate and hostname verification")
        }
        // Explicit DIRECT also prevents inherited Python proxy variables overriding Android.
        // YoutubeDLOptions drops empty arguments. addCommands is the library's raw argv API
        // and preserves the empty string required for an explicit DIRECT connection.
        if (proxy.isEmpty()) request.addCommands(listOf("--proxy", ""))
        else request.addOption("--proxy", proxy)
        // Stock yt-dlp then loads SSL_CERT_FILE, which the library sets to its private CA file.
        // This selects the exported OS roots; it does not change CERT_REQUIRED/check_hostname.
        request.addOption("--compat-options", "no-certifi")
    }

    fun systemTrustBundle(entries: List<NativeSystemCaEntry>): NativeSystemTrustBundle {
        val systemEntries = entries.filter { it.alias.startsWith("system:") && it.alias.length > "system:".length }
        if (systemEntries.isEmpty()) throw IOException("Android has no accessible system certificate roots")
        val roots = systemEntries.groupBy { sha256(it.certificate.encoded) }.toSortedMap()
        val pem = buildString {
            for (group in roots.values) {
                append("-----BEGIN CERTIFICATE-----\n")
                append(Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(group.first().certificate.encoded))
                append("\n-----END CERTIFICATE-----\n")
            }
        }.toByteArray(StandardCharsets.US_ASCII)
        return NativeSystemTrustBundle(pem, roots.map { (hash, group) ->
            NativeTrustRoot(group.map { it.alias }.distinct().sorted(), hash)
        })
    }

    fun requireNoExtraCaDirectories(inherited: String?, compiled: String, readable: (String) -> Boolean) {
        // SSL_CERT_DIR, if present, shadows the compiled OpenSSL directory and can contain a
        // colon-separated list. Any accessible directory could silently add non-system roots.
        val directories = inherited?.split(':') ?: listOf(compiled)
        if (directories.filter { it.isNotEmpty() }.any(readable)) {
            throw IOException("Native TLS has an additional accessible CA directory; Android system-only trust cannot be guaranteed")
        }
    }

    fun requireNoExtraCaConfiguration(inherited: String?, compiled: String, readable: (String) -> Boolean) {
        // OpenSSL's system_default configuration can apply VerifyCAFile/Path/Store to new
        // contexts before Python loads our bundle. An empty override explicitly disables it.
        val configuration = inherited ?: compiled
        if (configuration.isNotEmpty() && readable(configuration)) {
            throw IOException("Native TLS has an additional accessible OpenSSL configuration; Android system-only trust cannot be guaranteed")
        }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
