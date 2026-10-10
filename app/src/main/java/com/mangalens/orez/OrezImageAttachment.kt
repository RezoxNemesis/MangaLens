package com.mangalens.orez

/** Text-only explicit source context. It contains no URI, credentials, image bytes or tool authority. */
data class OrezImageAttachment(
    val sourceSha256: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val region: OrezImageRegion,
    val recognizerScript: String,
    val originalOcr: String,
    val truncated: Boolean
) {
    fun validated(): OrezImageAttachment = apply {
        require(sourceSha256.matches(Regex("[a-f0-9]{64}")))
        require(imageWidth in 1..100_000 && imageHeight in 1..100_000)
        require(region.left >= 0 && region.top >= 0 && region.right <= imageWidth && region.bottom <= imageHeight)
        require(region.right > region.left && region.bottom > region.top)
        require(recognizerScript in setOf("LATIN", "DEVANAGARI", "CHINESE", "JAPANESE", "KOREAN"))
        require(originalOcr.isNotBlank() && originalOcr.length <= MAX_TEXT && originalOcr.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES)
    }
    companion object { const val MAX_TEXT = 4096; const val MAX_TEXT_BYTES = 16_384 }
}

data class OrezImageRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Native source ownership is separate from model-visible text and survives actual provider await. */
interface OrezImageSourceOwner {
    suspend fun isCurrent(attachment: OrezImageAttachment): Boolean
    suspend fun <T> withVerifiedSource(attachment: OrezImageAttachment, consume: suspend () -> T): T?
}

internal data class OrezImageCropRequest(val left: Int = 0, val top: Int = 0, val right: Int = 100, val bottom: Int = 100) {
    fun validated() = apply { require(left in 0..99 && top in 0..99 && right in 1..100 && bottom in 1..100 && right > left && bottom > top) }
}

internal class OrezImageCropPlan private constructor(val region: OrezImageRegion, val sample: Int,
    private val predictedWidth: Int, private val predictedHeight: Int) {
    fun accepts(width: Int, height: Int): Boolean = width in maxOf(1, (region.right-region.left)/sample)..predictedWidth &&
        height in maxOf(1, (region.bottom-region.top)/sample)..predictedHeight && maxOf(width,height) <= 1280 &&
        width.toLong()*height <= 1_000_000
    companion object {
        fun create(width: Int, height: Int, crop: OrezImageCropRequest): OrezImageCropPlan {
            require(width in 1..100_000 && height in 1..100_000); crop.validated()
            val r = OrezImageRegion((width.toLong()*crop.left/100).toInt(), (height.toLong()*crop.top/100).toInt(),
                ((width.toLong()*crop.right+99)/100).toInt().coerceAtMost(width), ((height.toLong()*crop.bottom+99)/100).toInt().coerceAtMost(height))
            val w=r.right-r.left; val h=r.bottom-r.top; require(w>0 && h>0)
            var sample=1
            fun rounded(v: Int) = ((v.toLong()+sample-1)/sample).toInt()
            while (maxOf(rounded(w),rounded(h))>1280 || rounded(w).toLong()*rounded(h)>1_000_000) sample*=2
            return OrezImageCropPlan(r,sample,rounded(w),rounded(h))
        }
    }
}
