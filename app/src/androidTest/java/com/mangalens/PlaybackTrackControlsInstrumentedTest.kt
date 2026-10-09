package com.mangalens

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mangalens.ui.video.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Media3 group/parameter integration checks; these do not claim decoded playback. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackTrackControlsInstrumentedTest {
    @Test fun speedButtonChangesTheActualPlayerThroughTheSharedPanel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        var player: ExoPlayer? = null
        var callbacks = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val activePlayer = ExoPlayer.Builder(activity).build()
                    player = activePlayer
                    activePlayer.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackParametersChanged(value: androidx.media3.common.PlaybackParameters) { callbacks++ }
                    })
                    activity.setContent {
                        MaterialTheme {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                                PlaybackTrackControlsPanel(activePlayer)
                            }
                        }
                    }
                }
                val button = device.wait(Until.findObject(By.text("1.5×")), 10_000)
                assertNotNull("The shared panel did not expose the speed control", button)
                button!!.click()
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals(1.5f, requireNotNull(player).playbackParameters.speed, 0f)
                    assertEquals(1f, requireNotNull(player).playbackParameters.pitch, 0f)
                    assertTrue("The player did not publish a parameter change", callbacks > 0)
                }
            } finally {
                instrumentation.runOnMainSync { player?.release() }
            }
        }
    }

    @Test fun selectingEveryAudioRepresentationWritesItsExactNativeGroupAndIndex() {
        val first = TrackGroup("original", audio("en", 2), audio("en", 6))
        val second = TrackGroup("dub", audio("hi", 2), audio("hi", 6))
        val video = TrackGroup("video", video(720), video(1080))
        val tracks = Tracks(listOf(available(first), available(second), available(video)))
        val original = TrackSelectionParameters.Builder().setMaxVideoSize(1920, 1080)
            .setPreferredAudioLanguage("en")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setOverrideForType(TrackSelectionOverride(video, 1)).build()
        val choices = playbackTrackChoices(playbackTrackGroups(tracks), PlaybackTrackType.AUDIO)

        assertEquals(4, choices.size)
        choices.forEach { choice ->
            val changed = requireNotNull(selectedPlaybackTrackParameters(original, tracks, choice))
            assertEquals(listOf(choice.trackIndex), changed.overrides[choice.group]?.trackIndices)
            assertEquals(listOf(1), changed.overrides[video]?.trackIndices)
            assertEquals(original.preferredAudioLanguages, changed.preferredAudioLanguages)
            assertEquals(1080, changed.maxVideoHeight)
            assertTrue(C.TRACK_TYPE_TEXT in changed.disabledTrackTypes)
            assertFalse(C.TRACK_TYPE_AUDIO in changed.disabledTrackTypes)
        }
    }

    @Test fun automaticAudioSelectionPreservesTheChosenVideoAndCaptionTracks() {
        val audio = TrackGroup("audio", audio("en", 2))
        val video = TrackGroup("video", video(1080))
        val captions = TrackGroup("captions", captions("en"))
        val original = TrackSelectionParameters.Builder()
            .setOverrideForType(TrackSelectionOverride(audio, 0))
            .setOverrideForType(TrackSelectionOverride(video, 0))
            .setOverrideForType(TrackSelectionOverride(captions, 0))
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()

        val changed = automaticPlaybackTrackParameters(original, PlaybackTrackType.AUDIO)

        assertFalse(changed.overrides.containsKey(audio))
        assertEquals(original.overrides[video], changed.overrides[video])
        assertEquals(original.overrides[captions], changed.overrides[captions])
        assertFalse(C.TRACK_TYPE_AUDIO in changed.disabledTrackTypes)
    }

    @Test fun mediaCaptionsCanBeDisabledAndExplicitlyReenabledWithoutChangingAudio() {
        val audio = TrackGroup("audio", audio("hi", 2))
        val englishCaptions = TrackGroup("english-captions", captions("en"))
        val hindiCaptions = TrackGroup("hindi-captions", captions("hi"))
        val tracks = Tracks(listOf(available(audio), available(englishCaptions), available(hindiCaptions)))
        val original = TrackSelectionParameters.Builder()
            .setOverrideForType(TrackSelectionOverride(audio, 0))
            .setOverrideForType(TrackSelectionOverride(englishCaptions, 0)).build()

        val off = disabledMediaSubtitleParameters(original)
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertFalse(off.overrides.containsKey(englishCaptions))
        val hindi = playbackTrackChoices(playbackTrackGroups(tracks), PlaybackTrackType.CAPTIONS)[1]
        val changed = requireNotNull(selectedPlaybackTrackParameters(off, tracks, hindi))

        assertFalse(C.TRACK_TYPE_TEXT in changed.disabledTrackTypes)
        assertEquals(listOf(0), changed.overrides[hindiCaptions]?.trackIndices)
        assertFalse(changed.overrides.containsKey(englishCaptions))
        assertEquals(original.overrides[audio], changed.overrides[audio])
    }

    @Test fun refreshedManifestRejectsRemovedOrUnsupportedNativeTracks() {
        val old = TrackGroup("old signed manifest", video(1080))
        val choice = playbackTrackChoices(playbackTrackGroups(Tracks(listOf(available(old)))), PlaybackTrackType.VIDEO).single()
        val current = Tracks(listOf(available(TrackGroup("new signed manifest", video(720)))))
        val original = TrackSelectionParameters.Builder().build()

        assertNull(selectedPlaybackTrackParameters(original, current, choice))
        val unsupported = Tracks(listOf(Tracks.Group(old, false,
            intArrayOf(C.FORMAT_UNSUPPORTED_TYPE), booleanArrayOf(false))))
        assertNull(selectedPlaybackTrackParameters(original, unsupported, choice))
    }

    private fun available(group: TrackGroup) = Tracks.Group(group, false,
        IntArray(group.length) { C.FORMAT_HANDLED }, BooleanArray(group.length))
    private fun audio(language: String, channels: Int) = Format.Builder()
        .setSampleMimeType("audio/mp4a-latm").setLanguage(language).setChannelCount(channels).build()
    private fun video(height: Int) = Format.Builder()
        .setSampleMimeType("video/avc").setWidth(height * 16 / 9).setHeight(height).build()
    private fun captions(language: String) = Format.Builder()
        .setSampleMimeType("text/vtt").setLanguage(language).build()
}
