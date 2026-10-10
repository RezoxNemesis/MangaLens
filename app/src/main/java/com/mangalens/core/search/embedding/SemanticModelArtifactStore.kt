package com.mangalens.core.search.embedding

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal class SemanticModelPinMismatchException(message: String) : IOException(message)

/** One managed fixed artifact. All callers perform these bounded reads/writes on IO. */
internal class SemanticModelArtifactStore(filesRoot: File) {
    private val privateRoot = filesRoot.canonicalFile
    val directory = File(privateRoot, "semantic_models")
    val model = File(directory, SemanticEmbeddingPin.MODEL_SHA256 + ".onnx")
    val part = File(directory, SemanticEmbeddingPin.MODEL_SHA256 + ".onnx.part")
    fun checkPaths(create: Boolean = false) {
        require(directory.canonicalFile.parentFile == privateRoot && directory.canonicalFile == directory.absoluteFile)
        require(model.canonicalFile.parentFile == directory && part.canonicalFile.parentFile == directory)
        require(!Files.isSymbolicLink(model.toPath()) && !Files.isSymbolicLink(part.toPath()))
        if (create) check(directory.mkdirs() || directory.isDirectory)
    }
    fun partialBytes(): Long { checkPaths(); return part.takeIf { it.isFile }?.length()?.coerceAtMost(SemanticEmbeddingPin.MODEL_BYTES.toLong()) ?: 0L }
    fun verifyInstalled(checkpoint: () -> Unit): Boolean {
        checkPaths(); checkpoint()
        if (!model.exists()) return false
        verify(model, null, checkpoint)
        return true
    }
    /** Native parses this exact whole-pinned array, so a later path reopen cannot substitute weights. */
    fun readVerifiedModel(checkpoint: () -> Unit, reserveMemory: () -> Unit): ByteArray {
        checkPaths(); checkpoint()
        if (current(model).bytes != SemanticEmbeddingPin.MODEL_BYTES.toLong()) throw SemanticModelPinMismatchException("The optional model size does not match its pin.")
        reserveMemory(); checkpoint()
        val bytes = ByteArray(SemanticEmbeddingPin.MODEL_BYTES)
        verify(model, bytes, checkpoint)
        return bytes
    }
    fun commitDownloaded(checkpoint: () -> Unit) {
        checkPaths(); checkpoint(); val stamp = verify(part, null, checkpoint)
        FileChannel.open(part.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        checkpoint(); require(current(part) == stamp) { "The optional model partial changed during verification." }
        Files.move(part.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        checkpoint()
    }
    private fun verify(file: File, destination: ByteArray?, checkpoint: () -> Unit): Stamp {
        checkpoint(); val stamp = current(file)
        if (stamp.bytes != SemanticEmbeddingPin.MODEL_BYTES.toLong()) throw SemanticModelPinMismatchException("The English search model has an unexpected size. Download or recheck it.")
        require(destination == null || destination.size == SemanticEmbeddingPin.MODEL_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == stamp.bytes)
            val scratch = if (destination == null) ByteArray(65_536) else null
            var received = 0
            while (received < SemanticEmbeddingPin.MODEL_BYTES) {
                checkpoint()
                val count = minOf(65_536, SemanticEmbeddingPin.MODEL_BYTES - received)
                val buffer = if (destination != null) ByteBuffer.wrap(destination, received, count) else ByteBuffer.wrap(requireNotNull(scratch), 0, count)
                val actual = channel.read(buffer)
                if (actual < 0) throw IOException("The English search model stopped before its pinned size.")
                if (actual == 0) throw IOException("The English search model could not be read.")
                digest.update(destination ?: scratch!!, if (destination != null) received else 0, actual)
                received += actual
            }
            checkpoint(); require(channel.read(ByteBuffer.allocate(1)) == -1) { "The English search model exceeds its pinned size." }
        }
        checkpoint()
        if (digest.digest().hex() != SemanticEmbeddingPin.MODEL_SHA256) throw SemanticModelPinMismatchException("The English search model checksum does not match its publisher pin.")
        require(current(file) == stamp) { "The English search model changed while being read." }
        checkpoint(); return stamp
    }
    private data class Stamp(val key: Any?, val bytes: Long, val modified: java.nio.file.attribute.FileTime)
    private fun current(file: File): Stamp {
        require(file.canonicalFile.parentFile == directory && !Files.isSymbolicLink(file.toPath()))
        val value = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(value.isRegularFile)
        return Stamp(value.fileKey(), value.size(), value.lastModifiedTime())
    }
    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
