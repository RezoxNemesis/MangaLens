package com.mangalens.core.translation

import com.mangalens.core.compute.NativeComputePrecondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** Captured feature authority, rechecked once at actual native entry rather than each queue poll. */
internal class ChapterNativeEntryGuard(
    private val store: ChapterTranslationStore,
    private val captured: ChapterTranslationTask,
    private val page: ChapterTranslationPage,
    private val allowOwner: suspend (ChapterTranslationTask) -> Boolean,
    private val hashSource: (File) -> String = ChapterTranslationStore::sha256
) {
    private data class SourceIdentity(val path: String, val fileKey: Any?, val size: Long, val modified: FileTime)
    private val sourceRows = captured.pages.map { ReaderTranslationSource(it.index, it.sourcePath, it.sourceSha256) }
    private val requestedPages = captured.requestedPages?.toList()
    private val expectedSourceSha256 = requireNotNull(page.sourceSha256) { "The captured source page has not been verified." }
        .also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private val source = store.sourceFile(page) ?: error("The captured source page is unavailable. Retry its source.")
    private val sourceIdentity = identity(source)
    val precondition = NativeComputePrecondition(::validate)

    private fun current(): ChapterTranslationTask {
        val value = store.get(captured.id)
        if (value == null || !store.isCurrent(captured.id, captured.generation) ||
            value.generation != captured.generation || value.ownerRequestId != captured.ownerRequestId ||
            value.chapterId != captured.chapterId || value.config != captured.config || value.requestedPages != requestedPages ||
            value.status !in setOf(ChapterTranslationStatus.QUEUED, ChapterTranslationStatus.RUNNING) ||
            value.pages.map { ReaderTranslationSource(it.index, it.sourcePath, it.sourceSha256) } != sourceRows ||
            value.pages.singleOrNull { it.index == page.index }?.let {
                it.sourcePath == page.sourcePath && it.sourceSha256 == expectedSourceSha256 &&
                    it.status == ChapterTranslationPageStatus.RUNNING
            } != true)
            throw CancellationException("The captured chapter work was paused, replaced or changed before native entry.")
        return value
    }

    private suspend fun ownerCurrent() {
        currentCoroutineContext().ensureActive()
        val task = current()
        if (!allowOwner(task)) throw CancellationException("The chapter owner stopped this captured work before native entry.")
        currentCoroutineContext().ensureActive()
        current()
    }

    private suspend fun validate(waited: Boolean) {
        ownerCurrent()
        val before = identity(source)
        if (waited || before != sourceIdentity) {
            val hash = withContext(Dispatchers.IO) { hashSource(source) }
            check(hash == expectedSourceSha256 && identity(source) == before) {
                "Source page changed while waiting for native compute. Resume to translate the current page."
            }
        }
        ownerCurrent()
        check(identity(source) == before) { "Source page changed before native entry. Resume to translate the current page." }
    }

    private fun identity(file: File): SourceIdentity {
        val managed = store.sourceFile(page) ?: error("The captured source page is unavailable. Retry its source.")
        check(managed.canonicalPath == file.canonicalPath) { "The captured source path changed before native entry." }
        val attributes = Files.readAttributes(managed.toPath(), BasicFileAttributes::class.java)
        check(attributes.isRegularFile && attributes.size() in 1..MAX_SOURCE_BYTES) { "The captured source page is unavailable or too large." }
        return SourceIdentity(managed.canonicalPath, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime())
    }

    private companion object { const val MAX_SOURCE_BYTES = 40L * 1024L * 1024L }
}
