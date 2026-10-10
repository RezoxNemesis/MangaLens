package com.mangalens.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal interface FailedDownloadPartialRows {
    suspend fun get(id: String): DownloadEntity?
    suspend fun claim(captured: DownloadEntity): Boolean
    suspend fun complete(captured: DownloadEntity): Boolean
}

data class FailedDownloadPartialResult(val filesRemoved: Int, val bytesRemoved: Long)

internal class FailedDownloadPartialCleaner(
    private val root: File,
    private val rows: FailedDownloadPartialRows,
    private val allWorkFinished: suspend (String) -> Boolean,
    private val protectedContent: (DownloadEntity, List<File>) -> Boolean,
    private val stopClaimedRequest: suspend (String) -> Unit = {}
) {
    suspend fun clean(requested: DownloadEntity): FailedDownloadPartialResult = DownloadIdMutationFences.withId(requested.id) {
        currentCoroutineContext().ensureActive()
        val id = requested.id
        check(rows.get(id) == requested) { "This transfer changed after confirmation. Review its current row before cleanup." }
        val captured = FailedDownloadPartialPolicy.capture(requested)
        if (captured.state == DownloadState.FAILED) {
            check(rows.claim(captured)) { "This transfer changed. No partial files were removed." }
        }
        val claimed = rows.get(id) ?: error("This transfer no longer exists.")
        check(claimed == captured.copy(state = DownloadState.CANCELLED, stage = FailedDownloadPartialPolicy.PENDING,
            error = "Stopped for partial cleanup")) { "This transfer changed. No partial files were removed." }
        stopClaimedRequest(id)
        if (!allWorkFinished(id)) throw DownloadFilesBusyException()
        val owner = DownloadPrivateFileOwner(root, id)
        if (DownloadPrivateFileOwners.hasOwners(owner)) throw DownloadPrivateFileOwners.ownershipFailure(owner)
        val files = FailedDownloadPartialFiles.plan(root, id)
        check(!protectedContent(captured, files)) { "This transfer is referenced by video history or favourites. Its files were retained." }
        currentCoroutineContext().ensureActive()
        check(rows.get(id) == claimed) { "This transfer changed. No partial files were removed." }
        var removed = 0
        var bytes = 0L
        for (file in files) {
            // Owned mutations remain fenced until this actual IO returns, even if the caller retires.
            val size = file.length()
            check(file.delete()) { "Some partial files could not be removed. Retry cleanup after they are released." }
            removed++; bytes = Math.addExact(bytes, size)
        }
        check(rows.complete(claimed)) { "Partial files were removed but the transfer status could not be updated." }
        FailedDownloadPartialResult(removed, bytes)
    }
}
