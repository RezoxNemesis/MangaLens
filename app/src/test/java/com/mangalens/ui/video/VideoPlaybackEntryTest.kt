package com.mangalens.ui.video

import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackEntryTest {
    @Test fun exactEncodedWebUrlIsCapturedWithoutLosingSignedQuery() {
        val source = "https://video.example/clip?sig=a%2Bb&token=one+two"
        val parsed = parseVideoPlaybackEntry("mangalens://video?url=" + URLEncoder.encode(source, "UTF-8"))
        assertEquals(VideoPlaybackEntry.Url(source), parsed)
    }

    @Test fun opaqueSessionOnlyPermitsAnExactInternalIdentifier() {
        val id = "0123456789abcdef".repeat(2)
        assertEquals(VideoPlaybackEntry.Session(id), parseVideoPlaybackEntry("mangalens://video/session/$id"))
        for (suffix in listOf("../$id", "$id/", "$id?url=https://other.example", "bad", "a".repeat(33)))
            assertNull(suffix, parseVideoPlaybackEntry("mangalens://video/session/$suffix"))
    }

    @Test fun inputCannotGrantPrivateFileHeaderAudioOrCredentialAuthority() {
        for (source in listOf("file:///data/data/com.mangalens/private", "content://private/1",
            "https://user:secret@video.example/clip", "javascript:alert(1)", "https://video.example/\n")) {
            assertNull(source, parseVideoPlaybackEntry("mangalens://video?url=" + URLEncoder.encode(source, "UTF-8")))
        }
        val base = "mangalens://video?url=https%3A%2F%2Fvideo.example%2Fclip"
        for (suffix in listOf("&Cookie=secret", "&audio=https://other.example", "&url=https://other.example", "#secret"))
            assertNull(suffix, parseVideoPlaybackEntry(base + suffix))
    }

    @Test fun wrongRouteBlankMalformedOrOversizedInputsAreRejected() {
        for (value in listOf("", "mangalens://reader?url=https://video.example", "https://video.example",
            "mangalens://user@video?url=https://video.example", "mangalens://video?url=", "mangalens://video?url=%ZZ",
            "mangalens://video?url=" + "a".repeat(17_000))) assertNull(value.take(70), parseVideoPlaybackEntry(value))
    }
}
