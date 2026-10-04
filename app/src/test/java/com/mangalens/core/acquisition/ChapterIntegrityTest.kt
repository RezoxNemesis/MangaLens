package com.mangalens.core.acquisition
import org.junit.Assert.*
import org.junit.Test
class ChapterIntegrityTest {
    @Test fun hiddenCarouselSlidesRemainInSourceOrderWithoutCaptchaOrAds() {
        val pages = GenericMangaSourceAdapter().parse("""<div class="chapter-reader">
          <div class="slide"><img data-src="/chapter/1.webp"></div>
          <div class="slide" style="display:none"><img data-src="/chapter/2.webp"></div>
          <img src="/recaptcha.png"><img src="/placeholder.gif"><img src="/challenge.png" alt="captcha">
          <div class="advertisement"><img src="/content.jpg"></div>
          <img src="/chapter/1.webp">
        </div>""", "https://fixture.example/chapter/1").pageUrls
        assertEquals(listOf("https://fixture.example/chapter/1.webp", "https://fixture.example/chapter/2.webp"), pages)
    }
    @Test fun mangaDexManifestHasEveryPageInPublishedOrder() {
        val source = "https://mangadex.org/chapter/11111111-2222-3333-4444-555555555555/1"
        assertNotNull(MangaDexChapterSource.endpoint(source))
        assertNull(MangaDexChapterSource.endpoint(source.replace("mangadex.org", "mangadex.org.evil.example")))
        val parsed = MangaDexChapterSource.parse("""{"result":"ok","baseUrl":"https://uploads.example","chapter":{"hash":"0123456789abcdef0123456789abcdef","data":["1-a.jpg","2-b.jpg","../secret"]}}""")!!
        assertEquals(2, parsed.pageUrls.size)
        assertTrue(parsed.pageUrls[1].endsWith("/2-b.jpg"))
    }
}
