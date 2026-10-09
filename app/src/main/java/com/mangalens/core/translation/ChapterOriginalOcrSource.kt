package com.mangalens.core.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.system.Os
import com.mangalens.engine.OcrOriginalCropLease
import com.mangalens.engine.OcrOriginalRegionCrop
import com.mangalens.engine.OcrOriginalRegionSource
import com.mangalens.engine.OcrOriginalSourceChangedException
import com.mangalens.engine.OcrPixelVariantSpec
import com.mangalens.engine.OcrSourceResolutionPlan
import com.mangalens.engine.OcrSourceResolutionProof
import com.mangalens.engine.OcrSourceResolutionRequest
import com.mangalens.engine.acquireOriginalOcrCropOnIo
import com.mangalens.engine.planOriginalPixelVariant
import com.mangalens.engine.renderContextualOcrRetry
import com.mangalens.engine.withQualifiedOriginalOcrCrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Original bytes are borrowed only for a captured private page and an active translation owner. */
internal class ChapterOriginalOcrSource(
    private val proof: OcrSourceResolutionProof,
    private val sourceDirectory: File,
    private val checkpoint: suspend () -> OcrSourceResolutionProof?
) : OcrOriginalRegionSource {
    override suspend fun <T> withCrop(request: OcrSourceResolutionRequest, consume: suspend (OcrOriginalRegionCrop) -> T): T? =
        withQualifiedOriginalOcrCrop(proof, request, checkpoint,
            { plan -> acquireOriginalOcrCropOnIo { openCrop(plan) } }) { lease, plan ->
            val pixels = planOriginalPixelVariant(lease.width, lease.height, OcrPixelVariantSpec("original-contextual-scale", request.maxScale))
                ?: error("Original OCR crop has no bounded rendering plan.")
            val image = if (pixels.transform.a == 1f && pixels.outputWidth == lease.width && pixels.outputHeight == lease.height)
                lease.resource else renderContextualOcrRetry(lease.resource, pixels)
            try {
                // Rendering is real work. Recheck after it, immediately before the engine enters ML Kit.
                currentCoroutineContext().ensureActive()
                if (!lease.sourceStillVerified()) throw OcrOriginalSourceChangedException()
                currentCoroutineContext().ensureActive()
                if (checkpoint() != proof) throw CancellationException("Original OCR generation was replaced.")
                consume(OcrOriginalRegionCrop(image, plan, lease.width, lease.height, pixels))
            } finally { if (image !== lease.resource) image.recycle() }
        }

    @Suppress("DEPRECATION")
    private suspend fun openCrop(plan: OcrSourceResolutionPlan): OcrOriginalCropLease<Bitmap>? {
        currentCoroutineContext().ensureActive()
        val file = File(proof.sourcePath).canonicalFile
        if (file.absolutePath != proof.sourcePath || file.parentFile != sourceDirectory.canonicalFile || !file.isFile)
            throw OcrOriginalSourceChangedException()
        val input = try { FileInputStream(file) } catch (_: Exception) { throw OcrOriginalSourceChangedException() }
        var decoder: BitmapRegionDecoder? = null
        var crop: Bitmap? = null
        var leased = false
        try {
            if (!verified(input, file)) throw OcrOriginalSourceChangedException()
            decoder = BitmapRegionDecoder.newInstance(input.fd, false) ?: return null
            if (decoder.width != proof.originalWidth || decoder.height != proof.originalHeight) throw OcrOriginalSourceChangedException()
            currentCoroutineContext().ensureActive()
            val region = plan.sourceCrop
            crop = decoder.decodeRegion(Rect(region.left.toInt(), region.top.toInt(), region.right.toInt(), region.bottom.toInt()),
                BitmapFactory.Options().apply { inSampleSize = plan.decodeSample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
            val ownedCrop = crop
            val ownedDecoder = decoder
            val lease = OcrOriginalCropLease(ownedCrop, ownedCrop.width, ownedCrop.height,
                { withContext(Dispatchers.IO) { verified(input, file) } }, {
                    try { ownedCrop.recycle() } finally { try { ownedDecoder.recycle() } finally { input.close() } }
                })
            leased = true
            return lease
        } finally {
            if (!leased) try { crop?.recycle() } finally { try { decoder?.recycle() } finally { input.close() } }
        }
    }

    /** Hash the same open descriptor used by the decoder; also reject replacement of its managed path. */
    private suspend fun verified(input: FileInputStream, file: File): Boolean = try {
        currentCoroutineContext().ensureActive()
        val held = Os.fstat(input.fd)
        val current = Os.stat(file.absolutePath)
        if (held.st_dev != current.st_dev || held.st_ino != current.st_ino || held.st_size != current.st_size ||
            held.st_size !in 1..40L * 1024 * 1024 || file.canonicalPath != proof.sourcePath) false
        else {
            input.channel.position(0L)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > 40L * 1024 * 1024) throw OcrOriginalSourceChangedException()
                digest.update(buffer, 0, count)
            }
            input.channel.position(0L)
            digest.digest().joinToString("") { "%02x".format(it) } == proof.sourceSha256
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}
