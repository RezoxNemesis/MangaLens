package com.mangalens.core.search.embedding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal class SemanticVectorEntry(val chapterId: String, val sourceSha256: String, val textSha256: String,
    vector: FloatArray, val tokenCount: Int, val truncated: Boolean) {
    val vector = vector.copyOf()
    val key: String get() = SemanticModelArtifactStore.sha256((SemanticEmbeddingPin.cachePin + ":" + sourceSha256 + ":" + textSha256).toByteArray(Charsets.UTF_8))
    init {
        require(chapterId.matches(Regex("[a-f0-9]{32}")) && sourceSha256.matches(Regex("[a-f0-9]{64}")) && textSha256.matches(Regex("[a-f0-9]{64}")))
        require(tokenCount in 2..SemanticEmbeddingPin.TOKENS); SemanticEmbeddingMath.validate(this.vector)
    }
}
internal data class SemanticVectorSnapshot(val entries: List<SemanticVectorEntry>, val unreadable: Boolean)
internal object SemanticVectorIndexCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    fun encode(entries: List<SemanticVectorEntry>): ByteArray {
        require(entries.size <= SemanticLibraryMetadata.CANDIDATES && entries.map { it.key }.distinct().size == entries.size)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x4d4c5349); output.writeInt(1)
            output.writeUTF(SemanticModelArtifactStore.sha256(SemanticEmbeddingPin.cachePin.toByteArray(Charsets.UTF_8)))
            output.writeInt(entries.size)
            entries.sortedBy { it.key }.forEach { entry ->
                SemanticEmbeddingMath.validate(entry.vector)
                output.writeUTF(entry.chapterId); output.writeUTF(entry.sourceSha256); output.writeUTF(entry.textSha256)
                output.writeInt(entry.tokenCount); output.writeBoolean(entry.truncated)
                entry.vector.forEach { output.writeFloat(it) }
            }
        }
        val body = bytes.toByteArray(); require(body.size + 32 <= MAX_BYTES)
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    fun decode(bytes: ByteArray): List<SemanticVectorEntry> {
        require(bytes.size in 48..MAX_BYTES)
        val body = bytes.copyOfRange(0, bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(bytes.size - 32, bytes.size)))
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == 0x4d4c5349 && input.readInt() == 1)
            require(input.readUTF() == SemanticModelArtifactStore.sha256(SemanticEmbeddingPin.cachePin.toByteArray(Charsets.UTF_8)))
            val count = input.readInt(); require(count in 0..SemanticLibraryMetadata.CANDIDATES)
            val records = (0 until count).map {
                val id = input.readUTF(); val source = input.readUTF(); val text = input.readUTF()
                val tokenCount = input.readInt(); val truncated = input.readBoolean()
                SemanticVectorEntry(id, source, text, FloatArray(SemanticEmbeddingPin.DIMENSIONS) { input.readFloat() }, tokenCount, truncated)
            }
            require(input.available() == 0 && records.map { it.key }.distinct().size == records.size)
            records
        }
    }
}

/** Derived read-only hints have their own bounded atomic journal inside the optional model category. */
internal enum class SemanticVectorCorpus(val filename: String) { LIBRARY("library-vectors-v1.bin"), DIALOGUE("dialogue-vectors-v1.bin") }

internal class SemanticVectorIndex(filesRoot: File, corpus: SemanticVectorCorpus = SemanticVectorCorpus.LIBRARY) {
    private val privateRoot = filesRoot.canonicalFile
    private val directory = File(privateRoot, "semantic_models")
    private val file = File(directory, corpus.filename)
    private val pending = File(directory, corpus.filename + ".pending")
    private val writer = writers.computeIfAbsent(directory.path) { Mutex() }
    fun read(checkpoint: () -> Unit = {}): SemanticVectorSnapshot {
        checkPaths()
        if (!file.exists()) return SemanticVectorSnapshot(emptyList(), false)
        return try {
            require(file.length() in 1..SemanticVectorIndexCodec.MAX_BYTES.toLong())
            val bytes = file.inputStream().use { input ->
                val output = ByteArrayOutputStream(); val scratch = ByteArray(8192)
                while (output.size() <= SemanticVectorIndexCodec.MAX_BYTES) {
                    checkpoint()
                    val count = input.read(scratch, 0, minOf(scratch.size, SemanticVectorIndexCodec.MAX_BYTES + 1 - output.size()))
                    if (count < 0) break
                    if (count == 0) throw IOException("The semantic index could not be read.")
                    output.write(scratch, 0, count)
                }
                output.toByteArray()
            }
            checkpoint(); SemanticVectorSnapshot(SemanticVectorIndexCodec.decode(bytes), false)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { SemanticVectorSnapshot(emptyList(), true) }
    }
    suspend fun replace(entries: List<SemanticVectorEntry>, checkpoint: () -> Unit) = withContext(Dispatchers.IO) {
        writer.withLock {
            checkpoint(); checkPaths(); check(directory.mkdirs() || directory.isDirectory)
            // Under the exact shared writer, the prior fixed stage can only be a crash orphan.
            require(!pending.exists() || pending.isFile)
            Files.deleteIfExists(pending.toPath())
            checkpoint()
            val bytes = SemanticVectorIndexCodec.encode(entries)
            val stage = pending
            try {
                FileChannel.open(stage.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) { checkpoint(); if (output.write(buffer) <= 0) throw IOException("The semantic index could not be saved.") }
                    output.force(true)
                }
                checkpoint(); checkPaths()
                Files.move(stage.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                checkpoint()
            } finally { if (stage.exists()) { checkPaths(); stage.delete() } }
        }
    }
    private fun checkPaths() {
        require(directory.canonicalFile == directory.absoluteFile && directory.canonicalFile.parentFile == privateRoot &&
            file.canonicalFile.parentFile == directory && pending.canonicalFile.parentFile == directory &&
            !Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(pending.toPath()))
    }
    companion object { private val writers = ConcurrentHashMap<String, Mutex>() }
}
