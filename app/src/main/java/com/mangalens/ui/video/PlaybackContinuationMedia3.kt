package com.mangalens.ui.video

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource

/** Read on the player's application thread immediately before a source replacement. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun playbackContinuationSnapshot(player: Player): PlaybackContinuationSnapshot =
    PlaybackContinuationSnapshot(
        positionMs = player.currentPosition,
        durationMs = player.duration.takeIf { it > 0 },
        live = player.isCurrentMediaItemLive,
        dynamic = player.isCurrentMediaItemDynamic,
        seekable = player.isCurrentMediaItemSeekable
    )

/** Default is Media3's source-defined start, not the old live window's numeric offset. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun applyPlaybackReplacementStart(player: ExoPlayer, source: MediaSource, start: PlaybackReplacementStart): Boolean {
    val position = when (start) {
        PlaybackReplacementStart.Superseded -> return false
        PlaybackReplacementStart.Default -> C.TIME_UNSET
        is PlaybackReplacementStart.Position -> start.positionMs
    }
    player.setMediaSource(source, position)
    return true
}
