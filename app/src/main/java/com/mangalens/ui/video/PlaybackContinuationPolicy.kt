package com.mangalens.ui.video

internal const val PLAYBACK_SEEK_INCREMENT_MS = 10_000L

internal data class PlaybackContinuationSnapshot(
    val positionMs: Long,
    val durationMs: Long?,
    val live: Boolean,
    val dynamic: Boolean,
    val seekable: Boolean
)

internal sealed interface PlaybackReplacementStart {
    data object Superseded : PlaybackReplacementStart
    data object Default : PlaybackReplacementStart
    data class Position(val positionMs: Long) : PlaybackReplacementStart
}

/** Only a refresh of the still-current source can carry its actual VOD position forward. */
internal fun playbackReplacementStart(
    currentRevision: Long,
    refreshFromRevision: Long?,
    actual: PlaybackContinuationSnapshot?,
    savedPositionMs: Long
): PlaybackReplacementStart {
    if (refreshFromRevision != null && refreshFromRevision != currentRevision) return PlaybackReplacementStart.Superseded
    if (refreshFromRevision == null) return if (savedPositionMs >= 0) PlaybackReplacementStart.Position(savedPositionMs)
        else PlaybackReplacementStart.Default
    if (actual == null || actual.positionMs < 0 || actual.live || actual.dynamic || !actual.seekable) return PlaybackReplacementStart.Default
    // Live window coordinates and unknown/zero duration are not valid VOD bounds.
    val upperBound = actual.durationMs?.takeIf { it > 0 }
    return PlaybackReplacementStart.Position(if (upperBound == null) actual.positionMs else actual.positionMs.coerceAtMost(upperBound))
}
