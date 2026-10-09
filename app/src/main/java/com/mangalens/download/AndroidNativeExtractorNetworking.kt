package com.mangalens.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.ProxySelector
import java.security.KeyStore
import java.security.cert.X509Certificate

internal data class NativeExtractorNetworkSettings(
    val proxyArgument: String,
    val caFile: File,
    val caBundleSha256: String,
    val systemRoots: List<NativeTrustRoot>
)

/** Bridges Android's configured route and system-only trust to the stock native executor. */
internal object AndroidNativeExtractorNetworking {
    // Verified in both ABI libcrypto.so.3 binaries shipped by the pinned wrapper 0.18.1.
    private const val COMPILED_CA_DIRECTORY = "/data/data/com.termux/files/usr/etc/tls/certs"
    private const val COMPILED_SSL_CONFIGURATION = "/data/data/com.termux/files/usr/etc/tls/openssl.cnf"

    fun configure(
        context: Context,
        request: YoutubeDLRequest,
        sourceUrl: String,
        checkActive: () -> Unit = {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Native networking cancelled")
        }
    ): NativeExtractorNetworkSettings {
        val app = context.applicationContext
        BundledYtDlpRuntime.initialize(app, checkActive)
        val proxy = NativeExtractorNetworkPolicy.proxyArgument(sourceUrl, ProxySelector.getDefault())
        synchronized(YoutubeDL) {
            checkActive()
            NativeExtractorNetworkPolicy.requireNoExtraCaDirectories(
                System.getenv("SSL_CERT_DIR"), COMPILED_CA_DIRECTORY
            ) { path ->
                val directory = File(path)
                // Search permission alone can permit OpenSSL to open known hashed filenames.
                directory.isDirectory && (directory.canRead() || directory.canExecute())
            }
            NativeExtractorNetworkPolicy.requireNoExtraCaConfiguration(
                System.getenv("OPENSSL_CONF"), COMPILED_SSL_CONFIGURATION
            ) { path -> File(path).let { it.isFile && it.canRead() } }
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            val entries = mutableListOf<NativeSystemCaEntry>()
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                checkActive()
                val alias = aliases.nextElement()
                if (!alias.startsWith("system:")) continue
                val certificate = store.getCertificate(alias) as? X509Certificate ?: continue
                entries += NativeSystemCaEntry(alias, certificate)
            }
            val bundle = NativeExtractorNetworkPolicy.systemTrustBundle(entries)
            checkActive()
            // execute() in wrapper 0.18.1 sets SSL_CERT_FILE to this exact initialized path.
            // We replace only its CA data, without reflection or global environment changes.
            val caFile = File(File(app.noBackupFilesDir, YoutubeDL.baseName), "packages/python/usr/etc/tls/cert.pem")
            NativeSystemTrustBundleStore.install(caFile, bundle.pem, checkActive)
            NativeExtractorNetworkPolicy.applyOptions(request, proxy)
            checkActive()
            return NativeExtractorNetworkSettings(proxy, caFile, NativeExtractorNetworkPolicy.sha256(bundle.pem), bundle.roots)
        }
    }
}
