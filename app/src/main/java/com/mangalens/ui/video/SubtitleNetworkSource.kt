package com.mangalens.ui.video

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class SubtitleNetworkValidator(val etag: String, val size: Long, val url: String) {
    val fingerprint: String get() = "$etag|$size|${url.length}:$url"
}
internal fun isStrongSubtitleEtag(value: String): Boolean = value.length in 2..1024 && value.startsWith('"') && value.endsWith('"') &&
    value.substring(1, value.lastIndex).none { it == '"' || it.code <= 32 || it.code == 127 }
internal class SubtitleNetworkChanged(message: String) : IOException(message)
internal class SubtitleNetworkUnverified(message: String) : IOException(message)

/** Network interception scopes captured headers again for every redirected connection. */
internal fun scopedSubtitleClient(source: SubtitleMediaSource, base: OkHttpClient,
    expectedEtag: String? = null, expectedUrl: String? = null): OkHttpClient {
    val context = MediaRequestContext(source.uri, headers = source.headers)
    return base.newBuilder().followRedirects(true).followSslRedirects(false).addNetworkInterceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder()
        val protocol = setOf("host", "connection", "accept-encoding", "range", "if-match")
        request.headers.names().filter { it.lowercase(java.util.Locale.ROOT) !in protocol }.forEach(builder::removeHeader)
        // A strong validator belongs to its final resource. A redirect alias can
        // have another ETag and must never receive the media representation's tag.
        builder.removeHeader("If-Match")
        if (expectedEtag != null && request.url.toString() == expectedUrl) builder.header("If-Match", expectedEtag)
        context.headersFor(request.url.toString(), null).forEach { (name, value) -> builder.header(name, value) }
        chain.proceed(builder.build())
    }.build()
}

