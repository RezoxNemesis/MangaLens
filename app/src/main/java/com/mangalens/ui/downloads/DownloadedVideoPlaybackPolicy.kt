package com.mangalens.ui.downloads

import com.mangalens.download.DownloadEntity
import com.mangalens.download.DownloadState
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale

/** A saved output, deliberately separate from the adaptive cache's HTTP source. */
internal data class SavedDownloadedVideo(
    val downloadId: String,
    val uri: String,
    val mimeType: String,
    val bytes: Long
)

internal enum class SavedVideoReadOwnership { APP_OWNED, PERSISTED_READ, RUNTIME_MEDIA_READ, NONE }
internal data class SavedVideoReadEvidence(
    val mimeType: String?,
    val ownership: SavedVideoReadOwnership,
    val bytes: Long,
    val readNonempty: Boolean
)

internal enum class SavedVideoUnavailableReason(val message: String) {
    NOT_COMPLETED("This video download is not complete."),
    ADAPTIVE_SOURCE("This download uses the separate offline stream player."),
    MISSING_FILE("The saved video is missing. Download it again."),
    NOT_VIDEO("This saved download is not a video."),
    ACCESS_EXPIRED("Access to this saved video has expired. Choose it again in Watch or download it again."),
    MIME_CHANGED("The saved file is no longer the completed video. Download it again."),
    UNREADABLE_FILE("The saved video is empty, changed or unreadable. Download it again."),
    DOWNLOAD_CHANGED("This download changed while opening. Open its current saved file again.")
}
internal class SavedVideoUnavailableException(val reason: SavedVideoUnavailableReason) : IOException(reason.message)

/** Pure admission contract; Android supplies real MIME, durable access and first-byte evidence. */
internal object DownloadedVideoPlaybackPolicy {
    fun capture(row: DownloadEntity?): SavedDownloadedVideo {
        if (row == null || row.state != DownloadState.COMPLETED) refuse(SavedVideoUnavailableReason.NOT_COMPLETED)
        if (row.isAdaptive) refuse(SavedVideoUnavailableReason.ADAPTIVE_SOURCE)
        val mime = concreteVideoMime(row.mimeType) ?: refuse(SavedVideoUnavailableReason.NOT_VIDEO)
        val destination = row.destination
        if (row.bytesDownloaded <= 0L || destination == null || !savedUri(destination))
            refuse(SavedVideoUnavailableReason.MISSING_FILE)
        return SavedDownloadedVideo(row.id, destination, mime, row.bytesDownloaded)
    }

    fun confirm(saved: SavedDownloadedVideo, fresh: DownloadEntity?, read: SavedVideoReadEvidence): SavedDownloadedVideo {
        val current = try { capture(fresh) } catch (_: SavedVideoUnavailableException) { null }
        if (current != saved) refuse(SavedVideoUnavailableReason.DOWNLOAD_CHANGED)
        if (read.ownership == SavedVideoReadOwnership.NONE) refuse(SavedVideoUnavailableReason.ACCESS_EXPIRED)
        if (concreteVideoMime(read.mimeType) != saved.mimeType) refuse(SavedVideoUnavailableReason.MIME_CHANGED)
        if (!read.readNonempty || read.bytes < -1L || (read.bytes >= 0L && read.bytes != saved.bytes))
            refuse(SavedVideoUnavailableReason.UNREADABLE_FILE)
        return saved
    }

    fun isManagedFile(file: File, roots: List<File>): Boolean = runCatching {
        val actual = file.canonicalFile
        actual.isFile && actual.canRead() && roots.any { root ->
            actual.path.startsWith(root.canonicalPath + File.separator)
        }
    }.getOrDefault(false)

    private fun concreteVideoMime(value: String?): String? = value?.lowercase(Locale.ROOT)?.takeIf {
        it.length <= 256 && it.matches(Regex("video/[a-z0-9][a-z0-9!#$&^_.+\\-]{0,126}"))
    }

    private fun savedUri(value: String): Boolean = runCatching {
        if (value.isBlank() || value.length > 16_384 || value.any { it.code < 32 || it.code == 127 }) return@runCatching false
        val parsed = URI(value)
        if (parsed.isOpaque || parsed.userInfo != null || parsed.rawFragment != null ||
            parsed.path.isNullOrEmpty() || !parsed.path.startsWith('/') ||
            parsed.path.any { it.code < 32 || it.code == 127 }) return@runCatching false
        when (parsed.scheme) {
            "content" -> parsed.rawAuthority?.matches(Regex("[A-Za-z0-9_.-]+")) == true
            "file" -> parsed.rawAuthority.isNullOrEmpty() && parsed.rawQuery == null
            else -> false
        }
    }.getOrDefault(false)

    private fun refuse(reason: SavedVideoUnavailableReason): Nothing = throw SavedVideoUnavailableException(reason)
}
