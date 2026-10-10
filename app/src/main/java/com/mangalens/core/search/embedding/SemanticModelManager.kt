package com.mangalens.core.search.embedding

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class SemanticModelState(val verified: Boolean = false, val checking: Boolean = false,
    val downloading: Boolean = false, val stopping: Boolean = false, val receivedBytes: Long = 0,
    val error: String? = null) {
    val totalBytes: Long get() = SemanticEmbeddingPin.MODEL_BYTES.toLong()
}

/** Application-owned optional download. Screen disposal cancels search, not an explicit model transfer. */
internal class SemanticModelManager private constructor(context: Context) {
    val store = SemanticModelArtifactStore(context.applicationContext.filesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = Mutex()
    private val generation = AtomicLong()
    private val guard = Any()
    private var owner: Any? = null
    private var active: Job? = null
    private val connection = AtomicReference<HttpURLConnection?>()
    private val values = MutableStateFlow(SemanticModelState())
    val state: StateFlow<SemanticModelState> = values
    init { recheck() }

    fun recheck() = start(false)
    fun download() = start(true)
    private fun start(download: Boolean) {
        synchronized(guard) {
            if (owner != null) return
            val token = Any(); owner = token; val expected = generation.incrementAndGet()
            values.value = values.value.copy(checking = !download, downloading = download, stopping = false, error = null)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val caller = currentCoroutineContext()
                val checkpoint = { caller.ensureActive(); check(generation.get() == expected) { "The optional model request was retired." } }
                try {
                    writer.withLock {
                        checkpoint()
                        if (download) transfer(expected, checkpoint)
                        val verified = store.verifyInstalled(checkpoint)
                        checkpoint()
                        synchronized(guard) { if (owner === token && generation.get() == expected)
                            values.value = SemanticModelState(verified, receivedBytes = if (verified) SemanticEmbeddingPin.MODEL_BYTES.toLong() else store.partialBytes()) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    synchronized(guard) { if (owner === token && generation.get() == expected)
                        values.value = values.value.copy(verified = false, error = if (download)
                            "The pinned English search model could not be downloaded or verified. The saved partial can be resumed."
                        else "The English search model could not be verified. Recheck it or download its pinned copy.") }
                } finally {
                    connection.getAndSet(null)?.disconnect()
                    synchronized(guard) { if (owner === token) {
                        owner = null; active = null
                        values.value = values.value.copy(checking = false, downloading = false, stopping = false)
                    } }
                }
            }
            active = job; job.start()
        }
    }
    fun pauseDownload() {
        val captured = synchronized(guard) {
            if (owner == null || !values.value.downloading) return
            generation.incrementAndGet()
            values.value = values.value.copy(stopping = true, error = "Pausing the optional model download; its current partial is retained.")
            active?.cancel(); connection.get()
        }
        // Main retires the request immediately; descriptor/network close remains on IO.
        if (captured != null) scope.launch { if (connection.get() === captured) captured.disconnect() }
    }
    private fun transfer(expected: Long, checkpoint: () -> Unit) {
        store.checkPaths(create = true); checkpoint()
        val part = store.part
        var previous = if (part.isFile) part.length() else 0L
        if (previous == SemanticEmbeddingPin.MODEL_BYTES.toLong()) {
            try { store.commitDownloaded(checkpoint); return }
            catch (_: SemanticModelPinMismatchException) {
                checkpoint()
                // Explicit retry may restart this owner's fully received but checksum-invalid partial.
                FileChannel.open(part.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.truncate(0); it.force(true) }
                previous = 0
            }
        }
        if (previous > SemanticEmbeddingPin.MODEL_BYTES) {
            FileChannel.open(part.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.truncate(0); it.force(true) }
            previous = 0
        }
        val available = android.os.StatFs(store.directory.absolutePath).availableBytes
        check(available >= SemanticEmbeddingPin.MODEL_BYTES - previous + 32L * 1024 * 1024) { "Free storage before downloading the English search model." }
        var address = SemanticEmbeddingPin.MODEL_URL
        var redirects = 0
        while (true) {
            checkpoint(); SemanticModelDownloadPolicy.url(address)
            val current = URL(address).openConnection() as HttpURLConnection
            connection.set(current)
            current.connectTimeout = 15_000; current.readTimeout = 20_000; current.instanceFollowRedirects = false
            current.setRequestProperty("User-Agent", "MangaLens/EnglishMetadataSearch")
            current.setRequestProperty("Accept", "application/octet-stream")
            if (previous > 0) current.setRequestProperty("Range", "bytes=$previous-")
            try {
                current.connect(); checkpoint()
                if (current.responseCode in setOf(301, 302, 303, 307, 308)) {
                    check(++redirects <= 5) { "The model source redirected too often." }
                    val location = current.getHeaderField("Location") ?: throw IOException("The model redirect is missing.")
                    address = URL(URL(address), location).toString(); SemanticModelDownloadPolicy.url(address)
                    continue
                }
                val offset = SemanticModelDownloadPolicy.responseOffset(current.responseCode, previous, current.contentLengthLong, current.getHeaderField("Content-Range"))
                store.checkPaths(); checkpoint()
                FileChannel.open(part.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                    if (offset == 0L) output.truncate(0)
                    output.position(offset)
                    var received = offset; var lastForced = offset
                    current.inputStream.use { input ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            checkpoint()
                            val remaining = SemanticEmbeddingPin.MODEL_BYTES.toLong() - received
                            val actual = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
                            if (actual < 0) break
                            if (actual == 0) throw IOException("The model source stopped responding.")
                            require(actual <= remaining) { "The model source exceeds its pinned size." }
                            val bytes = ByteBuffer.wrap(buffer, 0, actual)
                            while (bytes.hasRemaining()) { checkpoint(); if (output.write(bytes) <= 0) throw IOException("The model partial could not be saved.") }
                            received += actual
                            if (received - lastForced >= 8L * 1024 * 1024) { output.force(true); lastForced = received }
                            checkpoint()
                            synchronized(guard) { if (generation.get() == expected && owner != null) values.value = values.value.copy(receivedBytes = received) }
                        }
                    }
                    output.force(true); checkpoint()
                    check(received == SemanticEmbeddingPin.MODEL_BYTES.toLong()) { "The English search model download stopped early." }
                }
                store.commitDownloaded(checkpoint)
                return
            } finally { connection.compareAndSet(current, null); current.disconnect() }
        }
    }
    companion object {
        private val instances = ConcurrentHashMap<String, SemanticModelManager>()
        fun shared(context: Context): SemanticModelManager = instances.computeIfAbsent(context.applicationContext.filesDir.canonicalPath) {
            SemanticModelManager(context.applicationContext)
        }
    }
}
