package com.mangalens.core.reader

import java.net.URI

/** Selected document identity; the managed rendered page remains the OCR source authority. */
data class ChapterDocumentSource(
    val uri: String,
    val kind: String,
    val pageIndex: Int,
    val documentSha256: String,
    val archiveEntryName: String? = null,
    /** Observed from ContentResolver, never inferred from a successful permission request. */
    val persistedReadPermission: Boolean = false
) {
    fun validate(): ChapterDocumentSource = apply {
        require(uri.length in 1..32_768)
        val selected = URI(uri)
        require(selected.scheme in setOf("content", "file")) { "The original document URI is invalid" }
        require(if (selected.scheme == "content") !selected.authority.isNullOrBlank() else selected.path?.startsWith('/') == true)
        require(kind == "archive" || kind == "pdf")
        require(pageIndex in 0..999 && (kind != "pdf" || pageIndex < 300))
        require(documentSha256.matches(Regex("[0-9a-f]{64}")))
        if (kind == "archive") {
            val name = requireNotNull(archiveEntryName)
            require(name.length in 1..4096 && !name.startsWith('/') && '\\' !in name &&
                !Regex("^[A-Za-z]:").containsMatchIn(name) && name.split('/').none { it == ".." })
            require(name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp"))
        } else require(archiveEntryName == null)
    }
}
