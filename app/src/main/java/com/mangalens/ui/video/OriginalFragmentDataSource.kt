package com.mangalens.ui.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.mangalens.download.OriginalFragmentPlan
import com.mangalens.download.OriginalMediaFragment
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Raw original init/media sequence. The real encoded container supplies timestamps to Media3. */
@UnstableApi
internal class OriginalFragmentDataSource private constructor(
    private val plan: OriginalFragmentPlan,
    private val pair: String,
    private val role: String,
    private val clientFor: (OriginalMediaFragment) -> OkHttpClient,
    private val byteMap: OriginalFragmentByteMap
) : BaseDataSource(true) {
    private val requestGuard = Any()
    private var generation = 0L
    private var owner: FragmentPlaybackOwners.Owner? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()
    override fun open(dataSpec: DataSpec): Long {
        close()
        val token = synchronized(requestGuard) { ++generation }
        if (dataSpec.uri.toString() != plan.sourceUrl || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null || dataSpec.position < 0L)
            throw IOException("Fragment playback requires a bounded original media read.")
        transferInitializing(dataSpec)
        val captured = FragmentPlaybackOwners.acquire(pair, role, plan, clientFor, byteMap)
        synchronized(requestGuard) {
            if (token != generation) { captured.retire(); throw IOException("Original fragment playback was stopped.") }
            owner = captured
        }
        try {
            captured.operation { locate(dataSpec.position) }
            val total = byteMap.total()
            if (total != null && dataSpec.position > total) throw IOException("Playback position is outside the selected original bytes.")
            return synchronized(requestGuard) {
                if (token != generation || owner !== captured) throw IOException("Original fragment playback was stopped.")
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                    else total?.let { it - dataSpec.position } ?: C.LENGTH_UNSET.toLong()
                opened = true
                transferStarted(dataSpec)
                remaining
            }
        } catch (failure: Throwable) {
            synchronized(requestGuard) { if (token == generation) close() else captured.retire() }
            throw failure
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val request = synchronized(requestGuard) {
            if (!opened) throw IOException("Selected fragment playback is not open.")
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            (owner ?: throw IOException("Selected fragment playback was stopped.")) to
                if (remaining < 0L) length else minOf(length.toLong(), remaining).toInt()
        }
        val current = request.first
        val count = current.operation { read(buffer, offset, request.second) }
        return synchronized(requestGuard) {
            if (owner !== current || !opened) throw IOException("Original fragment playback was stopped.")
            if (count > 0) {
                if (remaining >= 0L) remaining -= count
                bytesTransferred(count)
            } else if (count == C.RESULT_END_OF_INPUT && remaining > 0L) {
                throw IOException("Selected original bytes ended before the requested position.")
            }
            count
        }
    }
    override fun getUri(): Uri = Uri.parse(plan.sourceUrl)
    override fun getResponseHeaders(): Map<String, List<String>> = synchronized(requestGuard) { owner?.headers() ?: emptyMap() }
    override fun close() = synchronized(requestGuard) {
        generation++
        val previous = owner; owner = null
        // Only memory retirement occurs here, including when ExoPlayer calls close from its Main path.
        previous?.retire()
        if (opened) { opened = false; transferEnded() }
    }
    class Factory(plan: OriginalFragmentPlan, private val context: MediaRequestContext,
        private val pair: String, private val role: String) : DataSource.Factory {
        private val captured = plan.captured()
        private val byteMap = OriginalFragmentByteMap(captured)
        private val client by lazy {
            if (captured.hls != null) com.mangalens.download.OriginalHlsPublicTransport.client(captured.sourceUrl, context) { actual ->
                android.webkit.CookieManager.getInstance().getCookie(actual)
            } else MediaPlaybackDataSource.scopedClient(context)
        }
        override fun createDataSource(): DataSource = OriginalFragmentDataSource(captured, pair, role,
            { _ -> client }, byteMap)
    }
}

/** No UI/Media3 callback is stored here. Actual socket operations and close return own the two slots. */
internal object FragmentPlaybackOwners {
    private val guard = Object()
    private val owners = mutableListOf<Owner>()
    private val cleanup = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "original-fragment-close").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    fun acquire(pair: String, role: String, plan: OriginalFragmentPlan,
        clientFor: (OriginalMediaFragment) -> OkHttpClient, map: OriginalFragmentByteMap): Owner {
        require(role in setOf("video", "audio"))
        // Loader-thread wait permits normal previous close to return. No third producer may enter.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        synchronized(guard) {
            while (owners.isNotEmpty() && (owners.any { it.pair != pair || it.role == role } || owners.size >= 2)) {
                if (System.nanoTime() >= deadline || owners.any { it.unproven })
                    throw IOException("An earlier original media connection has not released. Stop playback before retrying.")
                try { guard.wait(50) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt(); throw IOException("Original fragment playback was stopped.", interrupted)
                }
            }
            return Owner(pair, role, plan.captured(), clientFor, map).also { owners += it }
        }
    }
    class Owner internal constructor(val pair: String, val role: String,
        private val plan: OriginalFragmentPlan,
        private val clientFor: (OriginalMediaFragment) -> OkHttpClient,
        private val map: OriginalFragmentByteMap) {
        internal var unproven = false
        private var retired = false
        private var operations = 0
        private var scheduled = false
        private var cancelAttempted = false
        private var responseCloseAttempted = false
        private var call: Call? = null
        private var response: Response? = null
        private var input: InputStream? = null
        private var index = 0
        private var received = 0L
        private var skip = 0L
        private var announced: Long? = null
        private var prefixChecked = false
        private var prefix = ByteArray(512)
        private var prefixSize = 0
        private var prefixPosition = 0
        fun <T> operation(block: Owner.() -> T): T {
            synchronized(guard) { ensureCurrent(); operations++ }
            try { return block().also { synchronized(guard) { ensureCurrent() } } }
            finally { synchronized(guard) { operations--; if (retired) scheduleLocked() } }
        }
        private fun ensureCurrent() { if (retired || unproven) throw IOException("Original fragment playback was stopped or its connection release is unproven.") }
        fun headers(): Map<String, List<String>> = synchronized(guard) { response?.headers?.toMultimap()?.mapValues { it.value.toList() } ?: emptyMap() }
        fun retire() = synchronized(guard) { retired = true; scheduleLocked() }
        private fun scheduleLocked() {
            if (scheduled || !retired) return
            scheduled = true
            try { cleanup.execute(::clean) }
            catch (_: RuntimeException) { scheduled = false; unproven = true /* retain actual resources/slot */ }
        }
        private fun clean() {
            var failure = false
            val toCancel = synchronized(guard) {
                if (!cancelAttempted) { cancelAttempted = true; call } else null
            }
            try { toCancel?.cancel() } catch (_: Throwable) { failure = true }
            val toClose = synchronized(guard) {
                if (operations == 0 && !responseCloseAttempted) { responseCloseAttempted = true; response } else null
            }
            var closed = false
            try {
                if (toClose != null) { closeActualSource(toClose); closed = true }
            } catch (_: Throwable) { failure = true }
            synchronized(guard) {
                if (failure) unproven = true
                if (closed) { response = null; input = null }
                scheduled = false
                if (operations == 0 && !unproven && response == null) {
                    call = null; owners.remove(this); guard.notifyAll()
                } else if (operations == 0 && !unproven && !responseCloseAttempted) {
                    // The read may have returned while this cancel operation was in progress.
                    scheduleLocked()
                }
                // A still-running read schedules its final close when it actually returns.
            }
        }
        fun locate(position: Long) {
            val located = map.locate(position)
            if (located != null) { index = located.fragment; skip = located.offset; return }
            // Unknown preceding sizes are discovered from actual responses/EOF, never duration guesses.
            var rest = position
            val scratch = ByteArray(64 * 1024)
            index = 0
            while (index < plan.fragments.size) {
                val known = map.length(index)
                if (known != null && rest >= known) { rest -= known; index++; continue }
                openFragment()
                val measured = map.length(index)
                if (measured != null && rest >= measured) { rest -= measured; finishFragment(false); index++; continue }
                if (rest == 0L) return
                while (rest > 0L) {
                    val count = readCurrent(scratch, 0, minOf(rest, scratch.size.toLong()).toInt())
                    if (count < 0) { finishFragment(true); index++; break }
                    rest -= count
                }
                if (rest == 0L) return
            }
            if (rest != 0L) throw IOException("Playback position is outside the measured original bytes.")
        }
        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (index < plan.fragments.size) {
                if (response == null) openFragment()
                if (skip > 0L) {
                    val scratch = ByteArray(64 * 1024)
                    while (skip > 0L) {
                        val count = readCurrent(scratch, 0, minOf(skip, scratch.size.toLong()).toInt())
                        if (count < 0) throw IOException("Selected fragment ended before its measured seek position.")
                        skip -= count
                    }
                }
                val count = readCurrent(buffer, offset, length)
                if (count >= 0) return count
                finishFragment(true); index++
            }
            return C.RESULT_END_OF_INPUT
        }
        private fun openFragment() {
            synchronized(guard) { ensureCurrent() }
            val fragment = plan.fragments[index]
            val request = Request.Builder().url(fragment.url).header("Accept-Encoding", "identity")
                .apply { fragment.rangeStart?.let { header("Range", "bytes=$it-${fragment.rangeEndExclusive!! - 1L}") } }.build()
            val next = clientFor(fragment).newCall(request)
            synchronized(guard) { call = next; cancelAttempted = false; ensureCurrent() }
            val opened = next.execute()
            synchronized(guard) {
                response = opened; responseCloseAttempted = false
                ensureCurrent()
            }
            if (!opened.isSuccessful) throw IOException("Selected fragment returned HTTP ${opened.code}.")
            val encoding = opened.header("Content-Encoding").orEmpty()
            if (encoding.isNotBlank() && !encoding.equals("identity", true)) throw IOException("Fragment encoding changed.")
            val mime = opened.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
            if (mime.startsWith("text/") || mime in DOCUMENT_TYPES) throw IOException("Fragment returned a document instead of encoded media.")
            val body = opened.body ?: throw IOException("Selected fragment is empty.")
            if (fragment.rangeStart != null) {
                val range = RANGE.matchEntire(opened.header("Content-Range").orEmpty())
                val start = range?.groupValues?.get(1)?.toLongOrNull()
                val end = range?.groupValues?.get(2)?.toLongOrNull()
                val total = range?.groupValues?.get(3)?.toLongOrNull()
                if (opened.code != 206 || start != fragment.rangeStart || end != fragment.rangeEndExclusive!! - 1L || total == null || total <= end!!)
                    throw IOException("Selected fragment returned an invalid range.")
            } else if (opened.code != 200) throw IOException("Selected fragment returned an unsolicited partial response.")
            announced = body.contentLength().takeIf { it >= 0L }
            announced?.let { map.observe(index, it) }
            input = body.byteStream(); received = 0L; prefixChecked = false; prefixSize = 0; prefixPosition = 0
        }
        private fun readCurrent(buffer: ByteArray, offset: Int, length: Int): Int {
            synchronized(guard) { ensureCurrent() }
            val stream = input ?: throw IOException("Selected fragment connection is unavailable.")
            if (!prefixChecked) {
                while (prefixSize < prefix.size) {
                    val count = stream.read(prefix, prefixSize, prefix.size - prefixSize)
                    synchronized(guard) { ensureCurrent() }
                    if (count < 0) break
                    if (count > 0) prefixSize += count
                }
                val text = prefix.copyOf(prefixSize).toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase()
                if (prefixSize == 0 || text.startsWith("<?xml") || text.startsWith("<mpd") || text.startsWith("#extm3u") ||
                    text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<head") || text.startsWith("<script") || text.startsWith("{") || text.startsWith("["))
                    throw IOException("Selected fragment contains a manifest or web page.")
                prefixChecked = true
            }
            val count = if (prefixPosition < prefixSize) minOf(length, prefixSize - prefixPosition).also {
                System.arraycopy(prefix, prefixPosition, buffer, offset, it); prefixPosition += it
            } else stream.read(buffer, offset, length)
            synchronized(guard) { ensureCurrent() }
            if (count > 0) {
                received = Math.addExact(received, count.toLong())
                if (received > OriginalFragmentPlan.MAX_FRAGMENT_BYTES || map.length(index)?.let { received > it } == true)
                    throw IOException("Selected fragment exceeded its original byte bound.")
            }
            return count
        }
        private fun finishFragment(eof: Boolean) {
            if (eof) {
                if (announced?.let { received != it } == true) throw IOException("Selected fragment ended before its original byte length.")
                map.observe(index, received)
            }
            val opened = synchronized(guard) { responseCloseAttempted = true; response }
            try { if (opened != null) closeActualSource(opened) }
            catch (failure: Throwable) {
                synchronized(guard) { unproven = true }
                throw IOException("Selected fragment did not close safely. Stop playback before retrying.", failure)
            }
            synchronized(guard) { response = null; input = null; call = null }
        }
        private fun closeActualSource(opened: Response) {
            // Response.close/ResponseBody.close suppress IOException. Observe the actual owned
            // source's sole close, after read return, before returning the pair capacity.
            val body = opened.body ?: throw IOException("Selected fragment response has no provable source release.")
            body.source().close()
        }
        companion object {
            private val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
            private val DOCUMENT_TYPES = setOf("application/json", "application/xml", "application/xhtml+xml", "application/dash+xml", "application/x-mpegurl", "application/vnd.apple.mpegurl")
        }
    }
}
