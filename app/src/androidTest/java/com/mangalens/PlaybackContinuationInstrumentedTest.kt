package com.mangalens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.ui.video.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Actual Media3 start/seek/callback checks. Timeline fixtures are never decoded or called playback acceptance. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackContinuationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeTenSecondLabelsMatchActualVmSeekIncrementsAndCallbacks() {
        val store = ViewModelStore()
        var vm: LocalVideoPlayerViewModel? = null
        var discontinuities = 0
        try {
            instrumentation.runOnMainSync {
                vm = LocalVideoPlayerViewModel(instrumentation.targetContext.applicationContext as Application)
                store.put("seek-fixture", requireNotNull(vm))
                val player = requireNotNull(vm).player
                player.setMediaSource(TimelineOnlySource("seek"), 25_000)
                player.addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) { discontinuities++ }
                })
                assertEquals(10_000L, player.seekBackIncrement)
                assertEquals(10_000L, player.seekForwardIncrement)
                player.seekBack()
                assertEquals(15_000L, player.currentPosition)
                player.seekForward()
                assertEquals(25_000L, player.currentPosition)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue("No actual seek callbacks were emitted", discontinuities >= 2) }
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun replacementReadsThePlayersLatestPositionAndKeepsItOnTheNewSource() = withPlayer { player ->
        player.setMediaSource(TimelineOnlySource("signed-version-a"), 5_000)
        val resolverStartPosition = player.currentPosition
        player.seekTo(27_500)
        val actual = playbackContinuationSnapshot(player)
        val start = playbackReplacementStart(2, 2, actual, 0)
        var transitioned = false
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { if (item?.mediaId == "signed-version-b") transitioned = true }
        })

        assertTrue(applyPlaybackReplacementStart(player, TimelineOnlySource("signed-version-b"), start))

        assertEquals(5_000L, resolverStartPosition)
        assertEquals(27_500L, player.currentPosition)
        assertEquals("signed-version-b", player.currentMediaItem?.mediaId)
        assertTrue("No actual Media3 source transition callback", transitioned)
    }

    @Test fun liveDynamicAndUnseekableReplacementUseTheNewSourcesDefaultWindowStart() = withPlayer { player ->
        listOf(Triple(true, false, true), Triple(false, true, true), Triple(false, false, false)).forEachIndexed { index, flags ->
            val (live, dynamic, seekable) = flags
            player.setMediaSource(TimelineOnlySource("old-$index", live, dynamic, seekable), 35_000)
            val actual = playbackContinuationSnapshot(player)
            assertEquals(live, actual.live)
            assertEquals(dynamic, actual.dynamic)
            assertEquals(seekable, actual.seekable)
            val start = playbackReplacementStart(4, 4, actual, 0)
            assertEquals(PlaybackReplacementStart.Default, start)

            applyPlaybackReplacementStart(player, TimelineOnlySource("new-$index", live, dynamic, seekable, defaultPosition = 17_000), start)

            assertEquals("Old live coordinates must not override the new default", 17_000L, player.currentPosition)
        }
    }

    @Test fun supersededReplacementDoesNotTouchTheActualPlayerOrPublishCallbacks() = withPlayer { player ->
        player.setMediaSource(TimelineOnlySource("current"), 25_000)
        var transitions = 0
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { transitions++ }
        })
        val start = playbackReplacementStart(9, 8, playbackContinuationSnapshot(player), 0)

        assertFalse(applyPlaybackReplacementStart(player, TimelineOnlySource("stale"), start))

        assertEquals("current", player.currentMediaItem?.mediaId)
        assertEquals(25_000L, player.currentPosition)
        assertEquals(0, transitions)
    }

    @Test fun localAndHttpReplacementsAdvanceRevisionAndRejectAnOlderRefreshBeforeMutation() = withVmMedia { vm, uris ->
        vm.open(uris[0])
        val localRevision = vm.sourceRevision
        assertTrue(localRevision > 0)
        assertTrue(vm.openHttp(uris[1].toString(), headers = mapOf("X-QA" to "first")))
        val httpRevision = vm.sourceRevision
        assertTrue(httpRevision > localRevision)
        assertTrue(vm.openHttp(uris[1].toString(), headers = mapOf("x-qa" to "first")))
        assertEquals("Duplicate render must not replace the source", httpRevision, vm.sourceRevision)
        assertTrue(vm.openHttp(uris[1].toString(), headers = mapOf("X-QA" to "second")))
        assertTrue("Headers are part of source identity", vm.sourceRevision > httpRevision)
        val headerRevision = vm.sourceRevision
        assertTrue(vm.openHttp(uris[1].toString(), headers = mapOf("X-QA" to "second"),
            audioUrl = uris[2].toString(), audioHeaders = mapOf("X-QA-Audio" to "first")))
        assertTrue("Separate audio is part of source identity", vm.sourceRevision > headerRevision)
        val audioRevision = vm.sourceRevision
        assertTrue(vm.openHttp(uris[1].toString(), headers = mapOf("X-QA" to "second"),
            audioUrl = uris[2].toString(), audioHeaders = mapOf("X-QA-Audio" to "second")))
        assertTrue("Separate audio headers are part of source identity", vm.sourceRevision > audioRevision)
        val currentRevision = vm.sourceRevision
        vm.player.pause()
        val currentPosition = vm.player.currentPosition

        assertFalse(vm.openHttp(uris[2].toString(), refreshFromRevision = localRevision))

        assertEquals(currentRevision, vm.sourceRevision)
        assertEquals(uris[1], vm.player.currentMediaItem?.localConfiguration?.uri)
        assertEquals(currentPosition, vm.player.currentPosition)
        vm.open(uris[1])
        assertTrue("Local open must replace an HTTP request even for the same URI", vm.sourceRevision > currentRevision)
        assertFalse(vm.openHttp(uris[0].toString(), refreshFromRevision = currentRevision))
        assertEquals(uris[1], vm.player.currentMediaItem?.localConfiguration?.uri)
    }

    @Test fun vmRefreshCarriesLatestPositionAndFollowingComposeOpenDeduplicatesIt() = withVmMedia { vm, uris ->
        val oldHeaders = mapOf("X-QA" to "old")
        val headers = mapOf("X-QA" to "new")
        assertTrue(vm.openHttp(uris[0].toString(), headers = oldHeaders))
        val revision = vm.sourceRevision
        vm.player.stop()
        vm.player.setMediaSource(TimelineOnlySource("known-vod", uri = uris[0]), 2_000)
        vm.player.seekTo(6_500)

        assertTrue(vm.openHttp(uris[1].toString(), headers = headers, refreshFromRevision = revision))
        vm.player.pause()
        val refreshedRevision = vm.sourceRevision
        assertTrue(refreshedRevision > revision)
        assertEquals(6_500L, vm.player.currentPosition)
        assertEquals(uris[1], vm.player.currentMediaItem?.localConfiguration?.uri)
        assertTrue(vm.openHttp(uris[1].toString(), headers = headers))
        assertEquals("The Compose source effect must not reset the already refreshed source", refreshedRevision, vm.sourceRevision)
        assertEquals(6_500L, vm.player.currentPosition)
    }

    @Test fun normalSameUriHeaderReplacementStillReadsTheFreshlySavedPosition() = withVmMedia { vm, uris ->
        assertTrue(vm.openHttp(uris[0].toString(), headers = mapOf("X-QA" to "old")))
        vm.player.stop()
        vm.player.setMediaSource(TimelineOnlySource("same-uri", uri = uris[0]), 6_000)

        assertTrue(vm.openHttp(uris[0].toString(), headers = mapOf("X-QA" to "new")))
        vm.player.pause()

        assertEquals(6_000L, vm.player.currentPosition)
    }

    private fun withPlayer(block: (ExoPlayer) -> Unit) {
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(instrumentation.targetContext).build()
            try { block(player) } finally { player.release() }
        }
    }

    private fun withVmMedia(block: (LocalVideoPlayerViewModel, List<Uri>) -> Unit) {
        val context = instrumentation.targetContext
        // Existing explicitly project-owned public-domain speech fixture; no external requests.
        val bytes = instrumentation.context.assets.open("orez-fixtures/jfk.wav").use { it.readBytes() }
        val directory = File(context.cacheDir, "qa-continuation-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val uris = (0..2).map { index -> Uri.fromFile(File(directory, "same-content-$index.wav").apply { writeBytes(bytes) }) }
        val store = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                val vm = LocalVideoPlayerViewModel(context.applicationContext as Application)
                store.put("continuation-fixture", vm)
                block(vm, uris)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            context.getSharedPreferences("mangalens_video_positions", 0).edit().apply {
                uris.forEach { uri -> remove(MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }) }
            }.commit()
            directory.deleteRecursively()
        }
    }

    /** Supplies only real Media3 timeline state. Its sample period is never prepared or decoded. */
    private class TimelineOnlySource(
        id: String,
        live: Boolean = false,
        dynamic: Boolean = false,
        seekable: Boolean = true,
        defaultPosition: Long = 0,
        uri: Uri = Uri.parse("https://fixture.invalid/$id")
    ) : BaseMediaSource() {
        private val item = MediaItem.Builder().setUri(uri).setMediaId(id).apply {
            if (live) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        }.build()
        private val timeline = SinglePeriodTimeline(120_000_000L, 120_000_000L, 0L,
            defaultPosition * 1_000, seekable, dynamic, live, null, item)
        override fun getMediaItem(): MediaItem = item
        override fun getInitialTimeline(): Timeline = timeline
        override fun prepareSourceInternal(mediaTransferListener: TransferListener?) = error("Timeline fixture must not be prepared")
        override fun maybeThrowSourceInfoRefreshError() = Unit
        override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
            error("Timeline fixture has no decoded samples")
        override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit
        override fun releaseSourceInternal() = Unit
    }
}
