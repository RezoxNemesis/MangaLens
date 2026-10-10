package com.mangalens.core.reader

data class ChapterPage(
    val index: Int,
    val sourceUrl: String,
    val localPath: String? = null,
    val error: String? = null,
    /** Verified SHA-256 plus cache incarnation; presentation only, never durable source authority. */
    val contentRevision: String? = null,
    val documentSource: ChapterDocumentSource? = null,
    /** Reversible presentation hint bound to the acquired original bytes, never deletion authority. */
    val promotionHint: ChapterPromotionHint? = null
)

data class ChapterPromotionHint(val reason: com.mangalens.core.acquisition.ChapterImagePromotion, val sourceSha256: String) {
    init { require(sourceSha256.matches(Regex("[a-f0-9]{64}"))) }
    fun matches(page: ChapterPage): Boolean = page.localPath != null && page.contentRevision?.substringBefore(':') == sourceSha256
}
