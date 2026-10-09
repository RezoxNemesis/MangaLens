package com.mangalens.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Activates only the immutable extractor shipped in the signed APK; never fetches code. */
internal class BundledExtractorInstaller(
    private val expectedSize: Long,
    private val expectedSha256: String
) {
    fun install(target: File, openBundled: () -> InputStream, checkActive: () -> Unit = {}): Boolean {
        checkActive()
        if (target.isFile && target.length() == expectedSize) {
            val installed = target.inputStream().use { hashAndCopy(it, null, checkActive) }
            if (installed.size == expectedSize && installed.sha256 == expectedSha256) return false
        }

        val parent = target.parentFile ?: throw IOException("Site extractor has no private parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create the site extractor directory")
        val staged = File.createTempFile(".yt-dlp-", ".partial", parent)
        try {
            val bundled = openBundled().use { input ->
                FileOutputStream(staged).use { output ->
                    hashAndCopy(input, output, checkActive).also { output.fd.sync() }
                }
            }
            if (bundled.size != expectedSize || bundled.sha256 != expectedSha256) {
                throw IOException("The bundled site extractor failed its APK integrity check. Reinstall or update MangaLens.")
            }
            checkActive()
            // Never truncate the old path: processes/readers holding that inode retain all
            // its bytes. Unsupported atomic replacement fails without modifying the old file.
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally {
            staged.delete()
        }
    }

    private fun hashAndCopy(input: InputStream, output: OutputStream?, checkActive: () -> Unit): HashedBytes {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8 * 1024)
        var size = 0L
        while (true) {
            checkActive()
            val limit = minOf(buffer.size.toLong(), expectedSize - size + 1L).toInt()
            val read = input.read(buffer, 0, limit)
            if (read < 0) break
            size += read
            if (size > expectedSize) throw IOException("Bundled site extractor exceeds its pinned APK size")
            digest.update(buffer, 0, read)
            output?.write(buffer, 0, read)
        }
        checkActive()
        return HashedBytes(size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private data class HashedBytes(val size: Long, val sha256: String)
}
