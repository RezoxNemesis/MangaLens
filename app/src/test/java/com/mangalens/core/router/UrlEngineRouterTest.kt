package com.mangalens.core.router

import com.mangalens.core.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class UrlEngineRouterTest {
    private lateinit var router: UrlEngineRouter

    @Before
    fun setUp() {
        router = UrlEngineRouter()
    }

    @Test
    fun testVideoStreamClassification() {
        assertEquals(ContentType.VIDEO_STREAM, router.classifyUrl("https://stream.example.com/live/index.m3u8"))
        assertEquals(ContentType.VIDEO_STREAM, router.classifyUrl("https://video.example.com/watch?id=12345"))
        assertEquals(ContentType.VIDEO_STREAM, router.classifyUrl("https://example.com/8kb89/video/title"))
        assertEquals(ContentType.VIDEO_STREAM, router.classifyUrl("https://www.youtube.com/watch?v=12345"))
        assertEquals(ContentType.GENERIC_WEB, router.classifyUrl("https://example.com/articles/video-game-design"))
    }

    @Test
    fun testMangaChapterClassification() {
        assertEquals(ContentType.IMAGE_CHAPTER, router.classifyUrl("https://mangadex.org/chapter/123456/1"))
        assertEquals(ContentType.IMAGE_CHAPTER, router.classifyUrl("https://site.example/manga/title/chapter-1"))
        assertEquals(ContentType.IMAGE_CHAPTER, router.classifyUrl("https://site.example/comic/title/ep/12"))
        assertEquals(ContentType.IMAGE_CHAPTER, router.classifyUrl("https://site.example/manhwa/title/episode/4"))
        assertEquals(ContentType.IMAGE_CHAPTER, router.classifyUrl("https://site.example/manhua/title/1"))
    }

    @Test
    fun testGenericWebFallback() {
        assertEquals(ContentType.GENERIC_WEB, router.classifyUrl("https://en.wikipedia.org/wiki/Main_Page"))
    }
}
