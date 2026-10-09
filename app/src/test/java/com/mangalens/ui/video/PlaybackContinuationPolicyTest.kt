package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class PlaybackContinuationPolicyTest {
    @Test fun changedSignedUrlRetainsActualPositionInsteadOfTheNewUrlsEmptySavedPosition() {
        assertEquals(PlaybackReplacementStart.Position(43_750), decide(snapshot = snapshot(position = 43_750), saved = 0))
    }

    @Test fun unrelatedNewMediaUsesItsOwnSavedPosition() {
        assertEquals(PlaybackReplacementStart.Position(7_000), decide(expected = null, saved = 7_000))
        assertEquals(PlaybackReplacementStart.Position(0), decide(expected = null, saved = 0))
    }

    @Test fun staleRevisionCannotSelectAnyReplacementPosition() {
        assertEquals(PlaybackReplacementStart.Superseded, decide(expected = 6, revision = 7, saved = 25_000))
    }

    @Test fun liveDvrRefreshUsesNewDefaultInsteadOfOldWindowCoordinates() {
        assertEquals(PlaybackReplacementStart.Default, decide(snapshot = snapshot(position = 90_000, live = true)))
    }

    @Test fun dynamicTimelineRefreshUsesNewDefaultEvenIfNotMarkedLive() {
        assertEquals(PlaybackReplacementStart.Default, decide(snapshot = snapshot(dynamic = true)))
    }

    @Test fun unseekableRefreshUsesTheSourceDefault() {
        assertEquals(PlaybackReplacementStart.Default, decide(snapshot = snapshot(seekable = false)))
    }

    @Test fun knownPositiveVodDurationBoundsTheRetainedPosition() {
        assertEquals(PlaybackReplacementStart.Position(30_000), decide(snapshot = snapshot(position = 32_000, duration = 30_000)))
    }

    @Test fun unknownOrInvalidDurationDoesNotClampAValidActualPositionToZero() {
        listOf(null, -9223372036854775807L, -1L, 0L).forEach { duration ->
            assertEquals(PlaybackReplacementStart.Position(43_750), decide(snapshot = snapshot(duration = duration)))
        }
    }

    @Test fun zeroActualPositionDoesNotRestoreAnOlderSavedPositionForTheNewUrl() {
        assertEquals(PlaybackReplacementStart.Position(0), decide(snapshot = snapshot(position = 0), saved = 60_000))
    }

    @Test fun invalidActualOrSavedPositionsUseDefaultRatherThanAnInvalidSeek() {
        assertEquals(PlaybackReplacementStart.Default, decide(snapshot = snapshot(position = -1)))
        assertEquals(PlaybackReplacementStart.Default, decide(expected = null, saved = -1))
    }

    @Test fun missingPlaybackSnapshotCannotInventRefreshContinuity() {
        assertEquals(PlaybackReplacementStart.Default, decide(snapshot = null, saved = 60_000))
    }

    private fun decide(
        expected: Long? = 7,
        revision: Long = 7,
        snapshot: PlaybackContinuationSnapshot? = snapshot(),
        saved: Long = 0
    ) = playbackReplacementStart(revision, expected, snapshot, saved)

    private fun snapshot(
        position: Long = 43_750,
        duration: Long? = 90_000,
        live: Boolean = false,
        dynamic: Boolean = false,
        seekable: Boolean = true
    ) = PlaybackContinuationSnapshot(position, duration, live, dynamic, seekable)
}
