package com.mangalens.orez
import org.junit.Assert.*
import org.junit.Test
class OrezVideoResultCodecTest {
    @Test fun realMetadataSurvivesChatPersistenceWithoutInventedDates() {
        val results = OrezVideoResultCodec.parseSearch("""{"entries":[{"id":"abcdefghijk","title":"Manga recap","channel":"Fixture channel","duration":123,"thumbnails":[{"url":"https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"}]}]}""")
        assertEquals(1, results.size)
        assertNull(results.first().uploadDate)
        assertEquals(123, results.first().durationSeconds)
        assertEquals(results, OrezVideoResultCodec.fromMessage("Results" + OrezVideoResultCodec.MARKER + OrezVideoResultCodec.encode(results)))
    }
}
