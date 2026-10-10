package com.mangalens.orez.agent

import android.content.Context
import com.mangalens.core.reader.ChapterLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** One process-wide producer slot remains occupied until actual blocking IO and close return. */
internal object OrezChapterAcquisitionOwnership {
    private val slot = OrezChapterAcquisitionSlot()
    suspend fun <T> withFiles(files: File, action: suspend (OrezAcquisitionPrivateOwner) -> T): T = slot.withFiles(files, action)
}
internal class OrezChapterAcquisitionSlot {
    private val producer = Mutex()
    private var failedOwner: OrezAcquisitionPrivateOwner? = null
    suspend fun <T> withFiles(files: File, action: suspend (OrezAcquisitionPrivateOwner) -> T): T = producer.withLock {
        check(failedOwner == null) { "An earlier native private close is unproven. Restart MangaLens before another chapter acquisition." }
        val owner = OrezAcquisitionPrivateOwner(files)
        try { action(owner) }
        finally {
            try { owner.finish() }
            catch (failure: Throwable) { failedOwner = owner; throw failure }
        }
    }
}

internal object OrezChapterAcquisitionCapture {
    suspend fun capture(context: Context, input: String, selectedId: String?): OrezChapterAcquisitionScope? {
        OrezNextChapterRequest.directUrl(input)?.let { return OrezChapterAcquisitionScope(it).validated() }
        if (!OrezNextChapterRequest.isRequested(input)) return null
        require(selectedId?.matches(Regex("[a-f0-9]{32}")) == true) { "Open one saved chapter before requesting its next chapter." }
        val app = context.applicationContext
        return withContext(Dispatchers.IO) { OrezChapterAcquisitionOwnership.withFiles(app.filesDir) { owner ->
            withTimeoutOrNull(90_000L) {
            val library = ChapterLibrary(app.filesDir, OrezOwnedChapterJournalIo(owner))
            val selected = requireNotNull(library.findMetadata(selectedId!!)) { "The selected chapter is no longer saved." }
            OrezNextChapterPolicy.chapterUrl(selected.sourceUrl)
            val budget = OrezChapterSourceReadBudget(OrezNextChapterPolicy.MAX_CHAPTER_BYTES * 2)
            val first = owner.inspect(selected, budget)
            val network = OrezChapterAcquisitionNetwork(owner)
            try {
                val source = network.readHtml(selected.sourceUrl)
                OrezNextChapterPolicy.requireChapterDocument(source.url, selected.sourceUrl)
                val target = OrezNextChapterPolicy.next(source.html, source.url)
                val current = requireNotNull(library.findMetadata(selected.id)) { "The selected chapter was removed during capture." }
                require(current.sourceUrl == selected.sourceUrl) { "The selected chapter source changed during capture." }
                val refreshed = owner.inspect(current, budget)
                require(refreshed.chapter.sourceFingerprint == first.chapter.sourceFingerprint && refreshed.chapter.pageCount == first.chapter.pageCount) { "Saved originals changed during next-chapter capture." }
                owner.requireCurrent(refreshed)
                OrezChapterAcquisitionScope(target, OrezNextChapterScope(selected.id, first.chapter.sourceFingerprint, first.chapter.pageCount,
                    selected.sourceUrl, target, OrezNextChapterPolicy.relation(selected.sourceUrl, target), source.sha256)).validated()
            } finally { network.client.connectionPool.evictAll() }
            } ?: error("Next-chapter capture timed out after actual request cleanup. Retry its public source in Reader.")
        } }
    }
}
