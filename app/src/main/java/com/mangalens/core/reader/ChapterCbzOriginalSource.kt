package com.mangalens.core.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Every decoder/parser/copy reads one held, app-managed original descriptor on producer IO. */
internal class ChapterCbzOriginalSource(private val managedDirectory: File, private val resources: ChapterCbzResources = ChapterCbzResources(),
    private val openInput: (File) -> FileInputStream = { FileInputStream(it) }) : ChapterCbzOriginals {
    override fun open(page: ChapterCbzPage, check: () -> Unit): ChapterCbzHeldOriginal {
        check()
        val file = managedFile(page.path)
        val input = resources.ownPrivate(openInput(file))
        try {
            val identity = identity(input, file)
            val hashes = hashHeld(input, identity.bytes, check)
            val extension = validateRaster(input, check)
            requireIdentity(input, file, identity)
            return Held(input, file, ChapterCbzPagePin(hashes.first, identity.bytes, hashes.second, extension, identity))
        } catch (failure: Throwable) { resources.closePrivate(input); throw failure }
    }
    override fun verify(page: ChapterCbzPage, pin: ChapterCbzPagePin, check: () -> Unit) {
        check()
        val file = managedFile(page.path)
        resources.usePrivate(openInput(file)) { input ->
            requireIdentity(input, file, pin.identity)
            if (hashHeld(input, pin.bytes, check).first != pin.sha256) changed()
            requireIdentity(input, file, pin.identity); check()
        }
    }
    private fun managedFile(path: String): File {
        val selected = File(path)
        val direct = Os.lstat(selected.absolutePath)
        if (!OsConstants.S_ISREG(direct.st_mode)) changed()
        if (!OsConstants.S_ISDIR(Os.lstat(managedDirectory.absolutePath).st_mode)) changed()
        val root = managedDirectory.canonicalFile
        val file = selected.canonicalFile
        if (file.parentFile != root || !OsConstants.S_ISDIR(Os.lstat(root.absolutePath).st_mode)) changed()
        return selected.absoluteFile
    }
    private fun identity(input: FileInputStream, file: File): ChapterCbzFileIdentity {
        val held = Os.fstat(input.fd); val current = Os.lstat(file.absolutePath)
        if (!OsConstants.S_ISREG(held.st_mode) || !OsConstants.S_ISREG(current.st_mode) ||
            held.st_dev != current.st_dev || held.st_ino != current.st_ino || held.st_size != current.st_size ||
            held.st_mtime != current.st_mtime || held.st_ctime != current.st_ctime || held.st_size !in 1..ChapterCbzPolicy.MAX_PAGE_BYTES) changed()
        if (!OsConstants.S_ISDIR(Os.lstat(managedDirectory.absolutePath).st_mode) || file.canonicalFile.parentFile != managedDirectory.canonicalFile) changed()
        return ChapterCbzFileIdentity(held.st_dev, held.st_ino, held.st_size, held.st_mtime, held.st_ctime)
    }
    private fun requireIdentity(input: FileInputStream, file: File, expected: ChapterCbzFileIdentity) {
        if (identity(input, file) != expected) changed()
    }
    private fun hashHeld(input: FileInputStream, bytes: Long, check: () -> Unit): Pair<String, Long> {
        input.channel.position(0)
        val digest = MessageDigest.getInstance("SHA-256"); val crc = CRC32()
        val buffer = ByteArray(ChapterCbzPolicy.COPY_BUFFER_BYTES); var total = 0L
        while (true) { check(); val size = input.read(buffer); if (size < 0) break; total += size
            if (total > bytes || total > ChapterCbzPolicy.MAX_PAGE_BYTES) changed()
            digest.update(buffer, 0, size); crc.update(buffer, 0, size) }
        if (total != bytes) changed()
        input.channel.position(0); check()
        return digest.digest().cbzHex() to crc.value
    }
    private fun validateRaster(input: FileInputStream, check: () -> Unit): String {
        check(); input.channel.position(0)
        val header = ByteArray(12); var read = 0
        while (read < header.size) { check(); val count = input.read(header, read, header.size - read); if (count < 0) break; read += count }
        val extension = when {
            read >= 8 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "png"
            read >= 3 && header[0] == 255.toByte() && header[1] == 216.toByte() && header[2] == 255.toByte() -> "jpg"
            read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            read >= 6 && String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
            read >= 2 && header[0] == 66.toByte() && header[1] == 77.toByte() -> "bmp"
            else -> throw IOException("An original page has an unsupported image format. Retry that page before exporting.")
        }
        input.channel.position(0)
        if (extension == "png" && !PngRasterIntegrity.verifyIfPng(input, ChapterCbzPolicy.MAX_PAGE_BYTES, ChapterCbzPolicy.MAX_SOURCE_PIXELS, check)) changed()
        check(); input.channel.position(0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeFileDescriptor(input.fd, null, bounds)
        val width = bounds.outWidth; val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > ChapterCbzPolicy.MAX_SOURCE_PIXELS)
            throw IOException("An original page could not be decoded within the export limits. Retry that page.")
        val expectedMime = when (extension) { "jpg" -> "image/jpeg"; "bmp" -> "image/bmp"; else -> "image/$extension" }
        if (bounds.outMimeType != expectedMime && !(extension == "bmp" && bounds.outMimeType == "image/x-ms-bmp")) changed()
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > 512 || (height.toLong() + sample - 1) / sample > 512) sample *= 2
        check(); input.channel.position(0)
        val bitmap = BitmapFactory.decodeFileDescriptor(input.fd, null, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
        }) ?: throw IOException("An original page could not be decoded. Retry that page before exporting.")
        try { if (bitmap.width !in 1..512 || bitmap.height !in 1..512) throw IOException("An original page exceeded the bounded verification sample."); check() }
        finally { bitmap.recycle() }
        input.channel.position(0)
        return extension
    }
    private inner class Held(private val input: FileInputStream, private val file: File,
        override val pin: ChapterCbzPagePin) : ChapterCbzHeldOriginal {
        override fun copyTo(output: OutputStream, check: () -> Unit) {
            check(); requireIdentity(input, file, pin.identity); input.channel.position(0)
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(ChapterCbzPolicy.COPY_BUFFER_BYTES); var total = 0L
            while (true) { check(); val size = input.read(buffer); if (size < 0) break; total += size
                if (total > pin.bytes) changed(); digest.update(buffer, 0, size); output.write(buffer, 0, size) }
            if (total != pin.bytes || digest.digest().cbzHex() != pin.sha256) changed()
            requireIdentity(input, file, pin.identity); check()
        }
        override fun close() { resources.closePrivate(input) }
    }
    private fun changed(): Nothing = throw IOException("An offline original changed or is unavailable. Reopen the chapter and retry the page before exporting.")
}
