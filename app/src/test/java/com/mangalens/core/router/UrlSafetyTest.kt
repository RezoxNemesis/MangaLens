package com.mangalens.core.router

import com.mangalens.core.model.ContentType
import org.junit.Assert.*
import org.junit.Test

class UrlSafetyTest {
    @Test fun rejectsNativeAndCredentialUrls() {
        listOf("file:///data/user/0/com.mangalens/secret", "javascript:alert(1)", "intent://x", "https://user:password@example.org/", "https:///chapter/1").forEach {
            assertFalse(it, UrlEngineRouter.isSafeWebUrl(it))
        }
    }
    @Test fun queryTermsCannotMisrouteWebPage() {
        assertEquals(ContentType.GENERIC_WEB, UrlEngineRouter().classifyUrl("https://example.org/search?q=manga+video+stream.mp4"))
    }
    @Test fun acceptsDirectMediaWithQuery() {
        assertEquals(ContentType.VIDEO_STREAM, UrlEngineRouter().classifyUrl("https://cdn.example.org/film.mp4?token=public"))
    }
}
