package com.mangalens

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import com.mangalens.download.*
import com.mangalens.ui.video.RecentVideoSource
import com.mangalens.ui.video.RecentVideoStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

/** Authored UNRUN: real Home → exact Download Room row → confirmation → actual private deletion. */
@RunWith(AndroidJUnit4::class)
class FailedDownloadPartialCleanupInstrumentedTest {
    @Test fun explicitFailedRowCleanupPreservesCompletedFavouriteAndNeighbourPartial() = coreScreenSmoke("failed-partial-cleanup") {
        val sample = File(requireNotNull(InstrumentationRegistry.getArguments().getString("sample_video")) {
            "Stage the controlled original H264/AAC sample; missing fixture is not a pass."
        })
        assertTrue(sample.isFile && sample.length() > 0)
        val id = "cleanup-${UUID.randomUUID()}"; val peerId = "peer-${UUID.randomUUID()}"
        val failedTitle = "Failed transfer $id"
        val root = File(context.filesDir, "downloads").apply { assertTrue(mkdirs() || isDirectory) }
        val partial = File(root, "$id.part")
        val validator = File(root, "$id.validator")
        val peerPartial = File(root, "$peerId.part")
        val completed = File(root, "$peerId.mp4")
        val dao = DownloadDatabase.get(context).downloads()
        val history = RecentVideoStore.shared(context)
        var recentKey: String? = null
        try {
            sample.inputStream().use { input -> partial.outputStream().use { output ->
                val bytes = ByteArray(128 * 1024); val read = input.read(bytes); assertTrue(read > 0); output.write(bytes, 0, read)
            } }
            validator.writeText("test-owned validator"); peerPartial.writeText("neighbour partial")
            sample.copyTo(completed)
            val originalHash = digest(completed); assertEquals(digest(sample), originalHash)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", completed)
            val metadata = MediaMetadataRetriever()
            val duration = try { metadata.setDataSource(completed.absolutePath)
                requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            } finally { metadata.release() }
            val source = requireNotNull(RecentVideoSource.local(uri.toString())); recentKey = source.key
            waitFor("History owner did not initialize", 5_000) { !history.state.value.loading }
            assertNull(history.state.value.error)
            history.record(source, 0, duration, true); history.favorite(source.key, true)
            waitFor("Owned favourite was not durably saved", 5_000) {
                history.state.value.entries.any { it.key == source.key && it.favorite }
            }
            runBlocking {
                dao.upsert(DownloadEntity(id, "https://fixture.invalid/$id.mp4", failedTitle, "video/mp4",
                    state = DownloadState.FAILED, bytesDownloaded = partial.length(), error = "Controlled transfer failure"))
                dao.upsert(DownloadEntity(peerId, "https://fixture.invalid/$peerId.mp4", "Completed favourite $peerId", "video/mp4",
                    destination = uri.toString(), bytesDownloaded = completed.length(), totalBytes = completed.length(), state = DownloadState.COMPLETED))
            }
            launchHome(); openHomeShortcut("Downloads", minimumWidthDp = 196); node(By.text("Download Room"))
            clickOwnedCleanup(failedTitle)
            node(By.text("Remove failed partial files?"))
            tap(By.text("Remove partial files").enabled(true))
            waitFor("Confirmed exact partial cleanup did not finish", 5_000) {
                !partial.exists() && !validator.exists() && runBlocking { dao.get(id)?.stage == FailedDownloadPartialPolicy.REMOVED }
            }
            assertEquals(DownloadState.CANCELLED, runBlocking { dao.get(id) }?.state)
            assertEquals("neighbour partial", peerPartial.readText())
            assertEquals(originalHash, digest(completed))
            val currentPeer = runBlocking { requireNotNull(dao.get(peerId)) }
            assertEquals(DownloadState.COMPLETED, currentPeer.state); assertEquals(uri.toString(), currentPeer.destination)
            assertTrue(history.state.value.entries.any { it.key == source.key && it.favorite })
            capture("exact-partial-cleanup-completed-favourite-retained")
        } finally {
            finishActivity(); runBlocking { dao.delete(id); dao.delete(peerId) }
            recentKey?.let(history::remove)
            partial.delete(); validator.delete(); peerPartial.delete(); completed.delete()
        }
    }

    private fun CoreScreenSmokeSupport.clickOwnedCleanup(title: String) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < until) {
            try {
                val titleNode = device.findObject(By.text(title).pkg(context.packageName))
                val titleBounds = titleNode?.visibleBounds
                var parent = titleNode?.parent
                while (parent != null) {
                    val card = parent
                    if (card.isScrollable) break
                    val statuses = card.findObjects(By.text(Pattern.compile(DownloadState.entries.joinToString("|") { it.name })).pkg(context.packageName))
                    if (statuses.isNotEmpty()) {
                        val status = statuses.singleOrNull()?.takeIf { it.text == DownloadState.FAILED.name }
                        val action = card.findObjects(By.text("Remove partial files").enabled(true).pkg(context.packageName)).singleOrNull()
                        if (status != null && action != null && titleBounds != null && card.visibleBounds.contains(titleBounds) &&
                            action.visibleBounds.top >= status.visibleBounds.bottom && card.visibleBounds.contains(action.visibleBounds)) {
                            action.click(); return
                        }
                        break // Never climb past this exact status-bearing card into peer rows/root.
                    }
                    parent = card.parent
                }
            } catch (_: StaleObjectException) { }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
        }
        fail("The exact FAILED Download Room row had no enabled partial-cleanup action")
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256").let { hash ->
        file.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) {
            val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count)
        } }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
}
