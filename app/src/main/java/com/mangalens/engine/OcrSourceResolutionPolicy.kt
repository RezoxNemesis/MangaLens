package com.mangalens.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

/** Immutable source and owner identity. A matching filename or sample factor is insufficient. */
internal data class OcrSourceResolutionProof(val taskId: String, val generation: String, val chapterId: String,
    val pageIndex: Int, val sourcePath: String, val sourceSha256: String,
    val originalWidth: Int, val originalHeight: Int, val decodedWidth: Int, val decodedHeight: Int)
internal data class OcrSourceResolutionRequest(val crop: OcrBox, val decodedWidth: Int, val decodedHeight: Int,
    val maxScale: Float = 3f)
internal data class OcrSourceResolutionPlan(val proof: OcrSourceResolutionProof, val sourceCrop: OcrBox,
    val decodeSample: Int, val expectedDecodeWidth: Int, val expectedDecodeHeight: Int) {
    fun mapToPage(box: OcrBox, actualWidth: Int, actualHeight: Int): OcrBox? {
        if (!box.valid || actualWidth <= 0 || actualHeight <= 0) return null
        val clipped = OcrBox(box.left.coerceIn(0f, actualWidth.toFloat()), box.top.coerceIn(0f, actualHeight.toFloat()),
            box.right.coerceIn(0f, actualWidth.toFloat()), box.bottom.coerceIn(0f, actualHeight.toFloat()))
        if (!clipped.valid) return null
        val x = proof.decodedWidth.toFloat() / proof.originalWidth
        val y = proof.decodedHeight.toFloat() / proof.originalHeight
        return OcrBox((sourceCrop.left + clipped.left * sourceCrop.width / actualWidth) * x,
            (sourceCrop.top + clipped.top * sourceCrop.height / actualHeight) * y,
            (sourceCrop.left + clipped.right * sourceCrop.width / actualWidth) * x,
            (sourceCrop.top + clipped.bottom * sourceCrop.height / actualHeight) * y)
    }

    fun textSizeToPage(size: Float, actualWidth: Int, actualHeight: Int): Float = size * minOf(
        sourceCrop.width / actualWidth * proof.decodedWidth / proof.originalWidth,
        sourceCrop.height / actualHeight * proof.decodedHeight / proof.originalHeight)
}

/** Integer region in original-file coordinates; caps apply before the decoder allocates a Bitmap. */
internal fun planOriginalSourceCrop(proof: OcrSourceResolutionProof, request: OcrSourceResolutionRequest): OcrSourceResolutionPlan? {
    if (!proof.taskId.matches(OWNER_ID) || !proof.generation.matches(OWNER_ID) || !proof.chapterId.matches(OWNER_ID) ||
        proof.pageIndex < 0 || !proof.sourcePath.startsWith('/') || !proof.sourceSha256.matches(SOURCE_HASH) ||
        proof.originalWidth <= 0 || proof.originalHeight <= 0 ||
        proof.originalWidth.toLong() * proof.originalHeight > 100_000_000L ||
        proof.decodedWidth !in 1..proof.originalWidth || proof.decodedHeight !in 1..proof.originalHeight ||
        request.decodedWidth != proof.decodedWidth || request.decodedHeight != proof.decodedHeight ||
        !request.maxScale.isFinite() || request.maxScale !in 1f..3f) return null
    if (maxOf(proof.originalWidth.toFloat() / proof.decodedWidth, proof.originalHeight.toFloat() / proof.decodedHeight) <= 1.1f) return null
    val crop = request.crop
    if (!crop.valid || crop.left < 0f || crop.top < 0f || crop.right > proof.decodedWidth || crop.bottom > proof.decodedHeight) return null
    val x = proof.originalWidth.toDouble() / proof.decodedWidth
    val y = proof.originalHeight.toDouble() / proof.decodedHeight
    val source = OcrBox(floor(crop.left * x).toFloat().coerceAtLeast(0f), floor(crop.top * y).toFloat().coerceAtLeast(0f),
        ceil(crop.right * x).toFloat().coerceAtMost(proof.originalWidth.toFloat()),
        ceil(crop.bottom * y).toFloat().coerceAtMost(proof.originalHeight.toFloat()))
    if (!source.valid) return null
    var sample = 1
    fun sampled(dimension: Float): Int = ((dimension.toLong() + sample - 1) / sample).toInt()
    while (sampled(source.width) > 1280 || sampled(source.height) > 1280 ||
        sampled(source.width).toLong() * sampled(source.height) > 1_000_000L) sample *= 2
    return OcrSourceResolutionPlan(proof, source, sample, sampled(source.width), sampled(source.height))
}

private val OWNER_ID = Regex("[a-f0-9]{32}")
private val SOURCE_HASH = Regex("[a-f0-9]{64}")
internal fun originalPageBoxToTile(page: OcrBox, tileTop: Int, width: Int, height: Int): OcrBox? {
    if (!page.valid || tileTop < 0 || width <= 0 || height <= 0) return null
    return OcrBox(page.left.coerceIn(0f, width.toFloat()), (page.top - tileTop).coerceIn(0f, height.toFloat()),
        page.right.coerceIn(0f, width.toFloat()), (page.bottom - tileTop).coerceIn(0f, height.toFloat())).takeIf { it.valid }
}
internal class OcrOriginalSourceChangedException : IllegalStateException("Original OCR source changed.")
internal class OcrOriginalCropLease<T>(val resource: T, val width: Int, val height: Int,
    val sourceStillVerified: suspend () -> Boolean, val release: () -> Unit)

/** Prompt cancellation can discard an IO result; that result still has an owner which must release it. */
internal suspend fun <R> acquireOriginalOcrCropOnIo(open: suspend () -> OcrOriginalCropLease<R>?): OcrOriginalCropLease<R>? {
    var acquired: OcrOriginalCropLease<R>? = null
    var handedOff = false
    try {
        val result = withContext(Dispatchers.IO) { open().also { acquired = it } }
        handedOff = true
        return result
    } finally { if (!handedOff) acquired?.release() }
}

/** The consume callback must retain the existing real native-completion barrier. */
internal suspend fun <R, T> withQualifiedOriginalOcrCrop(proof: OcrSourceResolutionProof,
    request: OcrSourceResolutionRequest, checkpoint: suspend () -> OcrSourceResolutionProof?,
    decode: suspend (OcrSourceResolutionPlan) -> OcrOriginalCropLease<R>?,
    consume: suspend (OcrOriginalCropLease<R>, OcrSourceResolutionPlan) -> T): T? {
    suspend fun checkOwner() {
        currentCoroutineContext().ensureActive()
        if (checkpoint() != proof) throw CancellationException("Original OCR task or source identity was replaced.")
    }
    checkOwner()
    val plan = planOriginalSourceCrop(proof, request) ?: return null
    val crop = decode(plan) ?: run { checkOwner(); return null }
    try {
        checkOwner()
        if (!crop.sourceStillVerified()) throw OcrOriginalSourceChangedException()
        checkOwner()
        check(crop.width > 0 && crop.height > 0 && crop.width <= 1280 && crop.height <= 1280 &&
            crop.width.toLong() * crop.height <= 1_000_000L) { "Original OCR crop exceeds its bounded decoder input." }
        val result = consume(crop, plan)
        checkOwner()
        if (!crop.sourceStillVerified()) throw OcrOriginalSourceChangedException()
        checkOwner()
        return result
    } finally { crop.release() }
}
