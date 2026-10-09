package com.mangalens.core.reader

data class ChapterPage(
    val index: Int,
    val sourceUrl: String,
    val localPath: String? = null,
    val error: String? = null,
    /** Verified SHA-256 plus cache incarnation; presentation only, never durable source authority. */
    val contentRevision: String? = null,
    val documentSource: ChapterDocumentSource? = null
)
