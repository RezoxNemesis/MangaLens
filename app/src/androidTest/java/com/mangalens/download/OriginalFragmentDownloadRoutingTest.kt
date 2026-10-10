package com.mangalens.download

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** AUTHORED UNRUN: the actual migration and Room callbacks retain the selected transport after restart. */
@RunWith(AndroidJUnit4::class)
class OriginalFragmentDownloadRoutingTest {
    @Test fun legacyMigrationAndColdReopenKeepCapturedFragmentRowsOutOfAdaptiveCallbacks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "fragment-routing-" + UUID.randomUUID() + ".db"
        var database: DownloadDatabase? = null
        fun open() = Room.databaseBuilder(context, DownloadDatabase::class.java, name)
            .addMigrations(DownloadDatabase.MIGRATION_3_4).build()
        try {
            context.openOrCreateDatabase(name, 0, null).use { old ->
                old.execSQL("CREATE TABLE media_downloads (id TEXT NOT NULL PRIMARY KEY, sourceUrl TEXT NOT NULL, title TEXT NOT NULL, mimeType TEXT NOT NULL, destination TEXT, bytesDownloaded INTEGER NOT NULL, totalBytes INTEGER NOT NULL, state TEXT NOT NULL, error TEXT, createdAt INTEGER NOT NULL, actualHeight INTEGER, stage TEXT, provider TEXT NOT NULL DEFAULT 'generic', sourcePageUrl TEXT, requestedHeight INTEGER)")
                old.execSQL("CREATE INDEX index_media_downloads_state ON media_downloads(state)")
                old.execSQL("CREATE INDEX index_media_downloads_createdAt ON media_downloads(createdAt)")
                old.execSQL("INSERT INTO media_downloads (id, sourceUrl, title, mimeType, bytesDownloaded, totalBytes, state, createdAt) VALUES ('legacy', 'https://fixture.invalid/legacy.mpd', 'legacy', 'application/dash+xml', 0, -1, 'QUEUED', 1)")
                old.version = 3
            }
            database = open()
            val legacy = requireNotNull(database.downloads().get("legacy"))
            assertNull(legacy.selectedTransport); assertTrue(legacy.isAdaptive)
            val url = "https://fixture.invalid/manifest/video.mpd"
            val plan = OriginalFragmentPlan(url, "1080", "video/mp4", 8_000_000L,
                listOf(OriginalMediaFragment("https://fixture.invalid/init"), OriginalMediaFragment("https://fixture.invalid/media")))
            val media = ResolvedMediaLink(url, "video/mp4", "fixture", videoFragments = plan, expectedDurationUs = plan.durationUs)
            val kind = requireNotNull(OriginalFragmentTransport.downloadKind(media))
            database.downloads().upsert(DownloadEntity("selected", url, "selected", "video/mp4", selectedTransport = kind))
            database.close(); database = open()
            val dao = database.downloads()
            val restored = requireNotNull(dao.get("selected"))
            assertEquals(kind, restored.selectedTransport); assertFalse(restored.isAdaptive)
            OriginalFragmentTransport.requireDownloadBinding(restored.selectedTransport, media)
            assertEquals(0, dao.adaptiveProgressIfActive("selected", 100, 200))
            assertEquals(0, dao.adaptiveStateIfActive("selected", 200, 200, DownloadState.COMPLETED, null))
            assertEquals(DownloadState.QUEUED, dao.get("selected")!!.state)
            assertEquals(0L, dao.get("selected")!!.bytesDownloaded)
            assertEquals(1, dao.adaptiveProgressIfActive("legacy", 100, 200))
            assertTrue(dao.get("legacy")!!.isAdaptive)
        } finally {
            database?.close()
            context.deleteDatabase(name) // Only this test's UUID database is owned here.
        }
    }
}
