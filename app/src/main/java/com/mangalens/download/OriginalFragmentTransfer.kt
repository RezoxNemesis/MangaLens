package com.mangalens.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class FragmentCheckpointIOException(cause: IOException) : IOException(
    "Could not save fragment progress. Previously saved progress was retained.", cause)

/** Complete encoded transport is not publication: the existing original-media audit remains mandatory. */
internal class OriginalFragmentTransfer(
    private val clientForFragment: (OriginalMediaFragment) -> OkHttpClient,
    private val privateFileCloseFailed: () -> Unit = {},
    private val openPrivateOutput: (File, Boolean) -> FileOutputStream = { file, append -> FileOutputStream(file, append) }
) {
    private data class Committed(val bytes: Long, val sha256: String)
    private var privateReleaseProven = true
    private val failedClose: () -> Unit = { privateReleaseProven = false; privateFileCloseFailed() }

    suspend fun download(plan: OriginalFragmentPlan, temp: File, checkpoint: File,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> }
    ): Long = withContext(Dispatchers.IO) {
        val captured = plan.captured()
        require(temp.parentFile?.canonicalFile == checkpoint.parentFile?.canonicalFile && temp != checkpoint)
        temp.parentFile?.mkdirs()
        require(!Files.isSymbolicLink(temp.toPath()) && !Files.isSymbolicLink(checkpoint.toPath())) { "Fragment storage changed ownership." }
        val planSha = captured.sha256()
        var committed = readCheckpoint(checkpoint, planSha, captured.fragments.size)
        var committedBytes = committed.fold(0L) { total, row -> Math.addExact(total, row.bytes) }
        verifyCommitted(temp, committed)
        truncate(temp, committedBytes)
        val knownTotal = captured.fragments.map { it.expectedBytes ?: it.rangeStart?.let { start -> it.rangeEndExclusive!! - start } }
            .takeIf { rows -> rows.all { it != null } }?.fold(0L) { total, bytes -> Math.addExact(total, bytes!!) } ?: -1L
        onProgress(committedBytes, knownTotal)
        for (index in committed.size until captured.fragments.size) {
            currentCoroutineContext().ensureActive()
            val fragment = captured.fragments[index]
            try {
                val received = transferOne(fragment, temp) { bytes -> onProgress(Math.addExact(committedBytes, bytes), knownTotal) }
                currentCoroutineContext().ensureActive()
                val next = committed + received
                writeCheckpoint(checkpoint, planSha, next)
                // There is no suspension between durable checkpoint acknowledgment and this local capture.
                committed = next
                committedBytes = Math.addExact(committedBytes, received.bytes)
            } catch (failure: Throwable) {
                if (privateReleaseProven && !failure.hasUnprovenPrivateClose()) {
                    try { withContext(NonCancellable) { truncate(temp, committedBytes) } }
                    catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                }
                throw failure
            }
        }
        currentCoroutineContext().ensureActive()
        check(committed.size == captured.fragments.size && temp.length() == committedBytes && committedBytes > 0L) {
            "Selected fragment sequence did not finish. No media was published."
        }
        committedBytes
    }

    private suspend fun transferOne(fragment: OriginalMediaFragment, temp: File,
        onProgress: suspend (Long) -> Unit
    ): Committed = coroutineScope {
        val expected = fragment.expectedBytes ?: fragment.rangeStart?.let { fragment.rangeEndExclusive!! - it }
        val request = Request.Builder().url(fragment.url).header("Accept-Encoding", "identity")
            .apply { fragment.rangeStart?.let { header("Range", "bytes=$it-${fragment.rangeEndExclusive!! - 1L}") } }.build()
        val call = clientForFragment(fragment).newCall(request)
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) throw IOException("Selected media fragment returned HTTP ${response.code}.")
                val encoding = response.header("Content-Encoding").orEmpty()
                if (encoding.isNotBlank() && !encoding.equals("identity", true)) throw IOException("Encoded fragment transfer changed its representation.")
                val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                if (type.startsWith("text/") || type in setOf("application/json", "application/xml", "application/xhtml+xml", "application/dash+xml", "application/x-mpegurl", "application/vnd.apple.mpegurl"))
                    throw IOException("Selected media fragment returned a document instead of encoded media.")
                val body = response.body ?: throw IOException("Selected media fragment was empty.")
                val announced = body.contentLength()
                if (fragment.rangeStart != null) {
                    val range = RANGE.matchEntire(response.header("Content-Range").orEmpty())
                    val start = range?.groupValues?.get(1)?.toLongOrNull()
                    val end = range?.groupValues?.get(2)?.toLongOrNull()
                    val total = range?.groupValues?.get(3)?.toLongOrNull()
                    if (response.code != 206 || start != fragment.rangeStart || end != fragment.rangeEndExclusive!! - 1L || total == null || total <= end!!)
                        throw IOException("Selected media fragment returned an invalid byte range.")
                } else if (response.code != 200) throw IOException("Selected media fragment returned an unsolicited partial response.")
                if (announced > OriginalFragmentPlan.MAX_FRAGMENT_BYTES || expected != null && announced >= 0L && expected != announced)
                    throw IOException("Selected media fragment length differs from the captured representation.")
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                // Response owns the sole network input close. The buffered wrapper owns the sole private output close.
                val input = body.byteStream()
                val raw = openPrivateOutput(temp, true)
                raw.wrapOwnedPrivateFile(failedClose) { it.buffered(BUFFER_SIZE) }.useOwnedPrivateFile(failedClose) { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    // Gather the bounded first prefix across short reads before writing any document bytes.
                    var prefixCount = 0
                    while (prefixCount < 512) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer, prefixCount, 512 - prefixCount)
                        if (count < 0) break
                        if (count == 0) continue
                        prefixCount += count
                    }
                    if (prefixCount == 0 || documentPrefix(buffer, prefixCount)) throw IOException("Selected media fragment contains a manifest or web page.")
                    done = prefixCount.toLong()
                    if (done > OriginalFragmentPlan.MAX_FRAGMENT_BYTES || expected != null && done > expected) throw IOException("Selected media fragment exceeded its captured byte bound.")
                    output.write(buffer, 0, prefixCount); digest.update(buffer, 0, prefixCount); onProgress(done)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        done = Math.addExact(done, count.toLong())
                        if (done > OriginalFragmentPlan.MAX_FRAGMENT_BYTES || expected != null && done > expected)
                            throw IOException("Selected media fragment exceeded its captured byte bound.")
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                        onProgress(done)
                    }
                    currentCoroutineContext().ensureActive()
                    if (done <= 0L || announced >= 0L && done != announced || expected != null && done != expected)
                        throw IOException("Selected media fragment ended before all encoded bytes arrived.")
                    output.flush(); raw.fd.sync()
                }
                Committed(done, digest.digest().hex())
            }
        } catch (failure: IOException) { currentCoroutineContext().ensureActive(); throw failure }
        finally { cancellation.cancel(); call.cancel() }
    }

    private suspend fun verifyCommitted(temp: File, rows: List<Committed>) {
        val expected = rows.fold(0L) { total, row -> Math.addExact(total, row.bytes) }
        if (rows.isEmpty()) return
        if (!temp.isFile || temp.length() < expected) throw IOException("Saved fragment prefix is incomplete. Resolve the source again.")
        temp.inputStream().useOwnedPrivateFile(failedClose) { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            rows.forEach { row ->
                val digest = MessageDigest.getInstance("SHA-256"); var remaining = row.bytes
                while (remaining > 0L) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count <= 0) throw IOException("Saved fragment prefix is incomplete.")
                    digest.update(buffer, 0, count); remaining -= count
                }
                if (digest.digest().hex() != row.sha256) throw IOException("Saved encoded fragment bytes changed. No media was published.")
            }
        }
    }
    private fun truncate(temp: File, length: Long) {
        if (!privateReleaseProven) throw IOException("Fragment file release is unproven. Partial files were retained.")
        RandomAccessFile(temp, "rw").useOwnedPrivateFile(failedClose) { file -> file.setLength(length); file.fd.sync() }
    }
    private fun readCheckpoint(file: File, planSha: String, count: Int): List<Committed> {
        if (!file.exists()) return emptyList()
        if (!file.isFile || file.length() !in 1..MAX_CHECKPOINT_BYTES.toLong()) throw IOException("Saved fragment checkpoint is invalid.")
        val bytes = file.inputStream().useOwnedPrivateFile(failedClose) { input ->
            val bounded = ByteArray(MAX_CHECKPOINT_BYTES + 1); var used = 0
            while (used < bounded.size) {
                val countRead = input.read(bounded, used, bounded.size - used)
                if (countRead < 0) break
                if (countRead == 0) continue
                used += countRead
            }
            bounded.copyOf(used)
        }
        if (bytes.size > MAX_CHECKPOINT_BYTES) throw IOException("Saved fragment checkpoint exceeds its bound.")
        val json = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
        catch (failure: Exception) { throw IOException("Saved fragment checkpoint is unreadable. Resolve the source again.", failure) }
        if (json.optString("version") != OriginalFragmentPlan.VERSION || json.optString("planSha256") != planSha)
            throw IOException("Saved fragment sequence differs from the selected source. Resolve the source again.")
        val rows = json.getJSONArray("completed")
        if (rows.length() > count) throw IOException("Saved fragment checkpoint has excess entries.")
        return (0 until rows.length()).map { index -> val row = rows.getJSONObject(index)
            val bytesCount = row.getLong("bytes"); val hash = row.getString("sha256")
            if (bytesCount !in 1..OriginalFragmentPlan.MAX_FRAGMENT_BYTES || !hash.matches(Regex("[a-f0-9]{64}"))) throw IOException("Saved fragment checkpoint is invalid.")
            Committed(bytesCount, hash)
        }
    }
    private fun writeCheckpoint(file: File, planSha: String, rows: List<Committed>) {
        val json = JSONObject().put("version", OriginalFragmentPlan.VERSION).put("planSha256", planSha)
            .put("completed", JSONArray().also { list -> rows.forEach { list.put(JSONObject().put("bytes", it.bytes).put("sha256", it.sha256)) } })
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_CHECKPOINT_BYTES) { "Fragment checkpoint exceeds its safe bound." }
        // A failed write/close/replace must not truncate the only receipt for committed bytes.
        // There is exactly one fixed staging name per caller-owned checkpoint, never a temp-file fanout.
        val stage = File(file.parentFile, file.name + ".new")
        try {
            if (Files.isSymbolicLink(stage.toPath()) || stage.exists() && !stage.isFile ||
                stage.canonicalFile.parentFile != file.parentFile.canonicalFile)
                throw IOException("Fragment checkpoint storage changed ownership.")
            openPrivateOutput(stage, false).useOwnedPrivateFile(failedClose) { output ->
                output.write(bytes); output.fd.sync()
            }
            Files.move(stage.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: IOException) {
            // Fail closed if atomic replacement is unavailable; no in-place fallback destroys old progress.
            throw FragmentCheckpointIOException(failure)
        }
    }
    private fun documentPrefix(bytes: ByteArray, count: Int): Boolean {
        val value = bytes.copyOf(minOf(count, 512)).toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase()
        return value.startsWith("<?xml") || value.startsWith("<mpd") || value.startsWith("#extm3u") ||
            value.startsWith("<!doctype html") || value.startsWith("<html") || value.startsWith("<head") || value.startsWith("<script") || value.startsWith("{") || value.startsWith("[")
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val MAX_CHECKPOINT_BYTES = 512 * 1024
        private val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
