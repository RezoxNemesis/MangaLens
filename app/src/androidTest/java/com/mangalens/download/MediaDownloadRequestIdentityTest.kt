package com.mangalens.download

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MediaDownloadRequestIdentityTest {
    @Test fun recoveredQueuedTaskReusesItsOriginalTransfer() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = DownloadDatabase.get(app).downloads()
        val id = "identity-${UUID.randomUUID()}"
        val calls = mutableListOf<String>()
        val commands = object : AdaptiveDownloadCommands {
            override fun add(id: String, url: String, mime: String) { calls += id }
            override fun pause(id: String) {}
            override fun resume(id: String) {}
            override fun remove(id: String) {}
        }
        val original = DownloadEntity(id, "https://example.com/video.m3u8", "Story", "application/x-mpegurl", state = DownloadState.QUEUED)
        dao.upsert(original)
        try {
            val manager = MediaDownloadManager(app, commands)
            assertEquals(id, manager.enqueue("https://example.com/source", requestId = id))
            assertEquals(listOf(id), calls)
            assertEquals(original, dao.get(id))
            dao.upsert(original.copy(state = DownloadState.COMPLETED))
            assertEquals(id, manager.enqueue("https://example.com/source", requestId = id))
            assertEquals("Finished transfers must not restart", 1, calls.size)
        } finally { dao.delete(id) }
    }
}
