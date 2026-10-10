package com.mangalens.ui.reader

import com.mangalens.core.io.AndroidFileOpenFlags
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.mangalens.core.reader.MangaImagePolicy
import com.mangalens.core.reader.PngRasterIntegrity
import com.mangalens.core.translation.ChapterTranslationStore
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ReaderPanelDetection(val sourceSha256: String, val width: Int, val height: Int,
    val panels: List<ReaderPanelRect>)

/** No descriptor or bitmap survives this read-only, cancellable original-source operation. */
internal suspend fun detectReaderPanels(originalPath: String, rightToLeft: Boolean = false,
    expectedSourceSha256: String? = null): ReaderPanelDetection = withContext(Dispatchers.IO) {
    require(expectedSourceSha256 == null || expectedSourceSha256.matches(Regex("[a-f0-9]{64}")))
    val owner = currentCoroutineContext()
    owner.ensureActive()
    val file = File(originalPath)
    require(file.isAbsolute) { "Guided panels require a saved original image." }
    val descriptor = Os.open(originalPath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or AndroidFileOpenFlags.CLOSE_ON_EXEC, 0)
    var bitmap: Bitmap? = null
    data class Stamp(val device: Long, val inode: Long, val bytes: Long, val modified: Long, val changed: Long)
    fun stamp(value: StructStat): Stamp {
        require((value.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG)
        require(value.st_size in 1..ChapterTranslationStore.MAX_SURFACE_BYTES)
        return Stamp(value.st_dev, value.st_ino, value.st_size, value.st_mtime, value.st_ctime)
    }
    try {
        val captured = stamp(Os.fstat(descriptor))
        fun stillCurrent() {
            owner.ensureActive()
            check(stamp(Os.fstat(descriptor)) == captured && stamp(Os.lstat(originalPath)) == captured) {
                "Original image changed while finding panels."
            }
        }
        fun hash(): String {
            check(Os.lseek(descriptor, 0L, OsConstants.SEEK_SET) == 0L)
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                owner.ensureActive()
                val count = Os.read(descriptor, bytes, 0, bytes.size)
                if (count == 0) break
                total += count
                check(total <= captured.bytes) { "Original image changed size while finding panels." }
                digest.update(bytes, 0, count)
            }
            check(total == captured.bytes)
            stillCurrent()
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        stillCurrent()
        val identity = hash()
        check(expectedSourceSha256 == null || identity == expectedSourceSha256) { "Original image identity changed." }
        check(Os.lseek(descriptor, 0L, OsConstants.SEEK_SET) == 0L)
        val heldInput = object : InputStream() {
            private val one = ByteArray(1)
            override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                owner.ensureActive()
                return Os.read(descriptor, bytes, offset, length).let { if (it == 0) -1 else it }
            }
            override fun close() = Unit // The enclosing source operation owns this descriptor.
        }
        PngRasterIntegrity.verifyIfPng(heldInput, ChapterTranslationStore.MAX_SURFACE_BYTES, 100_000_000L) { owner.ensureActive() }
        stillCurrent()
        check(Os.lseek(descriptor, 0L, OsConstants.SEEK_SET) == 0L)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(descriptor, null, bounds)
        val size = MangaImagePolicy.dimensions(bounds.outWidth, bounds.outHeight)
            ?: error("Original image dimensions are unavailable.")
        var sample = 1
        while ((size.width.toLong() + sample - 1) / sample > ReaderPanelGeometry.MAX_SIDE ||
            (size.height.toLong() + sample - 1) / sample > ReaderPanelGeometry.MAX_SIDE) sample *= 2
        owner.ensureActive()
        check(Os.lseek(descriptor, 0L, OsConstants.SEEK_SET) == 0L)
        bitmap = BitmapFactory.decodeFileDescriptor(descriptor, null, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }) ?: error("Original image could not be sampled for panels.")
        val sampled = checkNotNull(bitmap)
        check(sampled.width in 1..ReaderPanelGeometry.MAX_SIDE && sampled.height in 1..ReaderPanelGeometry.MAX_SIDE)
        val pixels = IntArray(sampled.width * sampled.height)
        sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
        val panels = ReaderPanelGeometry.detect(sampled.width, sampled.height, pixels, rightToLeft)
        // A matching after-read digest binds proposals to the original held source,
        // rather than just a later path lookup or coarse timestamp.
        check(hash() == identity) { "Original image content changed while finding panels." }
        owner.ensureActive()
        ReaderPanelDetection(identity, size.width, size.height, panels)
    } finally {
        bitmap?.recycle()
        Os.close(descriptor)
    }
}
