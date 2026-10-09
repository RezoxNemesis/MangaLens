package com.mangalens.ui.video

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class SubtitleExportLease(val token: String, val nonce: String)

/** A saved-instance token refers to private immutable bytes, never a large Bundle. */
internal class SubtitleExportSpool(private val directory: File, private val io: SubtitleJournalIo = AndroidSubtitleExportIo) {
    private val mutableWriting = MutableStateFlow<Set<String>>(emptySet())
    private val leases = mutableMapOf<String, String>()
    val writing: StateFlow<Set<String>> = mutableWriting
    init { check(directory.isDirectory || directory.mkdirs()) }
    @Synchronized fun stage(receipt: SubtitleExportReceipt): String {
        validate(receipt)
        managedFiles().filter { it.name.substringAfter("export-").substringBefore(".json") !in mutableWriting.value &&
            it.lastModified() < System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }
            .forEach { it.delete() }
        require(managedFiles().map { it.name.substringBefore(".json") }.distinct().size < 8) {
            "Too many pending subtitle exports. Complete or cancel an existing export and try again."
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        val bytes = JSONObject().put("token", token).put("taskId", receipt.taskId).put("generation", receipt.generation)
            .put("sourceCacheKey", receipt.sourceCacheKey).put("format", receipt.format).put("text", receipt.text)
            .put("sha256", SubtitleGenerationStore.digest(receipt.text)).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES && managedFiles().sumOf { it.length() } + bytes.size <= 64_000_000) {
            "Pending export storage is full. Complete or cancel an existing export and try again."
        }
        io.write(file(token), bytes)
        return token
    }
    @Synchronized fun read(token: String): SubtitleExportReceipt {
        val bytes = try { io.read(file(token)) } catch (failure: Exception) {
            throw java.io.IOException("Export request is unavailable. Export again.", failure)
        }
        require(bytes.size <= MAX_BYTES) { "Saved export is too large. Export again." }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getString("token") == token) { "Saved export token changed. Export again." }
        val receipt = SubtitleExportReceipt(json.getString("taskId"), json.getString("generation"), json.getString("sourceCacheKey"),
            json.getString("format"), json.getString("text"))
        validate(receipt)
        require(json.getString("sha256") == SubtitleGenerationStore.digest(receipt.text)) { "Saved export is corrupt. Export again." }
        return receipt
    }
    @Synchronized fun remove(token: String) {
        check(token !in leases) { "This subtitle export is being written." }
        removeManaged(token)
    }
    private fun removeManaged(token: String) {
        val base = file(token)
        listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach { it.delete() }
    }
    /** Acquire on the accepted callback, before provider work can be dispatched. */
    @Synchronized fun claim(token: String): SubtitleExportLease {
        require(token.matches(Regex("[a-f0-9]{32}"))) { "Export token is invalid. Export again." }
        if (token in leases) throw java.io.IOException("This subtitle export is already being written.")
        val lease = SubtitleExportLease(token, UUID.randomUUID().toString())
        leases[token] = lease.nonce
        mutableWriting.value = leases.keys.toSet()
        return lease
    }
    @Synchronized fun release(lease: SubtitleExportLease) {
        if (leases[lease.token] == lease.nonce) {
            leases.remove(lease.token); mutableWriting.value = leases.keys.toSet()
        }
    }
    suspend fun deliver(token: String, open: suspend () -> java.io.OutputStream?) = deliver(claim(token), open)
    suspend fun deliver(lease: SubtitleExportLease, open: suspend () -> java.io.OutputStream?) {
        synchronized(this) { check(leases[lease.token] == lease.nonce) { "Export lease is no longer active." } }
        try {
            read(lease.token).write(open)
            // Consumption is inside the lease. A restored controller cannot relaunch
            // this token between a provider success and managed cleanup.
            synchronized(this) { removeManaged(lease.token) }
        } finally { release(lease) }
    }
    private fun file(token: String): File {
        require(token.matches(Regex("[a-f0-9]{32}"))) { "Export token is invalid. Export again." }
        return File(directory, "export-$token.json").also {
            require(it.canonicalFile.parentFile == directory.canonicalFile) { "Export path is invalid." }
        }
    }
    private fun managedFiles() = directory.listFiles().orEmpty().filter {
        it.name.matches(Regex("export-[a-f0-9]{32}\\.json(\\.bak|\\.new)?"))
    }
    private fun validate(receipt: SubtitleExportReceipt) {
        require(receipt.taskId.matches(Regex("[a-f0-9]{32}")) && receipt.generation.matches(Regex("[a-f0-9]{32}")) &&
            receipt.sourceCacheKey.length in 1..16000 && receipt.format in setOf("srt", "vtt") &&
            receipt.text.isNotBlank() && receipt.text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Export request is invalid or too large. Export again." }
    }
    companion object {
        private const val MAX_BYTES = 8_000_000
        @Volatile private var instance: SubtitleExportSpool? = null
        fun shared(context: Context): SubtitleExportSpool = instance ?: synchronized(this) {
            instance ?: SubtitleExportSpool(File(context.applicationContext.filesDir, "subtitle-export-requests")).also { instance = it }
        }
    }
}

private object AndroidSubtitleExportIo : SubtitleJournalIo {
    override fun read(file: File): ByteArray = AtomicFile(file).openRead().use { require(it.channel.size() <= 8_000_000); it.readBytes() }
    override fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
}
