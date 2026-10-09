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
    @Test fun userSuppliedDemonicScansMarkupYieldsAllFifteenChapterPagesWithoutLogosOrAds() {
        val html = requireNotNull(javaClass.getResourceAsStream("/source-fixtures/demonics-kidnapped-dragons-63.html"))
            .bufferedReader().use { it.readText() }
        val result = adapter.parse(html, "https://demonicscans.org/title/Kidnapped-Dragons/chapter/63/1")
        assertEquals(15, result.pageUrls.size)
        assertEquals((0..14).map { "https://cdn.demoniclibs.com/Kidnapped%20Dragons/63/$it.jpg?v=1791383844" }, result.pageUrls)
    }
    @Test fun knownProviderImageClassDoesNotGrantUnrelatedPagesAReader() {
        val html = "<img class='imgholder' alt='Kidnapped Dragons Chapter 63 0' src='https://cdn.demoniclibs.com/Kidnapped Dragons/63/0.jpg'>"
        assertTrue(adapter.parse(html, "https://demonicscans.org/article/news").pageUrls.isEmpty())
        assertTrue(adapter.parse(html, "https://demonicscans.org.evil.example/title/Kidnapped-Dragons/chapter/63/1").pageUrls.isEmpty())
    }
    @Test fun readerPathsWithSpacesAreNormalizedBeforeUrlValidation() {
        val result = adapter.parse("<div class='reading-content'><img src='/A Story/1.jpg'></div>", "https://fixture.example/chapter/1")
        assertEquals(listOf("https://fixture.example/A%20Story/1.jpg"), result.pageUrls)
    }
    @Test fun providerImagesMustMatchTheSelectedTitleChapterCaptionAndCdn() {
        val result = adapter.parse("""
            <img class='imgholder' alt='Kidnapped Dragons Chapter 63 0' src='https://cdn.demoniclibs.com/Kidnapped Dragons/63/0.jpg'>
            <img class='imgholder' alt='Kidnapped Dragons Chapter 63 1' src='https://cdn.demoniclibs.com.evil.example/Kidnapped Dragons/63/1.jpg'>
            <img class='imgholder' alt='Kidnapped Dragons Chapter 63 2' src='https://cdn.demoniclibs.com/Other Story/63/2.jpg'>
            <img class='imgholder' alt='Kidnapped Dragons Chapter 63 3' src='https://cdn.demoniclibs.com/Kidnapped Dragons/64/3.jpg'>
            <img class='imgholder' alt='Kidnapped Dragons Chapter 63 4' src='https://cdn.demoniclibs.com/Kidnapped Dragons/63/5.jpg'>
            <img class='imgholder' alt='Advertisement' src='https://cdn.demoniclibs.com/Kidnapped Dragons/63/6.jpg'>
        """.trimIndent(), "https://demonicscans.org/title/Kidnapped-Dragons/chapter/63/1")
        assertEquals(listOf("https://cdn.demoniclibs.com/Kidnapped%20Dragons/63/0.jpg"), result.pageUrls)
    }
    @Test fun urlNormalizationKeepsEncodedPathsQueriesAndRejectsCredentials() {
        val result = adapter.parse("""
            <div class='reading-content'>
                <img src='https://fixture.example/A%20Story/1.jpg?v=1&amp;kind=long#page'>
                <img src='https://user:password@fixture.example/secret.jpg'>
            </div>
        """.trimIndent(), "https://fixture.example/chapter/1")
        assertEquals(listOf("https://fixture.example/A%20Story/1.jpg?v=1&kind=long"), result.pageUrls)
    }
    @Test fun mixedCasePastedHostRetainsSameSiteChapterLinks() {
        val result = adapter.parse("<a href='/chapter-2'>Chapter 2</a>", "https://Fixture.Example/manga/title")
        assertEquals(listOf(SourceChapter("Chapter 2", "https://fixture.example/chapter-2")), result.chapters)
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedHtmlIsRejected() {
        adapter.parse("x".repeat(1_500_001), "https://fixture.example/chapter/1")
    }
}
