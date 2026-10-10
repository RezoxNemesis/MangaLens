package com.mangalens.download

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Captured source duration is independent authority; these controls do not claim real encoded playback. */
class OriginalHlsCapturedDurationTest {
    private val source = CapturedHlsTrackSource("https://fixture.invalid/video/index.m3u8", "hls-v720", "video/mp4", "m3u8_native")
    private val playlist = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:2
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:2,
        one.m4s
        #EXTINF:2,
        two.m4s
        #EXT-X-ENDLIST
    """.trimIndent()
    private fun capture(duration: Long = 4_000_000L) = OriginalHlsVodPlaylist.capture(source,
        source.sourceUrl, "application/vnd.apple.mpegurl", playlist.toByteArray(), duration)

    @Test fun shortenedFinitePlaylistCannotReplaceLongerCapturedSource() {
        assertTrue(runCatching { capture(8_000_000L) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun longerPlaylistCannotReplaceShorterCapturedSource() {
        assertTrue(runCatching { capture(1_000_000L) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun fixedRoundingBoundaryPreservesCapturedDurationWithoutRewritingReceipt() {
        for (duration in listOf(3_750_000L, 4_000_000L, 4_250_000L)) {
            val plan = capture(duration)
            assertEquals(duration, plan.durationUs)
            assertEquals(4_000_000L, plan.hls!!.observedDurationUs)
            val replay = OriginalFragmentPlan.fromJson(JSONObject(plan.toJson().toString()))
            assertEquals(plan, replay)
            assertEquals(plan.sha256(), replay.sha256())
        }
    }
    @Test fun oneMicrosecondBeyondEitherFixedBoundaryIsRejected() {
        for (duration in listOf(3_749_999L, 4_250_001L))
            assertTrue(runCatching { capture(duration) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun coldReceiptCannotIncreaseRootDurationBehindAnUnchangedPlaylist() {
        val json = JSONObject(capture().toJson().toString()).put("durationUs", 8_000_000L)
        assertTrue(runCatching { OriginalFragmentPlan.fromJson(json) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun selfConsistentShortenedFragmentsAndReceiptStillRequireCapturedRoot() {
        val full = capture()
        val shortened = full.copy(fragments = full.fragments.mapIndexed { i, f ->
            if (i == 0) f else f.copy(durationUs = 500_000L)
        }, hls = full.hls!!.copy(observedDurationUs = 1_000_000L))
        assertTrue(runCatching { shortened.captured() }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun arbitraryTimestampOffsetDoesNotAuthorizeAnIncompleteSelectedSource() {
        val track = OriginalMediaTrack(0, "video/avc", "h264", 1280, 720, durationUs = 4_000_000L)
        val shifted = VerifiedOriginalMedia(listOf(track), mapOf(0 to
            EncodedTrackAudit(100, 1_000, 9_000_000L, "synthetic", 5_000_000L, 40_000L)))
        OriginalMediaProbe.verifyTail(shifted, 8_000_000L)
        OriginalMediaProbe.verifyObservedSpan(shifted, track, 4_000_000L)
        assertTrue(runCatching { capture(8_000_000L) }.exceptionOrNull() is IllegalArgumentException)
    }
}
