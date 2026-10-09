package com.mangalens.ui.video

import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class StoredCaptionFile(val file: File, val sha256: String, val bytes: Long)

/** Immutable content-addressed files; callers perform this bounded IO away from the UI. */
internal class CaptionFileStore(private val directory: File) {
    fun publish(captions: ImportedCaptionFile): StoredCaptionFile {
        val content = captions.srt.toByteArray(Charsets.UTF_8)
        require(content.size in 1..ImportedCaptionFile.MAX_BYTES) { "Canonical subtitles exceed the 2 MB limit." }
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create subtitle storage." }
        val digest = sha(content)
        val destination = File(directory.canonicalFile, "$digest.srt")
        val proof = StoredCaptionFile(destination, digest, content.size.toLong())
        if (verify(proof)) return proof
        val temporary = File.createTempFile(".caption-", ".pending", directory.canonicalFile)
        try {
            FileOutputStream(temporary).use { stream -> stream.write(content); stream.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            check(verify(proof)) { "Saved subtitle bytes could not be verified." }
            return proof
        } finally {
            temporary.delete()
        }
    }

    fun verify(proof: StoredCaptionFile): Boolean = runCatching { readVerified(proof); true }.getOrDefault(false)

    /** The proof covers the exact returned bytes, including a file replaced after earlier verification. */
    fun readVerified(proof: StoredCaptionFile): ByteArray {
        require(proof.sha256.matches(Regex("[a-f0-9]{64}")) && proof.bytes in 1..ImportedCaptionFile.MAX_BYTES.toLong()) {
            "Invalid subtitle file proof."
        }
        val expected = File(directory.canonicalFile, "${proof.sha256}.srt")
        val file = proof.file
        require(file.absoluteFile == expected && file.canonicalFile == expected && !Files.isSymbolicLink(file.toPath()) &&
            file.isFile && file.length() == proof.bytes) { "Subtitle file has changed; import it again." }
        return file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val content = ByteArrayOutputStream(proof.bytes.toInt())
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= proof.bytes) { "Subtitle file has grown beyond its verified size." }
                digest.update(buffer, 0, count)
                content.write(buffer, 0, count)
            }
            require(total == proof.bytes && digest.digest().hex() == proof.sha256) { "Subtitle file has changed; import it again." }
            content.toByteArray()
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
