package com.mangalens.ui.downloads

import com.mangalens.download.DownloadEntity
import com.mangalens.download.DownloadState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadedVideoPlaybackPolicyTest {
    private val uri = "content://com.mangalens.downloads/download_fallback/owned/video.mp4"
    private fun row() = DownloadEntity("owned", "https://fixture.invalid/expired.mp4", "Owned video", "video/mp4",
        destination = uri, bytesDownloaded = 17L, totalBytes = 17L, state = DownloadState.COMPLETED)
    private fun access(mime: String? = "video/mp4", ownership: SavedVideoReadOwnership = SavedVideoReadOwnership.APP_OWNED,
        bytes: Long = 17L, nonempty: Boolean = true) = SavedVideoReadEvidence(mime, ownership, bytes, nonempty)
    private fun denied(reason: SavedVideoUnavailableReason, block: () -> Unit) {
        try { block(); fail("Expected saved-video refusal: $reason") }
        catch (failure: SavedVideoUnavailableException) { assertEquals(reason, failure.reason) }
    }

    @Test fun completedVideoUsesItsExactSavedUriAndNeverItsExpiredSourceUrl() {
        val saved = DownloadedVideoPlaybackPolicy.capture(row())
        assertEquals(uri, saved.uri); assertEquals("video/mp4", saved.mimeType); assertEquals("owned", saved.downloadId)
        assertNotEquals(row().sourceUrl, saved.uri)
        assertEquals(saved, DownloadedVideoPlaybackPolicy.confirm(saved, row(), access()))
    }

    @Test fun everyNoncompletedStateAndRemovedRowIsRejected() {
        for (state in DownloadState.entries.filter { it != DownloadState.COMPLETED })
            denied(SavedVideoUnavailableReason.NOT_COMPLETED) { DownloadedVideoPlaybackPolicy.capture(row().copy(state = state)) }
        denied(SavedVideoUnavailableReason.NOT_COMPLETED) { DownloadedVideoPlaybackPolicy.capture(null) }
    }

    @Test fun missingDestinationOrUnpublishedBytesNeverFallsBackToTheOriginalUrl() {
        for (destination in listOf(null, "", " ")) denied(SavedVideoUnavailableReason.MISSING_FILE) {
            DownloadedVideoPlaybackPolicy.capture(row().copy(destination = destination))
        }
        for (bytes in listOf(0L, -1L)) denied(SavedVideoUnavailableReason.MISSING_FILE) {
            DownloadedVideoPlaybackPolicy.capture(row().copy(bytesDownloaded = bytes))
        }
    }

    @Test fun committedMimeMustBeConcreteVideoAndProviderMimeMustMatchItExactly() {
        for (mime in listOf("image/png", "audio/mp4", "application/octet-stream", "video/*", "video/", "video/mp4\n"))
            denied(SavedVideoUnavailableReason.NOT_VIDEO) { DownloadedVideoPlaybackPolicy.capture(row().copy(mimeType = mime)) }
        val saved = DownloadedVideoPlaybackPolicy.capture(row())
        for (actual in listOf(null, "image/png", "video/webm", "application/octet-stream"))
            denied(SavedVideoUnavailableReason.MIME_CHANGED) { DownloadedVideoPlaybackPolicy.confirm(saved, row(), access(mime = actual)) }
    }

    @Test fun originalWebUrlsOpaqueUrisFragmentsAndUserInfoAreNotSavedFileTargets() {
        for (destination in listOf("https://fixture.invalid/expired.mp4", "http://fixture.invalid/video.mp4", "content:video",
            "content://user@provider/video.mp4", "content://provider/video.mp4#replacement", "file://remote/video.mp4", "content://provider/video%00.mp4"))
            denied(SavedVideoUnavailableReason.MISSING_FILE) { DownloadedVideoPlaybackPolicy.capture(row().copy(destination = destination)) }
    }

    @Test fun ownOutputAndExactPersistedReadAreAllowedButTransientOrRevokedAccessIsNot() {
        val saved = DownloadedVideoPlaybackPolicy.capture(row())
        assertEquals(saved, DownloadedVideoPlaybackPolicy.confirm(saved, row(), access(ownership = SavedVideoReadOwnership.APP_OWNED)))
        assertEquals(saved, DownloadedVideoPlaybackPolicy.confirm(saved, row(), access(ownership = SavedVideoReadOwnership.PERSISTED_READ)))
        denied(SavedVideoUnavailableReason.ACCESS_EXPIRED) {
            DownloadedVideoPlaybackPolicy.confirm(saved, row(), access(ownership = SavedVideoReadOwnership.NONE))
        }
    }

    @Test fun emptyUnreadableOrChangedLengthDoesNotPassAdmission() {
        val saved = DownloadedVideoPlaybackPolicy.capture(row())
        denied(SavedVideoUnavailableReason.UNREADABLE_FILE) { DownloadedVideoPlaybackPolicy.confirm(saved, row(), access(nonempty = false)) }
        for (bytes in listOf(0L, 16L, 18L)) denied(SavedVideoUnavailableReason.UNREADABLE_FILE) {
            DownloadedVideoPlaybackPolicy.confirm(saved, row(), access(bytes = bytes))
        }
        // Streaming document providers may have unknown length; a real first-byte read remains required.
        assertEquals(saved, DownloadedVideoPlaybackPolicy.confirm(saved, row(), access(bytes = -1L)))
    }

    @Test fun aColdRestoredRowRetainsTheSameSavedTargetWithoutAmbientSourceResolution() {
        val first = row(); val restored = first.copy()
        assertEquals(DownloadedVideoPlaybackPolicy.capture(first), DownloadedVideoPlaybackPolicy.capture(restored))
        val saved = DownloadedVideoPlaybackPolicy.capture(first)
        assertEquals(saved, DownloadedVideoPlaybackPolicy.confirm(saved, restored, access()))
    }

    @Test fun aRemovedPausedOrReplacedRowCannotPublishAfterTheReadCompletes() {
        val saved = DownloadedVideoPlaybackPolicy.capture(row())
        val replacements = listOf(null, row().copy(state = DownloadState.PAUSED), row().copy(id = "another"),
            row().copy(destination = "content://com.mangalens.downloads/download_fallback/another/video.mp4"),
            row().copy(mimeType = "video/webm"), row().copy(bytesDownloaded = 18L))
        for (fresh in replacements) denied(SavedVideoUnavailableReason.DOWNLOAD_CHANGED) {
            DownloadedVideoPlaybackPolicy.confirm(saved, fresh, access())
        }
    }

    @Test fun adaptiveRowsNeverEnterTheSavedFileCallbackEvenWithAVideoMimeOrDestination() {
        for (source in listOf("https://fixture.invalid/playlist.m3u8", "https://fixture.invalid/manifest.mpd"))
            denied(SavedVideoUnavailableReason.ADAPTIVE_SOURCE) { DownloadedVideoPlaybackPolicy.capture(row().copy(sourceUrl = source)) }
    }

    @Test fun legacyFileOwnershipUsesCanonicalDirectoryBoundariesAndRejectsEscapingSymlinks() {
        val directory = Files.createTempDirectory("mangalens-saved-video-policy").toFile()
        val managed = File(directory, "downloads").apply { mkdirs() }
        val neighbour = File(directory, "downloads-neighbour").apply { mkdirs() }
        val saved = File(managed, "nested/video.mp4").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val outside = File(neighbour, "video.mp4").apply { writeBytes(byteArrayOf(2)) }
        try {
            assertTrue(DownloadedVideoPlaybackPolicy.isManagedFile(saved, listOf(managed)))
            assertFalse(DownloadedVideoPlaybackPolicy.isManagedFile(outside, listOf(managed)))
            assertFalse(DownloadedVideoPlaybackPolicy.isManagedFile(File(managed, "../downloads-neighbour/video.mp4"), listOf(managed)))
            val link = File(managed, "escape.mp4")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertFalse(DownloadedVideoPlaybackPolicy.isManagedFile(link, listOf(managed)))
        } finally {
            File(managed, "escape.mp4").delete(); saved.delete(); saved.parentFile.delete(); managed.delete()
            outside.delete(); neighbour.delete(); directory.delete()
        }
    }
}
