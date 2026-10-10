package com.mangalens.orez.agent

import com.mangalens.orez.research.OrezResearchPublicNetworkPolicy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One real producer owns its body/call until execute, consumption and close have actually returned. */
internal class OrezChapterAcquisitionNetwork(private val owner: OrezAcquisitionPrivateOwner) {
    private data class DocumentOwner(val url: okhttp3.HttpUrl)
    private var transferred = 0L
    private val maximumTransfer = OrezNextChapterPolicy.MAX_CHAPTER_BYTES + OrezNextChapterPolicy.MAX_HTML_BYTES * 6
    val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .dns(OrezResearchPublicNetworkPolicy.guardedDns()).proxySelector(OrezResearchPublicNetworkPolicy.directOnlyProxy())
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(45, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            var request = chain.request()
            val document = request.tag(DocumentOwner::class.java)
            var redirects = 0
            while (true) {
                OrezNextChapterPolicy.publicUrl(request.url.toString())
                document?.let { require(OrezNextChapterPolicy.sameOrigin(it.url, request.url)) { "A chapter document redirected outside its captured origin." } }
                request = request.newBuilder().removeHeader("Cookie").removeHeader("Authorization").removeHeader("Proxy-Authorization")
                    .removeHeader("Referer").header("User-Agent", "MangaLens/1.0 (public chapter acquisition)").build()
                val response = chain.proceed(request)
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val destination = try {
                        require(redirects++ < 3) { "Chapter redirect limit exceeded." }
                        val target = response.header("Location")?.let { request.url.resolve(it) }
                            ?: error("Chapter redirect has no usable destination.")
                        OrezNextChapterPolicy.publicUrl(target.toString())
                        require(!request.url.isHttps || target.isHttps) { "Secure chapter acquisition cannot redirect to HTTP." }
                        document?.let { require(OrezNextChapterPolicy.sameOrigin(it.url, target)) { "A chapter document redirected outside its captured origin." } }
                        target
                    } finally { closeResponse(response) }
                    request = request.newBuilder().url(destination).build()
                    continue
                }
                val body = response.body ?: return@addInterceptor response
                try {
                val limited = object : ResponseBody() {
                    private val source = object : ForwardingSource(body.source()) {
                        override fun read(sink: okio.Buffer, byteCount: Long): Long {
                            val read = super.read(sink, minOf(byteCount, 64L * 1024))
                            if (read > 0) synchronized(this@OrezChapterAcquisitionNetwork) {
                                if (read > maximumTransfer - transferred) throw IOException("Native chapter transfer exceeds its aggregate byte limit.")
                                transferred += read
                            }
                            return read
                        }
                    }.buffer()
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source(): BufferedSource = source
                }
                return@addInterceptor response.newBuilder().body(limited).build()
                } catch (failure: Throwable) { closeResponse(response); throw failure }
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable redirect loop")
        }.build()

    private fun closeResponse(response: okhttp3.Response) { response.body?.let { owner.usePrivate(it.source()) { } } }
    data class Document(val url: String, val html: String, val sha256: String)
    suspend fun readHtml(url: String): Document = coroutineScope {
        val address = OrezNextChapterPolicy.publicUrl(url)
        val context = currentCoroutineContext(); context.ensureActive()
        val call = client.newCall(Request.Builder().url(address).tag(DocumentOwner::class.java, DocumentOwner(address))
            .header("Accept", "text/html,application/xhtml+xml").build())
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            val response = call.execute()
            val body = requireNotNull(response.body) { "Chapter source returned no document." }
            owner.usePrivate(body.source()) { source ->
                context.ensureActive()
                require(response.isSuccessful) { "Public chapter source returned HTTP ${response.code}. Inspect it in Reader." }
                require(body.contentType()?.let { it.type == "text" && it.subtype == "html" || it.type == "application" && it.subtype == "xhtml+xml" } == true) {
                    "The chapter source did not return a public HTML document."
                }
                require(body.contentLength() <= OrezNextChapterPolicy.MAX_HTML_BYTES) { "Chapter source exceeds the native document limit." }
                val output = ByteArrayOutputStream()
                val input = source.inputStream()
                run {
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer, 0, minOf(buffer.size, OrezNextChapterPolicy.MAX_HTML_BYTES.toInt() + 1 - output.size()))
                        if (read < 0) break
                        require(output.size() + read <= OrezNextChapterPolicy.MAX_HTML_BYTES) { "Chapter source exceeds the native document limit." }
                        output.write(buffer, 0, read)
                    }
                }
                context.ensureActive()
                val bytes = output.toByteArray()
                Document(response.request.url.toString(), bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8), OrezNextChapterPolicy.sha(bytes))
            }
        } catch (failure: Exception) { context.ensureActive(); throw failure }
        finally { cancellation.cancel() }
    }
}
