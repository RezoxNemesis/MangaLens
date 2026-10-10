package com.mangalens.ui.video

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecentVideoHistoryTest {
    private fun entry(index: Int = 1, position: Long = 30_000, time: Long = index.toLong(), favorite: Boolean = false) =
        RecentVideoEntry(RecentVideoSource(RecentVideoKind.ONLINE, "https://example.test/watch/$index"), "Video $index",
            position, 120_000, true, time, favorite)

    @Test fun completePlaybackStartsAgainWhileAnUnfinishedMovieResumes() {
        assertEquals(30_000L, entry().resumePositionMs)
        assertTrue(entry(position = 119_000).watched)
        assertEquals(0L, entry(position = 119_000).resumePositionMs)
    }

    @Test fun liveAndUnseekableSourcesNeverInventAResumePoint() {
        assertEquals(0L, entry(position = 0).copy(seekable = false, durationMs = 0).validate().resumePositionMs)
        assertThrows(IllegalArgumentException::class.java) { entry().copy(seekable = false).validate() }
    }

    @Test fun anOldCapturedPositionCannotOverwriteANewerVisit() {
        val current = entry(position = 90_000, time = 50)
        assertEquals(listOf(current), RecentVideoHistoryPolicy.record(listOf(current), entry(time = 49)))
    }

    @Test fun anotherVisitPreservesAnExplicitFavorite() {
        val first = entry(favorite = true)
        val replay = RecentVideoHistoryPolicy.record(listOf(first), entry(position = 50_000, time = 4)).single()
        assertTrue(replay.favorite)
        assertEquals(50_000L, replay.positionMs)
    }

    @Test fun fullHistoryEvictsOldOrdinaryVisitsAndRetainsFavoriteFilesMetadata() {
        var all = listOf(entry(index = 1, favorite = true))
        (2..45).forEach { all = RecentVideoHistoryPolicy.record(all, entry(it)) }
        assertEquals(40, all.size)
        assertTrue(all.any { it.source.uri.endsWith("/1") && it.favorite })
        assertFalse(all.any { it.source.uri.endsWith("/2") })
        assertEquals("https://example.test/watch/45", all.first().source.uri)
    }

    @Test fun codecRestoresExactLocalUriPositionFavoriteAndSourcePageWithoutHeaders() {
        val local = RecentVideoEntry(RecentVideoSource(RecentVideoKind.LOCAL, "content://provider/document/42"),
            "Saved video", 17_000, 80_000, true, 100, true)
        val bytes = RecentVideoCodec.encode(listOf(local, entry()))
        val restored = RecentVideoCodec.decode(bytes)
        assertEquals(listOf(local, entry()), restored)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("headers"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("cookie", ignoreCase = true))
    }

    @Test fun historyRejectsUnexpectedPrivateNetworkAndOpaqueAuthority() {
        listOf("https://user:pass@example.test/watch", "javascript:alert(1)", "https://example.test/x#fragment", "https://example.test/\n").forEach {
            assertNull(RecentVideoSource.online(it))
        }
        listOf("https://example.test/video.mp4", "content://provider/a?redirect=file:///secret", "file://foreign/a.mp4").forEach {
            assertNull(RecentVideoSource.local(it))
        }
    }

    @Test fun duplicateAndAlteredJournalIdentitiesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { RecentVideoCodec.encode(listOf(entry(), entry())) }
        val bytes = RecentVideoCodec.encode(listOf(entry())).toString(Charsets.UTF_8)
            .replace(entry().key, "0".repeat(64)).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { RecentVideoCodec.decode(bytes) }
    }

    @Test fun aColdStoreRestoresCommittedVisitsWithoutReadingSavedVideoBytes() {
        val root = Files.createTempDirectory("recent-video-cold").toFile()
        try {
            val first = RecentVideoStore(root, FileIo, Dispatchers.Unconfined) { 100 }
            first.record(entry().source, 30_000, 120_000, true)
            first.favorite(entry().key, true)
            val cold = RecentVideoStore(root, FileIo, Dispatchers.Unconfined)
            assertFalse(cold.state.value.loading)
            assertEquals(30_000L, cold.state.value.entries.single().resumePositionMs)
            assertTrue(cold.state.value.entries.single().favorite)
            assertEquals(1, root.listFiles().orEmpty().size)
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptColdHistoryIsNotReplacedByANewVisit() {
        val root = Files.createTempDirectory("recent-video-corrupt").toFile()
        try {
            val original = "incomplete old journal".toByteArray()
            File(root, "history.json").writeBytes(original)
            val store = RecentVideoStore(root, FileIo, Dispatchers.Unconfined)
            store.record(entry().source, 20_000, 120_000, true)
            assertArrayEquals(original, File(root, "history.json").readBytes())
            assertNotNull(store.state.value.error)
        } finally { root.deleteRecursively() }
    }

    @Test fun failedHistoryWritesRetainPreviousCommittedProgressAndExposeAnError() {
        val root = Files.createTempDirectory("recent-video-write-failure").toFile()
        try {
            File(root, "history.json").writeBytes(RecentVideoCodec.encode(listOf(entry())))
            val io = object : RecentVideoJournalIo {
                override fun read(file: File) = FileIo.read(file)
                override fun write(file: File, bytes: ByteArray): Unit = throw java.io.IOException("controlled write failure")
            }
            val store = RecentVideoStore(root, io, Dispatchers.Unconfined) { 100 }
            store.record(entry().source, 90_000, 120_000, true)
            assertEquals(30_000L, store.state.value.entries.single().positionMs)
            assertEquals(30_000L, RecentVideoCodec.decode(File(root, "history.json").readBytes()).single().positionMs)
            assertNotNull(store.state.value.error)
        } finally { root.deleteRecursively() }
    }

    @Test fun removingHistoryMetadataLeavesAnUnrelatedSavedFileUntouched() {
        val root = Files.createTempDirectory("recent-video-remove").toFile()
        try {
            val saved = File(root, "saved.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val store = RecentVideoStore(root, FileIo, Dispatchers.Unconfined) { 100 }
            store.record(entry().source, 30_000, 120_000, true)
            store.remove(entry().key)
            assertTrue(store.state.value.entries.isEmpty())
            assertArrayEquals(byteArrayOf(1, 2, 3), saved.readBytes())
        } finally { root.deleteRecursively() }
    }

    private object FileIo : RecentVideoJournalIo {
        override fun read(file: File): ByteArray? = file.takeIf(File::isFile)?.readBytes()
        override fun write(file: File, bytes: ByteArray) { file.parentFile!!.mkdirs(); file.writeBytes(bytes) }
    }
}
