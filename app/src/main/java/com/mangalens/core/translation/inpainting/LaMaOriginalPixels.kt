package com.mangalens.core.translation.inpainting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.system.Os
import android.system.OsConstants
import com.mangalens.core.reader.PngRasterIntegrity
import com.mangalens.core.translation.MangaWritableRect
import com.mangalens.core.translation.memory.MemorySourceProof
import com.mangalens.ui.reader.acquireReaderBubblePreviewOnIo
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

/** Independently held original input for OCR and the optional model; never borrows the dialog image. */
internal class LaMaOriginalPixels private constructor(val bitmap: Bitmap, val source: MemorySourceProof,
    private val verify: suspend () -> Boolean, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    suspend fun isCurrent() = !closed.get() && verify() && !closed.get()
    override fun close() { if (closed.compareAndSet(false, true)) release() }

    fun sampled(bounds: com.mangalens.core.translation.memory.MemoryRegionBounds): MangaWritableRect? {
        val crop = source.bounds
        // Protect a neighbour crossing the input edge by clipping; no crop credential is created.
        val left = maxOf(crop.left, bounds.left); val top = maxOf(crop.top, bounds.top)
        val right = minOf(crop.right, bounds.right); val bottom = minOf(crop.bottom, bounds.bottom)
        if (left >= right || top >= bottom) return null
        val sx = bitmap.width.toDouble() / (crop.right - crop.left); val sy = bitmap.height.toDouble() / (crop.bottom - crop.top)
        return MangaWritableRect(floor((left - crop.left) * sx).toInt().coerceAtLeast(0), floor((top - crop.top) * sy).toInt().coerceAtLeast(0),
            ceil((right - crop.left) * sx).toInt().coerceAtMost(bitmap.width), ceil((bottom - crop.top) * sy).toInt().coerceAtMost(bitmap.height))
            .takeIf { it.valid(bitmap.width, bitmap.height) }
    }

    companion object {
        @Suppress("DEPRECATION")
        suspend fun open(sourceDirectory: File, source: MemorySourceProof, current: suspend () -> Unit): LaMaOriginalPixels? =
            acquireReaderBubblePreviewOnIo {
                source.validate(); current(); currentCoroutineContext().ensureActive()
                val file = File(source.sourcePath).canonicalFile
                if (file.path != source.sourcePath || file.parentFile != sourceDirectory.canonicalFile) return@acquireReaderBubblePreviewOnIo null
                val fd = try { Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0) } catch (_: Exception) { return@acquireReaderBubblePreviewOnIo null }
                val input = try { FileInputStream(fd) } catch (failure: Throwable) {
                    try { Os.close(fd) } catch (close: Throwable) { throw LaMaNativeCloseUnproven(listOf(fd, failure), fd, close) }
                    throw failure
                }
                var decoder: BitmapRegionDecoder? = null; var bitmap: Bitmap? = null; var transferred = false
                try {
                    if (!verified(input, file, source)) return@acquireReaderBubblePreviewOnIo null
                    val job = currentCoroutineContext()[Job]
                    PngRasterIntegrity.verifyIfPng(input, checkpoint = { job?.ensureActive() }); input.channel.position(0)
                    decoder = BitmapRegionDecoder.newInstance(input.fd, false) ?: return@acquireReaderBubblePreviewOnIo null
                    if (decoder.width != source.imageWidth || decoder.height != source.imageHeight) return@acquireReaderBubblePreviewOnIo null
                    val bounds = source.bounds; var sample = 1
                    val width = bounds.right - bounds.left; val height = bounds.bottom - bounds.top
                    while ((width.toLong() + sample - 1) / sample > 512 || (height.toLong() + sample - 1) / sample > 512) sample *= 2
                    current()
                    bitmap = decoder.decodeRegion(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), BitmapFactory.Options().apply {
                        inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
                    }) ?: return@acquireReaderBubblePreviewOnIo null
                    if (bitmap.width !in maxOf(1, width / sample)..minOf(512, (width + sample - 1) / sample) ||
                        bitmap.height !in maxOf(1, height / sample)..minOf(512, (height + sample - 1) / sample)) return@acquireReaderBubblePreviewOnIo null
                    current(); if (!verified(input, file, source)) return@acquireReaderBubblePreviewOnIo null
                    currentCoroutineContext().ensureActive()
                    val heldBitmap = bitmap; val heldDecoder = decoder
                    LaMaOriginalPixels(heldBitmap, source, { withContext(Dispatchers.IO) { current(); verified(input, file, source) } },
                        { release(heldBitmap, heldDecoder, input) }).also { transferred = true }
                } finally {
                    if (!transferred) release(bitmap, decoder, input)
                }
            }

        private fun release(bitmap: Bitmap?, decoder: BitmapRegionDecoder?, input: FileInputStream) {
            var failure: Throwable? = null
            try { bitmap?.recycle() } catch (problem: Throwable) { failure = problem }
            try { decoder?.recycle() } catch (problem: Throwable) { if (failure == null) failure = problem else failure.addSuppressed(problem) }
            try { input.close() } catch (problem: Throwable) { if (failure == null) failure = problem else failure.addSuppressed(problem) }
            failure?.let { throw LaMaNativeCloseUnproven(listOfNotNull(bitmap, decoder, input), input, it) }
        }

        private data class Stamp(val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long)
        private fun stamp(v: android.system.StructStat) = Stamp(v.st_dev, v.st_ino, v.st_size, v.st_mtime, v.st_ctime)
        private suspend fun verified(input: FileInputStream, file: File, source: MemorySourceProof): Boolean {
            currentCoroutineContext().ensureActive()
            val descriptor = Os.fstat(input.fd); val path = Os.lstat(file.path); val before = stamp(descriptor)
            if (!OsConstants.S_ISREG(descriptor.st_mode) || !OsConstants.S_ISREG(path.st_mode) || before != stamp(path) ||
                descriptor.st_size !in 1..40L * 1024 * 1024 || file.canonicalPath != source.sourcePath) return false
            input.channel.position(0); val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65_536); var count = 0L
            while (true) {
                currentCoroutineContext().ensureActive(); val read = input.read(buffer); if (read < 0) break
                if (read == 0) return false
                count += read; if (count > 40L * 1024 * 1024) return false
                digest.update(buffer, 0, read)
            }
            input.channel.position(0); currentCoroutineContext().ensureActive()
            return count == before.size && before == stamp(Os.fstat(input.fd)) && before == stamp(Os.lstat(file.path)) &&
                file.canonicalPath == source.sourcePath && digest.digest().joinToString("") { "%02x".format(it) } == source.sourceSha256
        }
    }
}
