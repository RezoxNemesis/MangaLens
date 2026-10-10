package com.mangalens.download

import com.mangalens.ui.video.MediaRequestContext
import com.mangalens.ui.video.SubtitleFragmentSources
import okhttp3.Request
import okhttp3.Dns
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Authored UNRUN: actual ASR-selected client rejects private literals before DNS/connect. */
class OriginalHlsAsrConsumerTest {
    @Test fun actualAsrConsumerCannotFetchPrivateLiteralThroughGenericClient() {
        val source = CapturedHlsTrackSource("https://fixture.invalid/audio.m3u8", "hls-a", "audio/mp4", "m3u8_native")
        val body = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\naudio.m4s\n#EXT-X-ENDLIST"
        val plan = OriginalHlsVodPlaylist.capture(source, source.sourceUrl, "application/vnd.apple.mpegurl", body.toByteArray(), 4_000_000)
        val dns = AtomicInteger()
        val client = SubtitleFragmentSources.clientFor(plan, MediaRequestContext(plan.sourceUrl)).newBuilder()
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> {
                dns.incrementAndGet(); throw AssertionError("Private target reached DNS before HLS admission")
            } }).build()
        for (url in listOf("https://127.0.0.1/private", "https://[::1]/private", "http://10.0.0.1/private")) {
            val failure = runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException || failure is MediaSourceException)
        }
        assertEquals(0, dns.get())
    }
}
