package com.mangalens

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import com.mangalens.download.DownloadDatabase
import com.mangalens.download.DownloadEntity
import com.mangalens.download.DownloadState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual Download Room navigation and native decode; controlled public fixture, not a provider claim. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class DownloadsPlaybackHandoffTest {
    @Test fun completedSavedVideoOpensInProductionPlayerAndReopensOffline() = coreScreenSmoke("download-local-handoff") {
        val staged = InstrumentationRegistry.getArguments().getString("sample_video")
        assertNotNull("Stage the verified controlled H264/AAC sample_video; missing fixture is not a pass", staged)
        val source = File(requireNotNull(staged))
        assertTrue("Controlled sample is missing", source.isFile && source.length() > 0L)
        val id = "qa-download-${UUID.randomUUID()}"
        val title = "Saved video ${UUID.randomUUID()}"
        val directory = File(context.filesDir, "downloads/$id").apply { assertTrue(mkdirs()) }
        val saved = File(directory, "$id.mp4")
        val dao = DownloadDatabase.get(context).downloads()
        val outputs = JSONArray()
        var destination: Uri? = null
        var currentPlayer: ExoPlayer? = null
        try {
            source.inputStream().use { input -> saved.outputStream().use { output -> input.copyTo(output) } }
            val expectedHash = digest(source)
            assertEquals(expectedHash, digest(saved))
            val metadata = MediaMetadataRetriever()
            val duration: Long
            val width: Int
            try {
                metadata.setDataSource(saved.absolutePath)
                duration = requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                width = requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
            } finally { metadata.release() }
            assertTrue("Controlled two-pass fixture is shorter than its reference video", duration >= 21_000L)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", saved)
            destination = uri
            assertEquals("video/mp4", context.contentResolver.getType(uri))
            // This same-app FileProvider is durably app-owned; it needs no invented SAF grant.
            context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                assertNotNull(descriptor)
                assertEquals(saved.length(), requireNotNull(descriptor).statSize)
            }
            runBlocking { dao.upsert(DownloadEntity(
                id = id,
                sourceUrl = "https://fixture.invalid/$id/expired-source.mp4",
                title = title,
                mimeType = "video/mp4",
                destination = uri.toString(),
                bytesDownloaded = saved.length(), totalBytes = saved.length(), state = DownloadState.COMPLETED
            )) }
            repeat(3) { entry ->
                if (entry == 2) await(5_000L, "Actual saved playback was not durably published to Recent Video") {
                    com.mangalens.ui.video.RecentVideoStore.shared(context).state.value.entries.any {
                        it.source.uri == uri.toString() && it.durationMs >= 21_000L && it.positionMs > 12_000L
                    }
                }
                launchHome()
                if (entry < 2) {
                    openHomeShortcut("Downloads", minimumWidthDp = 196)
                    node(By.text("Download Room"))
                    clickOwnedOpen(title)
                } else clickOwnedRecentVideo(saved.name)
                var view: PlayerView? = null
                await(8_000L, "Completed saved video never entered its production player; an external chooser is not playback") {
                    onMainValue {
                        resumedActivity()?.window?.decorView?.let(::findPlayerView)?.takeIf {
                            it.player?.currentMediaItem?.localConfiguration?.uri == uri && it.isShown
                        }?.also { view = it } != null
                    }
                }
                assertEquals("Saved-video handoff left MangaLens", context.packageName, device.currentPackageName)
                val player = onMainValue { requireNotNull(view?.player) as ExoPlayer }
                currentPlayer = player
                await(60_000L, "Saved video did not become READY with its original duration") {
                    onMainValue {
                        assertNull("Saved destination could not be decoded", player.playerError)
                        player.playbackState == Player.STATE_READY && kotlin.math.abs(player.duration - duration) <= 1000L
                    }
                }
                val seek = 12_000L
                onMain { assertEquals(uri, player.currentMediaItem?.localConfiguration?.uri); player.seekTo(seek); player.play() }
                await(60_000L, "Saved video's actual playback clock did not advance after seek") {
                    onMainValue {
                        assertNull("Saved video's offline seek failed", player.playerError)
                        player.playbackState == Player.STATE_READY && player.isPlaying && player.currentPosition > seek
                    }
                }
                var frame: Bitmap? = null
                await(30_000L, "Saved video produced no decoded nonuniform native frame") {
                    onMain {
                        assertNull(player.playerError)
                        val texture = view?.videoSurfaceView as? TextureView
                        if (texture?.isAvailable == true && player.videoSize.width == width) {
                            val candidate = texture.getBitmap(640, 288)
                            if (candidate != null) {
                                val values = (0 until 288 step 8).flatMap { y -> (0 until 640 step 8).map { x ->
                                    val pixel = candidate.getPixel(x, y)
                                    android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
                                } }
                                if (values.max() - values.min() > 60) frame = candidate else candidate.recycle()
                            }
                        }
                    }
                    frame != null
                }
                val frameOutput = File(context.getExternalFilesDir(null), "qa/core-smoke/download-local-handoff/frame-$entry.png")
                try { frameOutput.outputStream().use { assertTrue(requireNotNull(frame).compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { frame?.recycle() }
                outputs.put(onMainValue {
                    assertEquals(MimeTypes.VIDEO_H264, requireNotNull(player.videoFormat).sampleMimeType)
                    assertEquals(MimeTypes.AUDIO_AAC, requireNotNull(player.audioFormat).sampleMimeType)
                    assertTrue("Original video track is not selected", player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected })
                    assertTrue("Original audio track is not selected", player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected })
                    assertEquals(uri, player.currentMediaItem?.localConfiguration?.uri)
                    assertNull(player.playerError)
                    player.pause()
                    JSONObject().put("entry", entry).put("position_ms", player.currentPosition)
                        .put("duration_ms", player.duration).put("video_width", player.videoSize.width)
                        .put("decoded_video_mime", player.videoFormat?.sampleMimeType)
                        .put("decoded_audio_mime", player.audioFormat?.sampleMimeType)
                        .put("native_frame", true).put("saved_uri_sha256", digest(uri.toString().toByteArray()))
                })
                capture("saved-player-$entry")
                finishActivity()
                currentPlayer = null
                val row = runBlocking { requireNotNull(dao.get(id)) }
                assertEquals(DownloadState.COMPLETED, row.state)
                assertEquals(uri.toString(), row.destination)
                assertEquals(expectedHash, digest(saved))
            }
            File(context.getExternalFilesDir(null), "qa/core-smoke/download-local-handoff/outputs.json").writeText(
                JSONObject().put("fixture_sha256", expectedHash).put("fixture_bytes", saved.length())
                    .put("cold_activity_entries", 3).put("recent_video_entry", true).put("source_url_reopened", false).put("observations", outputs).toString(2)
            )
        } finally {
            onMain { runCatching { currentPlayer?.takeIf { it.currentMediaItem?.localConfiguration?.uri == destination }?.pause() } }
            // Close only the external chooser that this exact failing handoff may have opened.
            if (device.currentPackageName != context.packageName) device.pressBack()
            finishActivity()
            runBlocking { dao.delete(id) }
            destination?.let { uri -> context.getSharedPreferences("mangalens_video_positions", 0).edit()
                .remove(digest(uri.toString().toByteArray())).commit() }
            destination?.let { uri -> com.mangalens.ui.video.RecentVideoSource.local(uri.toString())?.let {
                com.mangalens.ui.video.RecentVideoStore.shared(context).remove(it.key)
            } }
            saved.delete(); directory.delete()
        }
    }

    private fun CoreScreenSmokeSupport.clickOwnedRecentVideo(title: String) {
        val until = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < until) {
            try {
                val ownedTitle = device.findObject(By.text(title).pkg(context.packageName))
                val titleBounds = ownedTitle?.visibleBounds
                var parent = ownedTitle?.parent
                while (parent != null) {
                    val card = parent
                    if (card.isScrollable) break
                    if (card.findObjects(By.text("Remove from history").pkg(context.packageName)).isNotEmpty()) {
                        val button = card.findObjects(By.text(java.util.regex.Pattern.compile("Continue video|Open video")).enabled(true).pkg(context.packageName))
                            .singleOrNull()?.takeIf { titleBounds != null && card.visibleBounds.contains(titleBounds) &&
                                card.visibleBounds.contains(it.visibleBounds) && it.visibleBounds.top >= titleBounds.bottom }
                        if (button != null) { button.click(); return }
                        break // Never climb out of the first actual owned history card to a neighbour.
                    }
                    parent = card.parent
                }
            } catch (_: StaleObjectException) { /* Re-query within the same original route budget. */ }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 20)
        }
        throw AssertionError("The exact saved video's Recent Video action was not available")
    }

    private fun CoreScreenSmokeSupport.clickOwnedOpen(title: String) {
        val until = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < until) {
            try {
                val ownedTitle = device.findObject(By.text(title).pkg(context.packageName))
                val titleBounds = ownedTitle?.visibleBounds
                var parent: UiObject2? = ownedTitle?.parent
                while (parent != null) {
                    val card = parent
                    // Stop before a scrolling list, or at the first status-bearing ancestor.
                    // A missing/disabled owned action must never cause a climb to peers/root.
                    if (card.isScrollable) break
                    val statuses = card.findObjects(By.text(java.util.regex.Pattern.compile(DownloadState.entries.joinToString("|") { it.name })).pkg(context.packageName))
                    if (statuses.isNotEmpty()) {
                        val status = statuses.singleOrNull()
                        val ownStatus = status?.takeIf {
                            it.text == DownloadState.COMPLETED.name && titleBounds != null &&
                                card.visibleBounds.contains(titleBounds) &&
                                it.visibleBounds.top >= titleBounds.bottom
                        }
                        if (ownStatus != null) {
                            val button = card.findObjects(By.text("Open").enabled(true).pkg(context.packageName)).singleOrNull()
                                ?.takeIf { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 &&
                                    it.visibleBounds.top >= ownStatus.visibleBounds.bottom &&
                                    card.visibleBounds.contains(it.visibleBounds) }
                            if (button != null) { button.click(); return }
                        }
                        break // Reacquire this exact card on the next poll; never climb past it.
                    }
                    parent = card.parent
                }
            } catch (_: StaleObjectException) { /* Reacquire the row within the original click bound. */ }
            SystemClock.sleep(50L)
        }
        fail("Owned completed Download Room row had no enabled Open action")
    }

    private fun resumedActivity(): MainActivity? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPlayerView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun <T> onMainValue(block: () -> T): T {
        var result: T? = null; onMain { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) { if (SystemClock.elapsedRealtime() >= until) fail(message); SystemClock.sleep(100L) }
    }
    private fun digest(file: File): String {
        val result = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            val n = input.read(buffer); if (n < 0) break; result.update(buffer, 0, n)
        } }
        return result.digest().joinToString("") { "%02x".format(it) }
    }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
