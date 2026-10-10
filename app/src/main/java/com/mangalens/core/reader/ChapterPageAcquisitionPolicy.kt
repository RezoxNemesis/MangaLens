package com.mangalens.core.reader

/** Error rows retain the actual catalog ordinal and source, never original-byte authority. */
object ChapterPageAcquisitionPolicy {
    const val PENDING = "This page has not been acquired yet. Retry this page."
    const val FAILURE = "This page could not be acquired. Retry this page or open its source in Web."
    fun failure(index: Int, source: String) = ChapterPage(index, source, error = FAILURE)
    fun summary(pages: List<ChapterPage>): String? {
        val failed = pages.count { it.localPath == null || it.error != null }
        return if (failed == 0) null else "$failed of ${pages.size} chapter pages are unavailable. Retry an affected page; acquired pages are retained."
    }
}

/** Encoded-size reservation only. This carries no original digest, bytes, or Reader/editor proof. */
data class ChapterAcquisitionBudgetPage(
    val index: Int,
    val sourceUrl: String,
    val fileName: String,
    val promotion: com.mangalens.core.acquisition.ChapterImagePromotion? = null
)
