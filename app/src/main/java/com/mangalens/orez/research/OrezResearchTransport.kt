package com.mangalens.orez.research

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal class ResearchOperationBudget {
    var requests = 0; private set
    var redirects = 0; private set
    var capturedBytes = 0; private set
    fun request() { require(++requests <= 10) { "Research request limit reached." } }
    fun redirect() { require(++redirects <= 5) { "Research redirect limit reached." } }
    fun allowance() = minOf(1_500_000, 6_000_000 - capturedBytes).also { require(it > 0) { "Research evidence limit reached." } }
    fun capture(bytes: Int) { require(bytes in 0..allowance()); capturedBytes += bytes }
}
internal data class ResearchCapturedDocument(val capture: ResearchHttpCapture, val text: String)
internal fun interface ResearchHttpTransport {
    suspend fun get(url: String, budget: ResearchOperationBudget, isExecuting: suspend () -> Boolean): ResearchCapturedDocument
}

/** A test-only trusted client may route fixture names locally; no caller/tool supplies headers or routes. */
internal class OrezResearchTransport private constructor(private val client: OkHttpClient, private val clock: () -> Long) : ResearchHttpTransport {
    override suspend fun get(url: String, budget: ResearchOperationBudget, isExecuting: suspend () -> Boolean): ResearchCapturedDocument = withContext(Dispatchers.IO) {
        val first = url.toHttpUrl(); OrezResearchPublicNetworkPolicy.requireUrl(first)
        var current = first
        val hops = ArrayList<ResearchHttpHop>()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isExecuting()) throw CancellationException("Research task retired.")
            OrezResearchPublicNetworkPolicy.requireUrl(current); budget.request()
            val call = client.newCall(Request.Builder().url(current).header("User-Agent", "MangaLens/1.4 public research")
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain").header("Accept-Encoding", "identity").build())
            val captured = coroutineScope {
                val context = currentCoroutineContext()
                val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { while (isActive) { if (!isExecuting()) { call.cancel(); return@launch }; delay(50) } }
                    finally { call.cancel() }
                }
                try { call.execute().use { response ->
                    context.ensureActive(); if (!isExecuting()) throw CancellationException("Research task retired.")
                    hops += ResearchHttpHop(current.toString(), response.code)
                    val location = response.header("Location")
                    if (response.code in setOf(301, 302, 303, 307, 308) && location != null) {
                        val target = current.resolve(location) ?: error("Invalid research redirect.")
                        require(!current.isHttps || target.isHttps) { "Research refuses an HTTPS downgrade." }
                        OrezResearchPublicNetworkPolicy.requireUrl(target); budget.redirect()
                        Pair(target, null)
                    } else {
                        require(response.header("Content-Encoding").orEmpty().let { it.isBlank() || it.equals("identity", true) }) { "Research source used unsupported compression." }
                        val body = response.body
                        val allowed = budget.allowance()
                        val output = ByteArrayOutputStream(minOf(allowed, 64 * 1024)); var truncated = false
                        body?.byteStream()?.use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (output.size() < allowed) {
                                context.ensureActive(); if (!isExecuting()) throw CancellationException("Research task retired.")
                                val count = input.read(buffer, 0, minOf(buffer.size, allowed - output.size()))
                                if (count < 0) break
                                if (count > 0) output.write(buffer, 0, count)
                            }
                            context.ensureActive(); if (!isExecuting()) throw CancellationException("Research task retired.")
                            if (output.size() == allowed) truncated = input.read() >= 0
                        }
                        val bytes = output.toByteArray(); budget.capture(bytes.size)
                        val mime = body?.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty().take(80)
                        val charset = body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                        val capture = ResearchHttpCapture(first.toString(), current.toString(), hops.toList(), response.code, mime,
                            clock(), bytes.size, researchSha256(bytes), truncated, response.header("Date").orEmpty().take(128), response.header("Last-Modified").orEmpty().take(128), textCharset = charset.name())
                        Pair(null, ResearchCapturedDocument(capture, bytes.toString(charset)))
                    }
                } } catch (failure: java.io.IOException) {
                    context.ensureActive(); if (!isExecuting()) throw CancellationException("Research task retired.")
                    throw failure
                } finally { watcher.cancel() }
            }
            captured.second?.let { return@withContext it }
            current = requireNotNull(captured.first)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable research transport state.")
    }
    companion object {
        fun production(clock: () -> Long = System::currentTimeMillis): OrezResearchTransport = OrezResearchTransport(OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(14, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .dns(OrezResearchPublicNetworkPolicy.guardedDns()).proxySelector(OrezResearchPublicNetworkPolicy.directOnlyProxy()).build(), clock)
        /** Internal fixture construction is never exposed to a model, page, app setting, or production host. */
        internal fun fixture(client: OkHttpClient, clock: () -> Long): OrezResearchTransport = OrezResearchTransport(client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).build(), clock)
    }
}
