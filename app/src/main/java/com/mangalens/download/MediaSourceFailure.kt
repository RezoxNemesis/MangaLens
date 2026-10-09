package com.mangalens.download

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

enum class MediaSourceFailureKind {
    PROVIDER_VERIFICATION_REQUIRED, PROVIDER_AUDIENCE_RESTRICTED, PROVIDER_LOGIN_REQUIRED,
    PROVIDER_UNAVAILABLE, NETWORK_PROXY_BLOCKED, NETWORK_TLS_FAILURE, NETWORK_CONNECTION_FAILED,
    TIMEOUT, SOURCE_ACCESS_DENIED, EXTRACTOR_FAILURE
}

/** Only fixed, actionable text leaves the native error boundary; raw diagnostics stay in causes. */
data class MediaSourceFailure(val kind: MediaSourceFailureKind, val message: String) {
    companion object {
        fun from(failure: Throwable): MediaSourceFailure {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val chain = mutableListOf<Throwable>()
            var current: Throwable? = failure
            while (current != null && chain.size < 16 && seen.add(current)) {
                if (current is CancellationException || current is InterruptedException) throw current
                chain += current
                current = current.cause
            }
            chain.filterIsInstance<MediaSourceException>().firstOrNull()?.let { return it.failure }
            return chain.map(::classifyOne).maxByOrNull { priority(it) }?.let(::forKind)
                ?: forKind(MediaSourceFailureKind.EXTRACTOR_FAILURE)
        }

        internal fun fromFailures(failures: List<Throwable>): MediaSourceFailure = failures.map(::from)
            .maxByOrNull { priority(it.kind) } ?: forKind(MediaSourceFailureKind.EXTRACTOR_FAILURE)

        private fun classifyOne(failure: Throwable): MediaSourceFailureKind {
            val text = failure.message.orEmpty().take(32_768).lowercase(Locale.ROOT)
                .replace('’', '\'').replace('‘', '\'')
            return when {
                text.contains("sign in to confirm you're not a bot") ||
                    text.contains("confirm you are not a bot") ||
                    text.contains("captcha required") -> MediaSourceFailureKind.PROVIDER_VERIFICATION_REQUIRED
                text.contains("content isn't available to everyone") ||
                    text.contains("can't be seen by certain audiences") -> MediaSourceFailureKind.PROVIDER_AUDIENCE_RESTRICTED
                text.contains("login required") || text.contains("log in to access") ||
                    text.contains("sign in to access") || text.contains("requires authentication") ||
                    text.contains("this video is private") -> MediaSourceFailureKind.PROVIDER_LOGIN_REQUIRED
                text.contains("video unavailable") || text.contains("video has been removed") ||
                    text.contains("this content is no longer available") -> MediaSourceFailureKind.PROVIDER_UNAVAILABLE
                text.contains("tunnel connection failed: 403") ||
                    text.contains("unexpected response code for connect: 403") ||
                    text.contains("proxy authentication required") -> MediaSourceFailureKind.NETWORK_PROXY_BLOCKED
                failure is SSLException || failure is CertificateException ||
                    text.contains("certificate_verify_failed") || text.contains("certificate verify failed") ||
                    text.contains("unable to get local issuer certificate") -> MediaSourceFailureKind.NETWORK_TLS_FAILURE
                failure is SocketTimeoutException || failure.javaClass.simpleName == "MediaResolutionTimeoutException" ||
                    text.contains("connection timed out") || text.contains("read timed out") -> MediaSourceFailureKind.TIMEOUT
                failure is ConnectException || failure is UnknownHostException ||
                    text.contains("network is unreachable") || text.contains("connection refused") ||
                    text.contains("temporary failure in name resolution") -> MediaSourceFailureKind.NETWORK_CONNECTION_FAILED
                text.contains("http error 403") || text.contains("http error 401") -> MediaSourceFailureKind.SOURCE_ACCESS_DENIED
                else -> MediaSourceFailureKind.EXTRACTOR_FAILURE
            }
        }

        // An explicit provider reason remains useful if a subsequent compatibility client fails.
        private fun priority(kind: MediaSourceFailureKind): Int = when (kind) {
            MediaSourceFailureKind.PROVIDER_VERIFICATION_REQUIRED,
            MediaSourceFailureKind.PROVIDER_AUDIENCE_RESTRICTED,
            MediaSourceFailureKind.PROVIDER_LOGIN_REQUIRED -> 100
            MediaSourceFailureKind.PROVIDER_UNAVAILABLE -> 90
            MediaSourceFailureKind.NETWORK_PROXY_BLOCKED,
            MediaSourceFailureKind.NETWORK_TLS_FAILURE -> 80
            MediaSourceFailureKind.TIMEOUT, MediaSourceFailureKind.NETWORK_CONNECTION_FAILED -> 70
            MediaSourceFailureKind.SOURCE_ACCESS_DENIED -> 60
            MediaSourceFailureKind.EXTRACTOR_FAILURE -> 0
        }

        private fun forKind(kind: MediaSourceFailureKind) = MediaSourceFailure(kind, when (kind) {
            MediaSourceFailureKind.PROVIDER_VERIFICATION_REQUIRED ->
                "The source requires a verification check before this video is accessible. Open the source page and retry if the provider grants access."
            MediaSourceFailureKind.PROVIDER_AUDIENCE_RESTRICTED ->
                "The provider limits this video to certain audiences. Open the source page to check your access."
            MediaSourceFailureKind.PROVIDER_LOGIN_REQUIRED ->
                "The source requires sign-in. Open the source page using your own session, then retry if the provider grants access."
            MediaSourceFailureKind.PROVIDER_UNAVAILABLE ->
                "The provider reports that this video is unavailable or removed. Open the source page to confirm it is still accessible."
            MediaSourceFailureKind.NETWORK_PROXY_BLOCKED ->
                "The network proxy rejected the connection to the source. Check the network, then retry or open the source page."
            MediaSourceFailureKind.NETWORK_TLS_FAILURE ->
                "The source's secure connection could not be verified with Android's system certificate roots. Check the device network and trusted system certificates, then retry."
            MediaSourceFailureKind.NETWORK_CONNECTION_FAILED ->
                "The device could not connect to the source. Check your connection, then retry or open the source page."
            MediaSourceFailureKind.TIMEOUT ->
                "Media resolution timed out. Retry or open the source page."
            MediaSourceFailureKind.SOURCE_ACCESS_DENIED ->
                "The source rejected access to its media. Open the source page to check your access, or retry when the source is available."
            MediaSourceFailureKind.EXTRACTOR_FAILURE ->
                "The installed extractor could not resolve an accessible video. Retry or open the source page; MangaLens may need an app update if the site's player has changed."
        })
    }
}

class MediaSourceException(val failure: MediaSourceFailure, cause: Throwable?) : IOException(failure.message, cause) {
    companion object {
        fun fromFailures(failures: List<Throwable>) = MediaSourceException(
            MediaSourceFailure.fromFailures(failures), failures.lastOrNull()
        )
    }
}
