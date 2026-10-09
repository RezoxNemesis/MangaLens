package com.mangalens.ui.video

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale
import kotlin.math.roundToInt

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun PlaybackTrackControlsPanel(player: Player, modifier: Modifier = Modifier) {
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    var parameters by remember(player) { mutableStateOf(player.trackSelectionParameters) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    var commands by remember(player) { mutableStateOf(player.availableCommands) }
    var status by remember(player) { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(value: Tracks) { tracks = value; status = null }
            override fun onTrackSelectionParametersChanged(value: TrackSelectionParameters) { parameters = value }
            override fun onPlaybackParametersChanged(value: PlaybackParameters) { speed = value.speed }
            override fun onAvailableCommandsChanged(value: Player.Commands) { commands = value }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val groups = remember(tracks) { playbackTrackGroups(tracks) }
    val canSelect = commands.contains(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Playback settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Choose from supported tracks in the current video. Download quality is selected separately.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Playback speed", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(.5f, .75f, 1f).forEach { value ->
                FilterChip(
                    selected = speed == value,
                    enabled = commands.contains(Player.COMMAND_SET_SPEED_AND_PITCH),
                    onClick = { player.setPlaybackSpeed(value) },
                    label = { Text("${value}×") }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1.25f, 1.5f, 2f).forEach { value ->
                FilterChip(
                    selected = speed == value,
                    enabled = commands.contains(Player.COMMAND_SET_SPEED_AND_PITCH),
                    onClick = { player.setPlaybackSpeed(value) },
                    label = { Text("${value}×") }
                )
            }
        }
        PlaybackTrackType.entries.forEach { type ->
            HorizontalDivider()
            Text(when (type) {
                PlaybackTrackType.VIDEO -> "Playback quality"
                PlaybackTrackType.AUDIO -> "Audio track"
                PlaybackTrackType.CAPTIONS -> "Media subtitles"
            }, style = MaterialTheme.typography.titleMedium)
            val nativeType = type.nativeType()
            val disabled = nativeType in parameters.disabledTrackTypes
            val manual = hasCurrentPlaybackTrackOverride(groups, type, parameters.overrides.keys)
            PlaybackTrackRadio("Auto", selected = !disabled && !manual, enabled = canSelect) {
                player.trackSelectionParameters = automaticPlaybackTrackParameters(player.trackSelectionParameters, type)
                status = null
            }
            if (type == PlaybackTrackType.CAPTIONS) {
                PlaybackTrackRadio("Off", selected = disabled, enabled = canSelect) {
                    player.trackSelectionParameters = disabledMediaSubtitleParameters(player.trackSelectionParameters)
                    status = null
                }
            }
            val choices = playbackTrackChoices(groups, type)
            choices.forEach { choice ->
                val override = parameters.overrides[choice.group]
                val selected = !disabled && override?.trackIndices?.contains(choice.trackIndex) == true
                PlaybackTrackRadio(
                    choice.label + if (choice.selected) " • Playing" else "",
                    selected = selected,
                    enabled = canSelect
                ) {
                    val next = selectedPlaybackTrackParameters(player.trackSelectionParameters, player.currentTracks, choice)
                    if (next != null) {
                        player.trackSelectionParameters = next
                        status = null
                    } else {
                        tracks = player.currentTracks
                        status = "This track is no longer available. Choose a current track."
                    }
                }
            }
            if (choices.isEmpty()) Text(
                when (type) {
                    PlaybackTrackType.VIDEO -> "No selectable video tracks yet."
                    PlaybackTrackType.AUDIO -> "No selectable audio tracks yet."
                    PlaybackTrackType.CAPTIONS -> "No embedded or imported subtitle tracks available."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (type == PlaybackTrackType.CAPTIONS) Text(
                "These controls affect embedded and imported subtitle tracks. Generated speech captions use the subtitle tools.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PlaybackTrackRadio(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled, Role.RadioButton, onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun playbackTrackGroups(tracks: Tracks): List<PlaybackTrackGroup<TrackGroup>> =
    tracks.groups.mapNotNull { group ->
        val type = when (group.type) {
            C.TRACK_TYPE_VIDEO -> PlaybackTrackType.VIDEO
            C.TRACK_TYPE_AUDIO -> PlaybackTrackType.AUDIO
            C.TRACK_TYPE_TEXT -> PlaybackTrackType.CAPTIONS
            else -> return@mapNotNull null
        }
        PlaybackTrackGroup(group.mediaTrackGroup, type, (0 until group.length).map { index ->
            PlaybackTrackDescription(
                playbackTrackLabel(group.getTrackFormat(index), type, index),
                group.isTrackSupported(index),
                group.isTrackSelected(index)
            )
        })
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun selectedPlaybackTrackParameters(
    current: TrackSelectionParameters,
    tracks: Tracks,
    choice: PlaybackTrackChoice<TrackGroup>
): TrackSelectionParameters? {
    val valid = validPlaybackTrackChoice(choice, playbackTrackGroups(tracks)) ?: return null
    return current.buildUpon().setTrackTypeDisabled(valid.type.nativeType(), false)
        .setOverrideForType(TrackSelectionOverride(valid.group, valid.trackIndex)).build()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun automaticPlaybackTrackParameters(current: TrackSelectionParameters, type: PlaybackTrackType): TrackSelectionParameters =
    current.buildUpon().clearOverridesOfType(type.nativeType()).setTrackTypeDisabled(type.nativeType(), false).build()

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun disabledMediaSubtitleParameters(current: TrackSelectionParameters): TrackSelectionParameters =
    current.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()

private fun PlaybackTrackType.nativeType(): Int = when (this) {
    PlaybackTrackType.VIDEO -> C.TRACK_TYPE_VIDEO
    PlaybackTrackType.AUDIO -> C.TRACK_TYPE_AUDIO
    PlaybackTrackType.CAPTIONS -> C.TRACK_TYPE_TEXT
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun playbackTrackLabel(format: Format, type: PlaybackTrackType, index: Int): String {
    val parts = mutableListOf<String>()
    if (type == PlaybackTrackType.VIDEO) {
        if (format.height > 0) parts += "${format.height}p"
        if (format.frameRate > 0) parts += "${format.frameRate.roundToInt()} fps"
        if (format.bitrate > 0) parts += "${format.bitrate / 1000} kbps"
    } else {
        format.label?.trim()?.takeIf(String::isNotBlank)?.let { parts += it.take(100) }
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            val language = Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault())
            if (language.isNotBlank() && parts.none { part -> part.equals(language, true) }) parts += language.take(60)
        }
        if (type == PlaybackTrackType.AUDIO && format.channelCount > 0) parts += "${format.channelCount} ch"
    }
    if (parts.isEmpty()) parts += "Track ${index + 1}"
    return parts.joinToString(" • ")
}
