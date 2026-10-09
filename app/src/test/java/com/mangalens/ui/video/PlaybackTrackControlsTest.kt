package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class PlaybackTrackControlsTest {
    @Test fun everyTrackInEachAudioGroupIsReachable() {
        val groups = listOf(
            group("main", PlaybackTrackType.AUDIO, "English stereo", "English surround"),
            group("dub", PlaybackTrackType.AUDIO, "Hindi stereo", "Hindi surround")
        )

        assertEquals(
            setOf("main" to 0, "main" to 1, "dub" to 0, "dub" to 1),
            playbackTrackChoices(groups, PlaybackTrackType.AUDIO).map { it.group to it.trackIndex }.toSet()
        )
    }

    @Test fun unequalGroupLengthsStillExposeEveryAudioTrack() {
        val groups = listOf(
            group("main", PlaybackTrackType.AUDIO, "English"),
            group("dub", PlaybackTrackType.AUDIO, "Hindi", "Japanese", "Korean", "Spanish")
        )

        assertEquals(
            listOf("English", "Hindi", "Japanese", "Korean", "Spanish"),
            playbackTrackChoices(groups, PlaybackTrackType.AUDIO).map { it.label }
        )
    }

    @Test fun unsupportedRepresentationsNeverBecomeManualChoices() {
        val groups = listOf(PlaybackTrackGroup("adaptive", PlaybackTrackType.VIDEO, listOf(
            PlaybackTrackDescription("720p", supported = true, selected = true),
            PlaybackTrackDescription("2160p unsupported codec", supported = false, selected = false),
            PlaybackTrackDescription("1080p", supported = true, selected = false)
        )))

        val options = playbackTrackChoices(groups, PlaybackTrackType.VIDEO)
        assertEquals(listOf(0, 2), options.map { it.trackIndex })
        assertEquals(listOf(true, false), options.map { it.selected })
    }

    @Test fun audioCaptionsAndVideoRemainSeparateEvenWithIdenticalLabels() {
        val groups = listOf(
            group("audio", PlaybackTrackType.AUDIO, "English"),
            group("captions", PlaybackTrackType.CAPTIONS, "English"),
            group("video", PlaybackTrackType.VIDEO, "1080p")
        )

        assertEquals(listOf("captions"), playbackTrackChoices(groups, PlaybackTrackType.CAPTIONS).map { it.group })
        assertEquals(listOf("audio"), playbackTrackChoices(groups, PlaybackTrackType.AUDIO).map { it.group })
        assertEquals(listOf("video"), playbackTrackChoices(groups, PlaybackTrackType.VIDEO).map { it.group })
    }

    @Test fun removedGroupCannotBeSelectedByAStaleSheetCallback() {
        val old = group("expired manifest", PlaybackTrackType.VIDEO, "1080p")
        val choice = playbackTrackChoices(listOf(old), PlaybackTrackType.VIDEO).single()
        val current = listOf(group("refreshed manifest", PlaybackTrackType.VIDEO, "720p"))

        assertNull(validPlaybackTrackChoice(choice, current))
    }

    @Test fun trackBecomingUnsupportedAfterInventoryRefreshCannotBeSelected() {
        val old = group("adaptive", PlaybackTrackType.VIDEO, "1080p")
        val choice = playbackTrackChoices(listOf(old), PlaybackTrackType.VIDEO).single()
        val current = listOf(PlaybackTrackGroup("adaptive", PlaybackTrackType.VIDEO,
            listOf(PlaybackTrackDescription("1080p", supported = false, selected = false))))

        assertNull(validPlaybackTrackChoice(choice, current))
    }

    @Test fun validChoiceUsesTheCurrentSelectionStatus() {
        val old = group("audio", PlaybackTrackType.AUDIO, "English", "Hindi")
        val choice = playbackTrackChoices(listOf(old), PlaybackTrackType.AUDIO)[1]
        val current = listOf(PlaybackTrackGroup("audio", PlaybackTrackType.AUDIO,
            listOf(PlaybackTrackDescription("English", true, false), PlaybackTrackDescription("Hindi", true, true))))

        assertTrue(validPlaybackTrackChoice(choice, current)!!.selected)
    }

    @Test fun emptyInventoryHasNoSyntheticQualityOrSubtitleTrack() {
        PlaybackTrackType.entries.forEach { type ->
            assertTrue(playbackTrackChoices(emptyList<PlaybackTrackGroup<String>>(), type).isEmpty())
        }
    }

    @Test fun overrideFromARemovedManifestDoesNotHideCurrentAutomaticSelection() {
        val current = listOf(group("refreshed manifest", PlaybackTrackType.VIDEO, "720p"))

        assertFalse(hasCurrentPlaybackTrackOverride(current, PlaybackTrackType.VIDEO, setOf("expired manifest")))
        assertTrue(hasCurrentPlaybackTrackOverride(current, PlaybackTrackType.VIDEO, setOf("refreshed manifest")))
    }

    private fun group(id: String, type: PlaybackTrackType, vararg labels: String) =
        PlaybackTrackGroup(id, type, labels.map { PlaybackTrackDescription(it, supported = true, selected = false) })
}
