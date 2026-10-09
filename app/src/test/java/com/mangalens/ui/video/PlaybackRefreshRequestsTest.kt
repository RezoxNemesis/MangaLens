package com.mangalens.ui.video

import com.mangalens.download.ResolvedMediaLink
import org.junit.Assert.*
import org.junit.Test

class PlaybackRefreshRequestsTest {
    @Test fun sameSignedStreamAndHeadersStillRestartErroredPlayback() {
        val requests = PlaybackRefreshRequests()
        val request = requests.begin()
        val previous = PlaybackStreamIdentity("https://cdn.example/master.m3u8?token=fixture", mapOf("Referer" to "https://example.com/watch"))
        val refreshed = previous.copy(headers = previous.headers.toMap())
        var prepareCalls = 1
        var playCalls = 1
        var applied = false
        assertTrue(requests.publish(request, previous, refreshed, { applied = true }, { prepareCalls++; playCalls++ }))
        assertTrue(applied)
        assertEquals(2, prepareCalls)
        assertEquals(2, playCalls)
    }

    @Test fun equivalentHeaderNamesStillRestartTheSamePlaybackRequest() {
        val requests = PlaybackRefreshRequests()
        val request = requests.begin()
        val previous = PlaybackStreamIdentity(
            "https://cdn.example/video.mp4", mapOf("Referer" to "https://example.com/watch"),
            "https://cdn.example/audio.m4a", mapOf("User-Agent" to "fixture")
        )
        val refreshed = previous.copy(
            headers = mapOf("referer" to "https://example.com/watch"),
            audioHeaders = mapOf("user-agent" to "fixture")
        )
        var prepareCalls = 0
        requests.publish(request, previous, refreshed, {}, { prepareCalls++ })
        assertEquals("Header spelling must match openHttp's case-insensitive request key", 1, prepareCalls)
    }

    @Test fun changedStreamUsesSourceReplacementAndCancelledRefreshCannotRestartIt() {
        val requests = PlaybackRefreshRequests()
        val request = requests.begin()
        val previous = PlaybackStreamIdentity("https://cdn.example/old.mp4")
        val refreshed = PlaybackStreamIdentity("https://cdn.example/new.mp4")
        var applied = 0
        var restarted = 0
        assertTrue(requests.publish(request, previous, refreshed, { applied++ }, { restarted++ }))
        assertEquals(1, applied)
        assertEquals(0, restarted)
        requests.cancel()
        assertFalse(requests.publish(request, refreshed, refreshed, { applied++ }, { restarted++ }))
        assertEquals(1, applied)
        assertEquals(0, restarted)
    }

    @Test fun refreshedThumbnailIsRejectedWhileVideoAndAdaptiveSourcesAreAccepted() {
        assertFalse(isPlayableRefresh(ResolvedMediaLink("https://example.com/poster.jpg", "image/jpeg")))
        for (mime in listOf("video/mp4", "video/webm", "application/x-mpegURL", "application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml")) {
            assertTrue(isPlayableRefresh(ResolvedMediaLink("https://example.com/opaque", mime)))
        }
    }

    @Test fun cancelledRequestCannotPublishOrFinishItsReplacement() {
        val requests = PlaybackRefreshRequests()
        val old = requests.begin()
        requests.cancel()
        val replacement = requests.begin()
        assertFalse(requests.isCurrent(old))
        assertFalse(requests.finish(old))
        assertTrue(requests.isCurrent(replacement))
        assertTrue(requests.finish(replacement))
        assertFalse(requests.isCurrent(replacement))
    }

    @Test fun replacingSourceKeepsCancellationOwnershipSeparate() {
        val oldSource = PlaybackRefreshRequests()
        val newSource = PlaybackRefreshRequests()
        val current = newSource.begin()
        oldSource.begin()
        oldSource.cancel()
        assertTrue(newSource.isCurrent(current))
    }

    @Test fun sourceHandoffUsesSourcePageAndRejectsEmbeddedCredentials() {
        assertEquals("https://example.com/watch", playbackSourcePage("https://example.com/watch", "https://cdn.example/video.mp4"))
        assertEquals("https://cdn.example/video.mp4", playbackSourcePage("https://user:secret@example.com/watch", "https://cdn.example/video.mp4"))
        assertNull(playbackSourcePage("javascript:alert(1)", "content://private/video"))
    }
}
