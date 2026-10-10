package com.mangalens

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.DownloadDatabase
import com.mangalens.download.DownloadEntity
import com.mangalens.download.DownloadState
import com.mangalens.ui.downloads.SavedVideoUnavailableException
import com.mangalens.ui.downloads.SavedVideoUnavailableReason
import com.mangalens.ui.video.RecentVideoOpening
import com.mangalens.ui.video.RecentVideoSource
import com.mangalens.ui.video.RecentVideoStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** API29+ genuine MediaStore ownership must not bypass the current native Download row. */
@RunWith(AndroidJUnit4::class)
class RecentVideoAccessInstrumentedTest {
    @SdkSuppress(minSdkVersion = 29)
    @Test fun appOwnedMediaStoreFileWithFailedDownloadCannotUseHistoryFallback() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(requireNotNull(InstrumentationRegistry.getArguments().getString("sample_video")) {
            "The controlled original H264/AAC sample_video is required; missing media is not a pass"
        })
        assertTrue(fixture.isFile && fixture.length() > 0)
        val id = "qa-history-refusal-${UUID.randomUUID()}"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$id.mp4")
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        val dao = DownloadDatabase.get(context).downloads()
        val source = requireNotNull(RecentVideoSource.local(uri.toString()))
        val history = RecentVideoStore.shared(context)
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                fixture.inputStream().use { it.copyTo(output) }
            }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            assertEquals(1, context.contentResolver.update(uri, values, null, null))
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(context.packageName, it.getString(0))
            }
            // The history metadata is intentionally stale relative to the authoritative FAILED row.
            history.record(source, 0, 22_000, true)
            withTimeout(5_000) { history.state.first { it.entries.any { entry -> entry.source == source } } }
            dao.upsert(DownloadEntity(id, "https://fixture.invalid/$id.mp4", id, "video/mp4",
                destination = uri.toString(), bytesDownloaded = fixture.length(), totalBytes = fixture.length(), state = DownloadState.FAILED))
            assertEquals(DownloadState.FAILED, requireNotNull(dao.get(id)).state)
            try {
                RecentVideoOpening.prepare(context, source.key)
                fail("App-owned URI permission must not make a failed download playable through recent history")
            } catch (refused: SavedVideoUnavailableException) {
                assertEquals(SavedVideoUnavailableReason.NOT_COMPLETED, refused.reason)
            }
            assertEquals(DownloadState.FAILED, requireNotNull(dao.get(id)).state)
        } finally {
            dao.delete(id)
            history.remove(source.key)
            context.contentResolver.delete(uri, null, null)
        }
    }
}
