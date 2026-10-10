package com.mangalens.core.translation.inpainting

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

internal data class LaMaModelState(val verified: Boolean = false, val checking: Boolean = false,
    val downloading: Boolean = false, val stopping: Boolean = false, val receivedBytes: Long = 0,
    val error: String? = null, val runtimeSupported: Boolean? = null, val restartRequired: Boolean = false) {
    val totalBytes: Long get() = LaMaReconstructionPin.MODEL_BYTES.toLong()
}

/** Application-owned optional download. Screen disposal retires previews, not an explicit model transfer. */
internal class LaMaModelManager private constructor(context: Context) {
    val store = LaMaModelArtifactStore(context.applicationContext.filesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = Mutex()
    private val generation = AtomicLong()
    private val guard = Any()
    private var owner: Any? = null
    private var active: Job? = null
    private var releases = LaMaTransferReleaseLedger()
    private class DisconnectTicket(val resource: HttpURLConnection)
    private val connection = AtomicReference<HttpURLConnection?>()
    private val values = MutableStateFlow(LaMaModelState())
    @Volatile private var quarantined: List<Any>? = null
    val state: StateFlow<LaMaModelState> = values
    init { recheck() }

    fun recheck() = start(false)
    fun download() = start(true)
    fun remove() = start(false, remove = true)
    fun captureInstalled(): Long? = synchronized(guard) {
        generation.get().takeIf { owner == null && values.value.verified && !values.value.stopping && quarantined == null }
    }
    fun isCurrent(captured: Long): Boolean = generation.get() == captured && values.value.verified &&
        !values.value.checking && !values.value.downloading && !values.value.stopping && quarantined == null
    fun retainUnprovenClose(problem: LaMaNativeCloseUnproven, lease: AutoCloseable?) = synchronized(guard) {
        quarantined = quarantined.orEmpty() + problem.retained + listOfNotNull(problem, problem.owner, lease)
        generation.incrementAndGet()
        values.value = values.value.copy(runtimeSupported = false, restartRequired = true, error = problem.message)
    }
    private fun retainIoClose(problem: LaMaIoCloseUnproven) = synchronized(guard) {
        releases.closeUnproven()
        quarantined = quarantined.orEmpty() + problem.retained + problem
        generation.incrementAndGet()
        values.value = values.value.copy(restartRequired = true, error = problem.message, checking = false, downloading = false, stopping = false)
    }
    private fun claimDisconnect(current: HttpURLConnection): DisconnectTicket? = synchronized(guard) {
        if (connection.get() !== current) null else {
            // Retain/register the closer before relinquishing the current connection's ownership.
            val ticket = DisconnectTicket(current)
            releases.registered(ticket)
            connection.set(null)
            ticket
        }
    }
    private fun disconnectOwned(ticket: DisconnectTicket) {
        try { ticket.resource.disconnect() } catch (problem: Throwable) { throw LaMaIoCloseUnproven(listOf(ticket.resource, ticket), problem) }
        synchronized(guard) { releases.closeReturned(ticket); releaseOwnerIfProven() }
    }
    private fun releaseConnection(current: HttpURLConnection) { claimDisconnect(current)?.let(::disconnectOwned) }
    private fun releaseOwnerIfProven() {
        if (quarantined == null && releases.mayRelease) { owner = null; active = null }
    }
    suspend fun readForPreview(captured: Long, reserveMemory: () -> Unit): ByteArray = writer.withLock {
        val caller = currentCoroutineContext()
        val checkpoint = { caller.ensureActive(); check(isCurrent(captured)) { "The installed repair pack changed." } }
        try { checkpoint(); val bytes = store.readVerifiedModel(checkpoint, reserveMemory); checkpoint(); bytes }
        catch (problem: LaMaIoCloseUnproven) { retainIoClose(problem); throw problem }
    }
    fun recordRuntimeCheck(captured: Long, supported: Boolean) = synchronized(guard) {
        if (isCurrent(captured)) values.value = values.value.copy(runtimeSupported = supported)
    }
    private fun start(download: Boolean, remove: Boolean = false) {
        synchronized(guard) {
            if (owner != null || quarantined != null) return
            releases = LaMaTransferReleaseLedger()
            val token = Any(); owner = token; val expected = generation.incrementAndGet()
            values.value = values.value.copy(checking = !download, downloading = download, stopping = false, error = null, runtimeSupported = null)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val caller = currentCoroutineContext()
                val checkpoint = { caller.ensureActive(); check(generation.get() == expected) { "The optional repair pack request was retired." } }
                try {
                    writer.withLock {
                        checkpoint()
                        if (remove) store.removeInstalled(checkpoint)
                        if (download) transfer(expected, checkpoint)
                        val verified = store.verifyInstalled(checkpoint)
                        val received = if (verified) LaMaReconstructionPin.MODEL_BYTES.toLong() else store.partialBytes()
                        checkpoint()
                        synchronized(guard) { if (owner === token && generation.get() == expected)
                            values.value = LaMaModelState(verified, receivedBytes = received) }
                    }
                } catch (problem: LaMaIoCloseUnproven) { retainIoClose(problem) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    synchronized(guard) { if (owner === token && generation.get() == expected)
                        values.value = values.value.copy(verified = false, error = if (download)
                            "The pinned LaMa repair pack could not be downloaded or verified. The saved partial can be resumed."
                        else "The LaMa repair pack could not be verified. Recheck it or download its pinned copy.") }
                } finally {
                    connection.get()?.let { try { releaseConnection(it) } catch (problem: LaMaIoCloseUnproven) { retainIoClose(problem) } }
                }
            }
            active = job
            // Completion also acknowledges a cancelled LAZY/queued producer whose body never ran.
            job.invokeOnCompletion { synchronized(guard) { if (owner === token) {
                releases.producerReturned(); releaseOwnerIfProven()
                values.value = values.value.copy(checking = false, downloading = false, stopping = owner != null && quarantined == null)
            } } }
            job.start()
        }
    }
    fun pauseDownload() {
        val captured = synchronized(guard) {
            if (owner == null || !values.value.downloading) return
            generation.incrementAndGet()
            values.value = values.value.copy(stopping = true, error = "Pausing the optional repair pack download; its current partial is retained.")
            active?.cancel(); connection.get()?.let(::claimDisconnect)
        }
        // Main retires the request immediately; descriptor/network close remains on IO.
        if (captured != null) scope.launch {
            try { disconnectOwned(captured) } catch (problem: LaMaIoCloseUnproven) { retainIoClose(problem) }
            synchronized(guard) { if (owner == null) values.value = values.value.copy(stopping = false) }
        }
    }
    private fun transfer(expected: Long, checkpoint: () -> Unit) {
        store.checkPaths(create = true); checkpoint()
        val part = store.part
        var previous = if (part.isFile) part.length() else 0L
        if (previous == LaMaReconstructionPin.MODEL_BYTES.toLong()) {
            try { store.commitDownloaded(checkpoint); return }
            catch (_: LaMaModelPinMismatchException) {
                checkpoint()
                // Explicit retry may restart this owner's fully received but checksum-invalid partial.
                withLaMaIoHandle(FileChannel.open(part.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { it.truncate(0); it.force(true) }
                previous = 0
            }
        }
        if (previous > LaMaReconstructionPin.MODEL_BYTES) {
            withLaMaIoHandle(FileChannel.open(part.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { it.truncate(0); it.force(true) }
            previous = 0
        }
        val available = android.os.StatFs(store.directory.absolutePath).availableBytes
        check(available >= LaMaReconstructionPin.MODEL_BYTES - previous + 32L * 1024 * 1024) { "Free storage before downloading the LaMa repair pack." }
        var address = LaMaReconstructionPin.MODEL_URL
        var redirects = 0
        while (true) {
            checkpoint(); LaMaModelDownloadPolicy.url(address)
            val current = URL(address).openConnection() as HttpURLConnection
            current.connectTimeout = 15_000; current.readTimeout = 20_000; current.instanceFollowRedirects = false
            current.setRequestProperty("User-Agent", "MangaLens/OriginalRegionRepair")
            current.setRequestProperty("Accept", "application/octet-stream")
            if (previous > 0) current.setRequestProperty("Range", "bytes=$previous-")
            var transferFailure: Throwable? = null
            try {
                // Pause registers its closer under this same guard, including before an IO launch begins.
                synchronized(guard) { checkpoint(); check(owner != null); connection.set(current) }
                current.connect(); checkpoint()
                if (current.responseCode in setOf(301, 302, 303, 307, 308)) {
                    check(++redirects <= 5) { "The model source redirected too often." }
                    val location = current.getHeaderField("Location") ?: throw IOException("The model redirect is missing.")
                    address = URL(URL(address), location).toString(); LaMaModelDownloadPolicy.url(address)
                    continue
                }
                val offset = LaMaModelDownloadPolicy.responseOffset(current.responseCode, previous, current.contentLengthLong, current.getHeaderField("Content-Range"))
                store.checkPaths(); checkpoint()
                withLaMaIoHandle(FileChannel.open(part.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { output ->
                    if (offset == 0L) output.truncate(0)
                    output.position(offset)
                    var received = offset; var lastForced = offset
                    withLaMaIoHandle(current.inputStream) { input ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            checkpoint()
                            val remaining = LaMaReconstructionPin.MODEL_BYTES.toLong() - received
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
                    check(received == LaMaReconstructionPin.MODEL_BYTES.toLong()) { "The LaMa repair pack download stopped early." }
                }
                store.commitDownloaded(checkpoint)
                return
            } catch (problem: Throwable) { transferFailure = problem; throw problem }
            finally {
                try { releaseConnection(current) } catch (problem: LaMaIoCloseUnproven) {
                    throw LaMaIoCloseUnproven(problem.retained + listOfNotNull(transferFailure), problem)
                }
            }
        }
    }
    companion object {
        private val instances = ConcurrentHashMap<String, LaMaModelManager>()
        fun shared(context: Context): LaMaModelManager = instances.computeIfAbsent(context.applicationContext.filesDir.absolutePath) {
            LaMaModelManager(context.applicationContext)
        }
    }
}
