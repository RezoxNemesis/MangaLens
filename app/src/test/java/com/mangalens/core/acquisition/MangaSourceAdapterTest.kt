package com.mangalens.core.acquisition

import org.junit.Assert.*
import org.junit.Test

class MangaSourceAdapterTest {
    private val adapter = GenericMangaSourceAdapter()
    @Test fun extractsOnlyReaderImagesAndOrdersChapters() {
        val result = adapter.parse("""
          <title>Fixture manga</title><img src="/logo.png">
          <div class="reading-content">
            <img data-src="/page1.png"><img src="/page2.webp"><img src="/page1.png"><img src="file:///secret">
            <img src="https://www.google.com/recaptcha/api2/logo_48.png">
            <img src="https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g/captcha.png">
          </div>
          <a href="/chapter-10">Chapter 10</a><a href="/chapter-2.5">Chapter 2.5</a><a href="/chapter-2">Chapter 2</a>
          <a href="https://other.example/chapter-1">Chapter 1</a><a href="javascript:alert(1)">Chapter 7</a>
        """.trimIndent(), "https://fixture.example/manga/title")
        assertEquals("Fixture manga", result.title)
        assertEquals(listOf("https://fixture.example/page1.png", "https://fixture.example/page2.webp"), result.pageUrls)
        assertEquals(listOf("Chapter 2", "Chapter 2.5", "Chapter 10"), result.chapters.map { it.title })
    }
    @Test fun unknownImageHeavyWebPageNeedsRenderedFallback() {
        assertTrue(adapter.parse("<img src='/photo.png'>", "https://fixture.example/article").pageUrls.isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedHtmlIsRejected() {
        adapter.parse("x".repeat(1_500_001), "https://fixture.example/chapter/1")
    }
}
