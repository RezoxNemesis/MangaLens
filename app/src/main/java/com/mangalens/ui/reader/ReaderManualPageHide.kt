package com.mangalens.ui.reader

import com.mangalens.core.reader.ChapterPage

/** Session-local display choice. This identity never authorizes a source read or mutation. */
internal class ReaderManualPageHide private constructor(
    private val chapterId: String,
    private val captured: ChapterPage
) {
    fun matches(currentChapter: String, page: ChapterPage): Boolean = currentChapter == chapterId &&
        page.index == captured.index && page.sourceUrl == captured.sourceUrl && page.localPath == captured.localPath &&
        page.contentRevision == captured.contentRevision && page.error == null &&
        // Older metadata without a revision gets no authority across a recreated page object.
        (captured.contentRevision != null || page === captured)

    companion object {
        const val MAX_PAGES = 2_000
        fun capture(chapterId: String, page: ChapterPage): ReaderManualPageHide? =
            if (chapterId.isBlank() || page.index < 0 || page.error != null ||
                page.localPath.isNullOrBlank() || page.sourceUrl.isBlank()) null
            else ReaderManualPageHide(chapterId, page)
    }
}
