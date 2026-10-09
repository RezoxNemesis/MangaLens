package com.mangalens.download

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** An application interceptor serves fixture HTML without sockets, DNS, TLS or provider requests. */
class MediaResolverProviderFallbackTest {
    @Test fun suppliedYoutubeShortUrlCannotTurnItsProviderFailureIntoAThumbnailDownload() {
        assertThumbnailDoesNotReplaceFailure("https://youtu.be/RzasqVwpLOA?si=vn3VhAdIZKeUXgZE")
    }

    @Test fun shortSocialSharePathAlsoPreservesItsNativeFailure() {
        assertThumbnailDoesNotReplaceFailure("https://fb.watch/public-share-id/")
    }

    @Test fun genericChapterImagesRemainResolvable() {
        val client = thumbnailClient()
        try {
            val resolved = MediaLinkResolver(client).resolve("https://chapter.example/63/1")!!
            assertEquals("image/jpeg", resolved.mimeType)
            assertEquals("https://cdn.example/poster.jpg", resolved.url)
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    private fun assertThumbnailDoesNotReplaceFailure(source: String) {
        val client = thumbnailClient()
        val original = MediaSourceException.fromFailures(listOf(IOException("Sign in to confirm you're not a bot")))
        val extractor = SiteMediaExtractor { _, _ -> throw original }
        try {
            val failure = assertThrows(MediaSourceException::class.java) { MediaLinkResolver(client, extractor).resolve(source) }
            assertSame("HTML poster must not replace the actual extractor failure", original, failure)
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    private fun thumbnailClient() = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("<meta property='og:image' content='https://cdn.example/poster.jpg'>".toResponseBody())
            .build()
    }.build()
}
