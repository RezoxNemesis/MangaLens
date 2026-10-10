package com.mangalens.core.router

import org.junit.Assert.*
import org.junit.Test

class MangaLensWidgetEntryPolicyTest {
    @Test fun ExactRecentKeyAndDownloadIdBecomeOnlyNativeEntries() {
        val key = "a".repeat(64)
        assertEquals(MangaLensAppEntry.RecentVideo(key), MangaLensAppEntryPolicy.parse("mangalens://recent-video?key=$key"))
        assertEquals(MangaLensAppEntry.Downloads("native-1_a"), MangaLensAppEntryPolicy.parse("mangalens://downloads?focus=native-1_a"))
    }
    @Test fun SourceCookieAndDuplicateParametersCannotAuthorizePlayback() {
        val key = "a".repeat(64)
        listOf("mangalens://recent-video?key=$key&url=https%3A%2F%2Fexample.org", "mangalens://recent-video?key=$key&key=$key",
            "mangalens://recent-video?cookie=session", "mangalens://downloads?focus=..%2Fprivate").forEach { assertNull(it, MangaLensAppEntryPolicy.parse(it)) }
    }
    @Test fun MalformedForeignAuthorityPathFragmentAndPortAreRejected() {
        listOf("mangalens://recent-video/path?key=" + "a".repeat(64), "mangalens://downloads:80?focus=x", "mangalens://downloads?focus=x#grant",
            "mangalens://recent-video?key=%ZZ", "https://recent-video?key=" + "a".repeat(64), "mangalens://downloads?focus=%FF").forEach { assertNull(it, MangaLensAppEntryPolicy.parse(it)) }
    }
    @Test fun ExistingReaderAndEditableOrezEntriesRetainTheirMeaning() {
        assertEquals(MangaLensAppEntry.Reader("b".repeat(32)), MangaLensAppEntryPolicy.parse("mangalens://reader?chapter=" + "b".repeat(32)))
        assertEquals(MangaLensAppEntry.Orez("Explain this page"), MangaLensAppEntryPolicy.parse("mangalens://orez?request=Explain%20this%20page"))
    }
}
