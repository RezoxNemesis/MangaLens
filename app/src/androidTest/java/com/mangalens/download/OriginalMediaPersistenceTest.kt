package com.mangalens.download

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File

@RunWith(AndroidJUnit4::class)
class OriginalMediaPersistenceTest {
    @Test fun validLargeSplitReceiptRoundTripsAndTruncationFailsTransferAdmission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DownloadRequestContextStore(context)
        val id = "original-large-context-${UUID.randomUUID()}"
        val names = listOf("Accept", "Accept-Language", "Cookie", "Origin", "Referer", "User-Agent")
        val headers = names.associateWith { "x".repeat(16_384) }
        val pair = ResolvedMediaLink("https://video.fixture.invalid/original", "video/webm", "Youtube",
            headers = headers, audioUrl = "https://audio.fixture.invalid/original", audioHeaders = headers,
            requestedHeight = DownloadQuality.BEST.height, expectedDurationUs = 8_000_000)
        val receipt = File(context.filesDir, "download_request_context/$id.json")
        try {
            store.write(id, pair)
            assertTrue("Ordinary allowed headers can exceed the old reader cap", receipt.length() > 128 * 1024)
            assertEquals(pair, store.readForTransfer(id, pair.url, required = true))
            receipt.writeText("{")
            assertNull(store.readMedia(id))
            val failure = runCatching { store.readForTransfer(id, pair.url, required = true) }.exceptionOrNull()
            assertTrue("Corruption must not erase the selected separate audio", failure is IllegalStateException)
            assertFalse(failure!!.message.orEmpty().contains("https://"))
            assertFalse(failure.message.orEmpty().contains("Cookie"))
            receipt.writeText("{\"url\":\"${pair.url}\",\"headers\":{}}")
            assertTrue("Valid JSON without a declared audio source shape remains unreadable",
                runCatching { store.readForTransfer(id, pair.url, required = true) }.exceptionOrNull() is IllegalStateException)
        } finally { store.remove(id) }
    }

    @Test fun missingResolvedReceiptsFailAndOversizeWritesKeepThePriorAtomicSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DownloadRequestContextStore(context)
        val id = "original-missing-context-${UUID.randomUUID()}"
        val pair = ResolvedMediaLink("https://video.fixture.invalid/original", "video/webm", "Youtube",
            audioUrl = "https://audio.fixture.invalid/original")
        try {
            assertTrue(runCatching { store.readForTransfer(id, pair.url, required = true) }.exceptionOrNull() is IllegalStateException)
            assertNull(store.readForTransfer(id, pair.url, required = false))
            store.write(id, pair)
            assertTrue(runCatching { store.write(id, pair.copy(title = "x".repeat(MAX_DOWNLOAD_REQUEST_CONTEXT_BYTES + 1))) }
                .exceptionOrNull() is IllegalStateException)
            assertEquals("Rejected replacements must retain the complete old tuple", pair,
                store.readForTransfer(id, pair.url, required = true))
        } finally { store.remove(id) }
    }

    @Test fun partialSourceRefreshCannotAttachTheOldAudioOrFactsToNewVideo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()
        val dao = database.downloads()
        val store = DownloadRequestContextStore(context)
        val id = "original-refresh-${UUID.randomUUID()}"
        val old = ResolvedMediaLink("https://old.fixture.invalid/video", "video/webm", "Youtube",
            audioUrl = "https://old.fixture.invalid/audio", expectedDurationUs = 8_000_000,
            originalSelection = OriginalMediaSelection(videoCodec = "vp9", audioCodec = "opus"))
        val replacement = ResolvedMediaLink("https://new.fixture.invalid/video", "video/mp4", "generic",
            expectedDurationUs = 3_000_000)
        try {
            store.write(id, old)
            dao.upsert(DownloadEntity(id, old.url, "Old fixture", "video/webm"))
            assertEquals(1, dao.refreshSource(id, replacement.url, "video/mp4", "New fixture", "generic",
                "https://new.fixture.invalid/page", null, "fixture refresh"))
            val source = dao.get(id)!!.sourceUrl
            val failure = runCatching { requireBoundMediaSource(source, store.readMedia(id)) }.exceptionOrNull()
            assertTrue("A partially committed refresh must fail source admission", failure is IllegalStateException)
            assertNull(dao.get(id)!!.destination)
            assertEquals(DownloadState.QUEUED, dao.get(id)!!.state)
            store.write(id, replacement)
            assertEquals(replacement, requireBoundMediaSource(source, store.readMedia(id)))
        } finally { store.remove(id); database.close() }
    }

    @Test fun completePairPersistsOriginalFactsAndAnUnrelatedReplacementClearsThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DownloadRequestContextStore(context)
        val id = "original-context-${UUID.randomUUID()}"
        val first = ResolvedMediaLink("https://video.fixture.invalid/original", "video/webm", "Youtube",
            detectedHeight = 1080, sourcePageUrl = "https://fixture.invalid/source-one",
            headers = mapOf("Cookie" to "synthetic-video=one"), audioUrl = "https://audio.fixture.invalid/original",
            audioHeaders = mapOf("Cookie" to "synthetic-audio=two"), requestedHeight = DownloadQuality.BEST.height,
            expectedDurationUs = 8_000_000, originalSelection = OriginalMediaSelection("vp9-high", "opus",
                "vp9", "opus", 1080, "web_safari"))
        try {
            store.write(id, first)
            assertEquals(first, store.readMedia(id))
            val replacement = ResolvedMediaLink("https://new.fixture.invalid/other.mp4", "video/mp4", "generic",
                sourcePageUrl = "https://fixture.invalid/source-two", expectedDurationUs = 3_000_000)
            store.write(id, replacement)
            val restored = store.readMedia(id)!!
            assertEquals(replacement, restored)
            assertNull(restored.originalSelection)
            assertNull(restored.audioUrl)
            assertTrue(restored.audioHeaders.isEmpty())
            assertTrue(restored.headers.isEmpty())
        } finally { store.remove(id) }
    }

    @Test fun actualContainerMimeCommitsWithTheDestinationAndStoppedTasksRejectBoth() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()
        val dao = database.downloads()
        val id = "original-publication-${UUID.randomUUID()}"
        val item = DownloadEntity(id, "https://fixture.invalid/original", "Original fixture", "video/mp4")
        try {
            for (state in listOf(DownloadState.PAUSED, DownloadState.CANCELLED, DownloadState.COMPLETED)) {
                dao.upsert(item.copy(state = state))
                assertEquals(0, dao.completeIfActive(id, "content://fixture/original.webm", 42, "video/webm"))
                assertEquals(state, dao.get(id)!!.state)
                assertEquals("video/mp4", dao.get(id)!!.mimeType)
                assertNull(dao.get(id)!!.destination)
            }
            dao.upsert(item)
            assertEquals(1, dao.completeIfActive(id, "content://fixture/original.mkv", 84, "video/x-matroska"))
            val completed = dao.get(id)!!
            assertEquals(DownloadState.COMPLETED, completed.state)
            assertEquals("content://fixture/original.mkv", completed.destination)
            assertEquals("video/x-matroska", completed.mimeType)
            assertEquals(84L, completed.bytesDownloaded)
            assertEquals(84L, completed.totalBytes)
            // The defaulted fourth argument preserves existing callers' MIME.
            dao.upsert(item)
            assertEquals(1, dao.completeIfActive(id, "content://fixture/legacy.mp4", 21))
            assertEquals("video/mp4", dao.get(id)!!.mimeType)
        } finally { database.close() }
    }
}
