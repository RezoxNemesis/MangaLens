package com.mangalens.download

import com.mangalens.orez.research.OrezResearchPublicNetworkPolicy
import com.mangalens.ui.video.MediaRequestContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated public HLS route; generic download/network/native ownership is unchanged. */
internal object OriginalHlsPublicTransport {
    private val lock = Any()
    private val claims = Semaphore(4, true)
    // A failed close retains the actual response and its unreleased claim until process restart.
    private val failedResponses = ArrayList<Response>()

    fun requireUrl(value: String): HttpUrl {
        require(OriginalHlsVodPolicy.safeUrl(value))
        val url = value.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && '%' !in url.host &&
            url.port == if (url.isHttps) 443 else 80) { "HLS supports public HTTP(S) default-port sources." }
        if (':' in url.host || url.host.matches(Regex("[0-9.]+")))
            require(OrezResearchPublicNetworkPolicy.isPublic(InetAddress.getByName(url.host))) { "HLS refuses a private source address." }
        return url
    }

    fun client(anchor: String, page: String?, headers: Map<String, String>, browserCookie: (String) -> String?): OkHttpClient {
        return client(anchor, MediaRequestContext(anchor, page, headers.toMap()), browserCookie)
    }

    fun client(anchor: String, context: MediaRequestContext, browserCookie: (String) -> String?): OkHttpClient {
        requireUrl(anchor)
        return OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .dns(OrezResearchPublicNetworkPolicy.guardedDns())
            .proxySelector(OrezResearchPublicNetworkPolicy.directOnlyProxy())
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS)
            .addInterceptor(Interceptor { chain ->
                var request = chain.request()
                var delivered: Response? = null
                for (hop in 0..5) {
                    requireUrl(request.url.toString())
                    synchronized(lock) {
                        if (failedResponses.isNotEmpty()) throw MediaSourceException(MediaSourceFailure.from(closeFailure()), null)
                        if (!claims.tryAcquire()) throw unavailable("Other HLS requests are still returning. Retry shortly.")
                    }
                    val response = try { chain.proceed(request) }
                    catch (failure: Throwable) { claims.release(); throw failure }
                    val owned = own(response)
                    if (owned.code !in setOf(301, 302, 303, 307, 308)) { delivered = owned; break }
                    try {
                        require(hop < 5) { "HLS redirect limit exceeded." }
                        val next = owned.header("Location")?.let { request.url.resolve(it) }
                            ?: error("HLS redirect has no valid target.")
                        requireUrl(next.toString()); require(!request.url.isHttps || next.isHttps) { "HLS redirect lowered source security." }
                        request = request.newBuilder().url(next).build()
                    } finally { owned.close() }
                }
                requireNotNull(delivered)
            })
            .addNetworkInterceptor(Interceptor { chain ->
                requireUrl(chain.request().url.toString())
                val connection = requireNotNull(chain.connection())
                require(connection.route().proxy.type() == Proxy.Type.DIRECT &&
                    OrezResearchPublicNetworkPolicy.isPublic(connection.socket().inetAddress)) { "HLS connection is not a verified public direct route." }
                chain.proceed(chain.request())
            })
            .addNetworkInterceptor(scopedDownloadHeaders(context, browserCookie))
            .build()
    }

    private fun own(response: Response): Response {
        val body = response.body
        if (body == null) {
            // No returned network source can acknowledge release; retain the response conservatively.
            synchronized(lock) { failedResponses += response }
            throw closeFailure()
        }
        val closed = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        try {
            val source = object : ForwardingSource(body.source()) {
                override fun close() {
                    if (!closed.compareAndSet(false, true)) {
                        if (failed.get()) throw closeFailure()
                        return
                    }
                    try { super.close(); claims.release() }
                    catch (failure: Throwable) {
                        failed.set(true)
                        synchronized(lock) { failedResponses += response }
                        // ResponseBody.closeQuietly can swallow IOException; a fixed runtime failure
                        // makes failed release observable even through an ordinary Response.use.
                        throw closeFailure(failure)
                    }
                }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (construction: Throwable) {
            // Construction has not delivered an owner. Observe the real source close directly.
            try { body.source().close(); claims.release() }
            catch (failure: Throwable) {
                synchronized(lock) { failedResponses += response }
                construction.addSuppressed(closeFailure(failure))
            }
            throw construction
        }
    }

    private fun closeFailure(cause: Throwable? = null) = OriginalHlsUnprovenReleaseException(cause)

    private fun unavailable(message: String) = MediaSourceException(MediaSourceFailure(MediaSourceFailureKind.UNSUPPORTED_TRANSPORT, message), null)
}

/** Typed local close failure; raw source diagnostics remain private causes. */
internal class OriginalHlsUnprovenReleaseException(cause: Throwable? = null) : IllegalStateException(
    "An HLS response did not close. Restart MangaLens before another HLS request; existing partial files were retained.", cause)