/** One bounded range cache lets MediaExtractor seek without delegating redirects to native HTTP. */
internal class SubtitleNetworkSource(requested: SubtitleMediaSource, baseClient: OkHttpClient = client,
    private val expectedEtag: String? = null, private val expectedSize: Long? = null, private val expectedUrl: String? = null) : AutoCloseable {
    private val source = requested.captureSnapshot()
    private val transport = scopedSubtitleClient(source, baseClient, expectedEtag,
        expectedUrl ?: Request.Builder().url(source.uri).build().url.toString())
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<Call?>(null)
    private var knownSize = expectedSize ?: -1L
    private var cachePosition = 0L
    private var cache = ByteArray(0)

    @Synchronized fun validator(): String? = proof()?.let { "${it.etag}|${it.size}" }
    /** Identity comes from the representation read by GET, never a potentially stale HEAD cache. */
    @Synchronized fun proof(): SubtitleNetworkValidator? = execute(request().header("Range", "bytes=0-0").build()) { response ->
        if (!response.isSuccessful) throw IOException("Video source returned HTTP ${response.code} during verification.")
        val total = if (response.code == 206) {
            val range = Regex("bytes 0-0/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                ?: throw IOException("Video identity probe returned an invalid byte range.")
            range.groupValues[1].toLongOrNull()
        } else if (response.code == 200) response.body?.contentLength() else null
        val etag = response.header("ETag")?.takeIf(::isStrongSubtitleEtag)
        val first = response.body?.byteStream()?.read() ?: -1
        if (first < 0) throw IOException("Video identity probe returned an empty source.")
        if (etag != null && total != null && total > 0) SubtitleNetworkValidator(etag, total, response.request.url.toString()) else null
    }
    @Synchronized fun size(): Long {
        if (knownSize >= 0) return knownSize
        runCatching { execute(request().head().build()) { response ->
            if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }?.let { knownSize = it }
        } }
        if (knownSize < 0) fetch(0)
        return knownSize
    }
    @Synchronized fun readAt(position: Long, data: ByteArray, offset: Int, size: Int): Int {
        require(position in 0..Long.MAX_VALUE - CACHE_BYTES && offset >= 0 && size >= 0 && offset <= data.size - size)
        if (size == 0) return 0
        if (closed.get()) throw IOException("Subtitle video reader is closed.")
        if (knownSize >= 0 && position >= knownSize) return -1
        if (position < cachePosition || position >= cachePosition + cache.size) fetch(position)
        val start = (position - cachePosition).toInt()
        val count = minOf(size, cache.size - start)
        if (count <= 0) return -1
        cache.copyInto(data, offset, start, start + count)
        return count
    }
    private fun fetch(position: Long) {
        if (closed.get()) throw IOException("Subtitle video reader is closed.")
        val last = position + CACHE_BYTES - 1L
        execute(request().header("Range", "bytes=$position-$last").build()) { response ->
            if (expectedUrl != null && response.request.url.toString() != expectedUrl)
                throw SubtitleNetworkChanged("Video redirected to a different resource during generation.")
            if (response.code == 412 && expectedEtag != null) throw SubtitleNetworkChanged("Video bytes no longer match their captured version.")
            if (response.code == 416) {
                val total = response.header("Content-Range")?.substringAfter("*/")?.toLongOrNull()
                if (expectedSize != null && total != null && total != expectedSize)
                    throw SubtitleNetworkChanged("Video byte length changed at its reported end.")
                if (total != null && position >= total) { knownSize = total; cachePosition = position; cache = ByteArray(0); return@execute }
                throw IOException("Video range is unavailable. Refresh its source and retry.")
            }
            if (!response.isSuccessful) throw IOException("Video source returned HTTP ${response.code}. Refresh its source and retry.")
            if (expectedEtag != null) {
                val actualEtag = response.header("ETag")?.takeIf(::isStrongSubtitleEtag)
                    ?: throw SubtitleNetworkUnverified("Video byte ranges have no stable version. Completed windows have been kept; retry to verify them.")
                if (actualEtag != expectedEtag)
                    throw SubtitleNetworkChanged("Video bytes no longer match their captured version. Refresh the source and generate a new task.")
            }
            var expectedBytes: Int? = null
            if (response.code == 206) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw IOException("Video range response is invalid.")
                if (range.groupValues[1].toLongOrNull() != position) throw IOException("Video range response starts at another position.")
                val end = range.groupValues[2].toLongOrNull() ?: throw IOException("Video range response end is invalid.")
                if (end !in position..last) throw IOException("Video range response exceeds its bounded request.")
                expectedBytes = (end - position + 1).toInt()
                range.groupValues[3].toLongOrNull()?.let {
                    if (it <= end) throw IOException("Video range length is invalid.")
                    if (expectedSize != null && it != expectedSize) throw SubtitleNetworkChanged("Video byte length changed during generation.")
                    knownSize = it
                }
            } else {
                if (position != 0L) throw IOException("This online video does not support bounded seeking. Save a local copy and retry subtitles.")
                response.body?.contentLength()?.takeIf { it >= 0 }?.let {
                    if (expectedSize != null && it != expectedSize) throw SubtitleNetworkChanged("Video byte length changed during generation.")
                    knownSize = it
                }
            }
            val body = response.body ?: throw IOException("Video source returned no audio data.")
            val bytes = ByteArray(CACHE_BYTES)
            var used = 0
            body.byteStream().use { input -> while (used < bytes.size) {
                if (closed.get()) throw IOException("Subtitle video reader is closed.")
                val count = input.read(bytes, used, bytes.size - used)
                if (count < 0) break
                if (count == 0) continue
                used += count
            } }
            if (expectedBytes != null && used != expectedBytes) throw IOException("Video range was truncated or changed. Refresh its source and retry.")
            cachePosition = position
            cache = bytes.copyOf(used)
            if (knownSize < 0 && used < CACHE_BYTES) knownSize = position + used
        }
    }
    private fun request() = Request.Builder().url(source.uri).header("Accept-Encoding", "identity")
    private fun <T> execute(request: Request, action: (Response) -> T): T {
        if (closed.get()) throw IOException("Subtitle video reader is closed.")
        val call = transport.newCall(request)
        active.set(call)
        if (closed.get()) call.cancel()
        try { return call.execute().use(action) } finally { active.compareAndSet(call, null) }
    }
    override fun close() { closed.set(true); active.get()?.cancel() }
    companion object {
        private const val CACHE_BYTES = 512 * 1024
        private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()
    }
}
