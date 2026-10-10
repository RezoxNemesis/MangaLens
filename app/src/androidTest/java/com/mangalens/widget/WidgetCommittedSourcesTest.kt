package com.mangalens.widget

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.core.reader.ChapterCbzResources
import com.mangalens.download.DownloadDatabase
import com.mangalens.download.DownloadEntity
import com.mangalens.download.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Authored real Room projection controls; uncompiled and unrun, not widget host acceptance. */
@RunWith(AndroidJUnit4::class)
class WidgetCommittedSourcesTest {
    @Test fun CommittedTransferUsesActualBytesAndCurrentNativeId() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(app, DownloadDatabase::class.java).build()
        try {
            database.downloads().upsert(DownloadEntity("completed", "https://example.org/private", "Complete", "video/mp4",
                bytesDownloaded = 17, totalBytes = 100, state = DownloadState.COMPLETED))
            val resources = ChapterCbzResources(); resources.beginPrivateWork()
            try {
                val snapshot = withContext(Dispatchers.IO) { WidgetContinuationFiles.download(database, resources) {} }
                assertEquals(17, snapshot.percent); assertEquals(WidgetNativeEntry.Download("completed"), snapshot.entry)
                assertTrue(snapshot.detail.contains("17 of 100 bytes")); assertFalse(snapshot.detail.contains("https://"))
                assertTrue(resources.privateReleaseProven())
            } finally { resources.finishPrivateWork(); withContext(Dispatchers.IO) { resources.close() } }
        } finally { withContext(Dispatchers.IO) { database.close() } }
    }
    @Test fun ActiveTransferWinsWithoutOpeningCookiesOrResolvedSourceColumns() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(app, DownloadDatabase::class.java).build()
        try {
            database.downloads().upsert(DownloadEntity("new-completed", "https://example.org/private?token=x", "Done", "video/mp4",
                bytesDownloaded = 100, totalBytes = 100, state = DownloadState.COMPLETED, createdAt = 99))
            database.downloads().upsert(DownloadEntity("older-active", "https://example.org/private?token=y", "Active", "video/mp4",
                bytesDownloaded = 23, totalBytes = -1, state = DownloadState.DOWNLOADING, createdAt = 1))
            val resources = ChapterCbzResources(); resources.beginPrivateWork()
            try {
                val snapshot = withContext(Dispatchers.IO) { WidgetContinuationFiles.download(database, resources) {} }
                assertEquals(WidgetNativeEntry.Download("older-active"), snapshot.entry); assertNull(snapshot.percent)
                assertEquals("Downloading · 23 bytes received · total unknown", snapshot.detail); assertTrue(resources.privateReleaseProven())
            } finally { resources.finishPrivateWork(); withContext(Dispatchers.IO) { resources.close() } }
        } finally { withContext(Dispatchers.IO) { database.close() } }
    }
}
