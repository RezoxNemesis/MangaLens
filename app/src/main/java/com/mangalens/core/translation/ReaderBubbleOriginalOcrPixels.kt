package com.mangalens.core.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.system.Os
import android.system.OsConstants
import com.mangalens.core.translation.memory.MemorySourceProof
import com.mangalens.engine.OcrBox
import com.mangalens.engine.OcrOriginalCropLease
import com.mangalens.engine.acquireOriginalOcrCropOnIo
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Actual separately decoded original crop, never an Image-owned preview or cleaned PNG. */
internal data class ReaderBubbleOriginalOcrPixels(val bitmap: Bitmap, val source: MemorySourceProof) {
    fun toOriginal(box: OcrBox): OcrBox? {
        if (!box.valid || bitmap.width <= 0 || bitmap.height <= 0) return null
        val bounds = source.bounds
        val clipped = OcrBox(box.left.coerceIn(0f, bitmap.width.toFloat()), box.top.coerceIn(0f, bitmap.height.toFloat()),
            box.right.coerceIn(0f, bitmap.width.toFloat()), box.bottom.coerceIn(0f, bitmap.height.toFloat()))
        if (!clipped.valid) return null
        return OcrBox(bounds.left + clipped.left * (bounds.right - bounds.left) / bitmap.width,
            bounds.top + clipped.top * (bounds.bottom - bounds.top) / bitmap.height,
            bounds.left + clipped.right * (bounds.right - bounds.left) / bitmap.width,
            bounds.top + clipped.bottom * (bounds.bottom - bounds.top) / bitmap.height)
    }
}

/** The consume callback must await the real ML Kit completion before returning/cancelling. */
internal class ReaderBubbleOriginalOcrSource(private val sourceDirectory: File) {
    suspend fun <T> withPixels(inspection: ReaderBubbleInspection, current: suspend () -> Boolean,
        consume: suspend (ReaderBubbleOriginalOcrPixels) -> T): T? {
        currentCoroutineContext().ensureActive()
        val source = inspection.source ?: return null
        if (!current()) return null
        val lease = acquireOriginalOcrCropOnIo { openOnIo(source) } ?: return null
        try {
            currentCoroutineContext().ensureActive()
            if (!lease.sourceStillVerified() || !current()) return null
            val result = consume(lease.resource)
            currentCoroutineContext().ensureActive()
            if (!lease.sourceStillVerified() || !current()) return null
            return result
        } finally { lease.release() }
    }

    @Suppress("DEPRECATION")
    private suspend fun openOnIo(source: MemorySourceProof): OcrOriginalCropLease<ReaderBubbleOriginalOcrPixels>? {
        currentCoroutineContext().ensureActive()
        val file = File(source.sourcePath).canonicalFile
        if (file.path != source.sourcePath || file.parentFile != sourceDirectory.canonicalFile) return null
        val plan = ReaderBubbleCropPlan.create(source)
        val input = try { FileInputStream(file) } catch (_: Exception) { return null }
        var decoder: BitmapRegionDecoder? = null
        var bitmap: Bitmap? = null
        var transferred = false
        try {
            if (!verified(input, file, source)) return null
            decoder = BitmapRegionDecoder.newInstance(input.fd, false) ?: return null
            if (decoder.width != source.imageWidth || decoder.height != source.imageHeight) return null
            currentCoroutineContext().ensureActive()
            val b = source.bounds
            bitmap = decoder.decodeRegion(Rect(b.left, b.top, b.right, b.bottom), BitmapFactory.Options().apply {
                inSampleSize = plan.sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return null
            if (!plan.acceptsActualDecode(bitmap.width, bitmap.height)) return null
            if (!verified(input, file, source)) return null
            currentCoroutineContext().ensureActive()
            val heldBitmap = requireNotNull(bitmap); val heldDecoder = requireNotNull(decoder)
            val result = OcrOriginalCropLease(ReaderBubbleOriginalOcrPixels(heldBitmap, source), heldBitmap.width, heldBitmap.height,
                { withContext(Dispatchers.IO) { verified(input, file, source) } },
                { try { heldBitmap.recycle() } finally { try { heldDecoder.recycle() } finally { input.close() } } })
            transferred = true
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        finally { if (!transferred) try { bitmap?.recycle() } finally { try { decoder?.recycle() } finally { input.close() } } }
    }

    private data class Stamp(val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long)
    private fun stamp(value: android.system.StructStat) = Stamp(value.st_dev, value.st_ino, value.st_size, value.st_mtime, value.st_ctime)
    private suspend fun verified(input: FileInputStream, file: File, source: MemorySourceProof): Boolean = try {
        currentCoroutineContext().ensureActive()
        val descriptor = Os.fstat(input.fd); val path = Os.lstat(file.path); val before = stamp(descriptor)
        if (!OsConstants.S_ISREG(descriptor.st_mode) || !OsConstants.S_ISREG(path.st_mode) || before != stamp(path) ||
            descriptor.st_size !in 1..40L * 1024 * 1024 || file.canonicalPath != source.sourcePath) false
        else {
            input.channel.position(0L)
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024); var count = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                if (count > 40L * 1024 * 1024) throw java.io.IOException("Original image exceeded its verified limit.")
                digest.update(buffer, 0, read)
            }
            input.channel.position(0L)
            currentCoroutineContext().ensureActive()
            before == stamp(Os.fstat(input.fd)) && before == stamp(Os.lstat(file.path)) && count == before.size &&
                digest.digest().joinToString("") { "%02x".format(it) } == source.sourceSha256
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}
