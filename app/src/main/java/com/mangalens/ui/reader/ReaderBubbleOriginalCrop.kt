package com.mangalens.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.system.Os
import android.system.OsConstants
import com.mangalens.core.translation.ReaderBubbleCropPlan
import com.mangalens.core.translation.ReaderBubbleInspection
import com.mangalens.core.translation.memory.MemorySourceProof
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Dialog-owned original preview; it is never an OCR worker proof or a model input. */
internal class ReaderBubbleOriginalCrop internal constructor(
    val bitmap: Bitmap,
    val source: MemorySourceProof,
    private val verify: suspend () -> Boolean,
    private val release: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    suspend fun isSourceCurrent(): Boolean = !closed.get() && verify() && !closed.get()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

/** Every decoder borrows the actual held original FD, not a path reopened after checksum verification. */
internal class ReaderBubbleOriginalCropLoader(private val sourceDirectory: File) {
    suspend fun open(inspection: ReaderBubbleInspection, current: suspend () -> Boolean): ReaderBubbleOriginalCrop? =
        acquireReaderBubblePreviewOnIo { openOnIo(inspection, current) }

    @Suppress("DEPRECATION")
    private suspend fun openOnIo(inspection: ReaderBubbleInspection, current: suspend () -> Boolean): ReaderBubbleOriginalCrop? {
            currentCoroutineContext().ensureActive()
            val source = inspection.source ?: return null
            val plan = ReaderBubbleCropPlan.create(source)
            if (!current()) return null
            val file = try { File(source.sourcePath).canonicalFile } catch (_: IOException) { return null }
            if (file.path != source.sourcePath || file.parentFile != sourceDirectory.canonicalFile || !file.isFile)
                return null
            val input = try { FileInputStream(file) } catch (_: Exception) { return null }
            var decoder: BitmapRegionDecoder? = null
            var bitmap: Bitmap? = null
            var transferred = false
            try {
                if (!verified(input, file, source)) return null
                currentCoroutineContext().ensureActive()
                decoder = BitmapRegionDecoder.newInstance(input.fd, false) ?: return null
                if (decoder.width != source.imageWidth || decoder.height != source.imageHeight) return null
                currentCoroutineContext().ensureActive()
                val region = plan.bounds
                bitmap = decoder.decodeRegion(Rect(region.left, region.top, region.right, region.bottom),
                    BitmapFactory.Options().apply { inSampleSize = plan.sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    ?: return null
                if (!plan.acceptsActualDecode(bitmap.width, bitmap.height)) return null
                currentCoroutineContext().ensureActive()
                if (!verified(input, file, source) || !current()) return null
                currentCoroutineContext().ensureActive()
                val heldBitmap = bitmap
                val heldDecoder = decoder
                val result = ReaderBubbleOriginalCrop(heldBitmap, source,
                    { withContext(Dispatchers.IO) { verified(input, file, source) && current() } },
                    { try { heldBitmap.recycle() } finally { try { heldDecoder.recycle() } finally { input.close() } } })
                transferred = true
                return result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
            finally {
                if (!transferred) try { bitmap?.recycle() } finally { try { decoder?.recycle() } finally { input.close() } }
            }
        }

    private data class DescriptorStamp(val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long)
    private fun stamp(value: android.system.StructStat) = DescriptorStamp(value.st_dev, value.st_ino, value.st_size, value.st_mtime, value.st_ctime)

    /** Count and hash the same descriptor; both no-follow path identity and held metadata must remain stable. */
    private suspend fun verified(input: FileInputStream, file: File, source: MemorySourceProof): Boolean = try {
        currentCoroutineContext().ensureActive()
        val held = Os.fstat(input.fd); val path = Os.lstat(file.path)
        val before = stamp(held)
        if (!OsConstants.S_ISREG(held.st_mode) || !OsConstants.S_ISREG(path.st_mode) || before != stamp(path) ||
            held.st_size !in 1..MAX_SOURCE_BYTES || file.canonicalPath != source.sourcePath) false
        else {
            input.channel.position(0L)
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_SOURCE_BYTES) throw IOException("Original preview source exceeds its byte limit.")
                hash.update(buffer, 0, count)
            }
            input.channel.position(0L)
            currentCoroutineContext().ensureActive()
            before == stamp(Os.fstat(input.fd)) && before == stamp(Os.lstat(file.path)) && total == before.size &&
                file.canonicalPath == source.sourcePath &&
                hash.digest().joinToString("") { "%02x".format(it) } == source.sourceSha256
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    private companion object { const val MAX_SOURCE_BYTES = 40L * 1024 * 1024 }
}
