package com.mangalens.core.translation.inpainting

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

internal class LaMaModelPinMismatchException(message: String) : IOException(message)

/** One managed fixed artifact. All callers perform these bounded reads/writes on IO. */
internal class LaMaModelArtifactStore(filesRoot: File) {
    private val privateRoot by lazy { filesRoot.canonicalFile }
    val directory by lazy { File(privateRoot, "inpainting_models") }
    val model by lazy { File(directory, LaMaReconstructionPin.MODEL_SHA256 + ".onnx") }
    val part by lazy { File(directory, LaMaReconstructionPin.MODEL_SHA256 + ".onnx.part") }
    fun checkPaths(create: Boolean = false) {
        require(privateRoot.canonicalFile == privateRoot)
        require(directory.canonicalFile.parentFile == privateRoot && directory.canonicalFile == directory.absoluteFile)
        require(model.canonicalFile.parentFile == directory && part.canonicalFile.parentFile == directory)
        require(!Files.isSymbolicLink(model.toPath()) && !Files.isSymbolicLink(part.toPath()))
        if (create) check(directory.mkdirs() || directory.isDirectory)
    }
    fun partialBytes(): Long { checkPaths(); return part.takeIf { it.isFile }?.length()?.coerceAtMost(LaMaReconstructionPin.MODEL_BYTES.toLong()) ?: 0L }
    fun verifyInstalled(checkpoint: () -> Unit): Boolean {
        checkPaths(); checkpoint()
        if (!model.exists()) return false
        verify(model, null, checkpoint)
        return true
    }
    /** Native parses this exact whole-pinned array, so a later path reopen cannot substitute weights. */
    fun readVerifiedModel(checkpoint: () -> Unit, reserveMemory: () -> Unit): ByteArray {
        checkPaths(); checkpoint()
        if (current(model).bytes != LaMaReconstructionPin.MODEL_BYTES.toLong()) throw LaMaModelPinMismatchException("The optional repair pack size does not match its pin.")
        reserveMemory(); checkpoint()
        val bytes = ByteArray(LaMaReconstructionPin.MODEL_BYTES)
        verify(model, bytes, checkpoint)
        return bytes
    }
    fun commitDownloaded(checkpoint: () -> Unit) {
        checkPaths(); checkpoint(); val stamp = verify(part, null, checkpoint)
        withLaMaIoHandle(FileChannel.open(part.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { it.force(true) }
        checkpoint(); require(current(part) == stamp) { "The optional repair pack partial changed during verification." }
        Files.move(part.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        withLaMaIoHandle(FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { it.force(true) }
        checkpoint()
    }
    fun removeInstalled(checkpoint: () -> Unit) {
        checkPaths(); checkpoint()
        Files.deleteIfExists(model.toPath()); checkpoint()
        Files.deleteIfExists(part.toPath()); checkpoint()
        if (directory.isDirectory) withLaMaIoHandle(FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { it.force(true) }
    }
    private fun verify(file: File, destination: ByteArray?, checkpoint: () -> Unit): Stamp {
        checkpoint(); val stamp = current(file)
        if (stamp.bytes != LaMaReconstructionPin.MODEL_BYTES.toLong()) throw LaMaModelPinMismatchException("The LaMa repair pack has an unexpected size. Download or recheck it.")
        require(destination == null || destination.size == LaMaReconstructionPin.MODEL_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        withLaMaIoHandle(FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel ->
            require(channel.size() == stamp.bytes)
            val scratch = if (destination == null) ByteArray(65_536) else null
            var received = 0
            while (received < LaMaReconstructionPin.MODEL_BYTES) {
                checkpoint()
                val count = minOf(65_536, LaMaReconstructionPin.MODEL_BYTES - received)
                val buffer = if (destination != null) ByteBuffer.wrap(destination, received, count) else ByteBuffer.wrap(requireNotNull(scratch), 0, count)
                val actual = channel.read(buffer)
                if (actual < 0) throw IOException("The LaMa repair pack stopped before its pinned size.")
                if (actual == 0) throw IOException("The LaMa repair pack could not be read.")
                digest.update(destination ?: scratch!!, if (destination != null) received else 0, actual)
                received += actual
            }
            checkpoint(); require(channel.read(ByteBuffer.allocate(1)) == -1) { "The LaMa repair pack exceeds its pinned size." }
        }
        checkpoint()
        if (digest.digest().hex() != LaMaReconstructionPin.MODEL_SHA256) throw LaMaModelPinMismatchException("The LaMa repair pack checksum does not match its publisher pin.")
        require(current(file) == stamp) { "The LaMa repair pack changed while being read." }
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
