package com.mangalens.ui.video

internal enum class PlaybackTrackType { VIDEO, AUDIO, CAPTIONS }

internal data class PlaybackTrackDescription(
    val label: String,
    val supported: Boolean,
    val selected: Boolean
)

internal data class PlaybackTrackGroup<G>(
    val group: G,
    val type: PlaybackTrackType,
    val tracks: List<PlaybackTrackDescription>
)

internal data class PlaybackTrackChoice<G>(
    val group: G,
    val type: PlaybackTrackType,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean
)

/** Keep the native group and its own track index together; they are independent coordinates. */
internal fun <G> playbackTrackChoices(
    groups: List<PlaybackTrackGroup<G>>,
    type: PlaybackTrackType
): List<PlaybackTrackChoice<G>> = groups.filter { it.type == type }.flatMap { group ->
    group.tracks.mapIndexedNotNull { index, track ->
        if (!track.supported) null
        else PlaybackTrackChoice(group.group, type, index, track.label, track.selected)
    }
}

/** A refreshed manifest may remove a group or change decoder support before a tap is dispatched. */
internal fun <G> validPlaybackTrackChoice(
    choice: PlaybackTrackChoice<G>,
    current: List<PlaybackTrackGroup<G>>
): PlaybackTrackChoice<G>? = playbackTrackChoices(current, choice.type).firstOrNull {
    it.group == choice.group && it.trackIndex == choice.trackIndex
}

/** Overrides for old manifests remain in Media3 parameters but do not select current tracks. */
internal fun <G> hasCurrentPlaybackTrackOverride(
    current: List<PlaybackTrackGroup<G>>,
    type: PlaybackTrackType,
    overriddenGroups: Set<G>
): Boolean = current.any { it.type == type && it.group in overriddenGroups }
