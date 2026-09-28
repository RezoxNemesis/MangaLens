package com.mangalens.core

import com.mangalens.core.acquisition.ClipboardUrlResolver
import com.mangalens.core.acquisition.MediaMode
import com.mangalens.core.orez.OrezDomain
import com.mangalens.core.orez.OrezIntentRouterV12
import com.mangalens.engine.OcrTextProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V12SubsystemTest {
    @Test
    fun routerHandlesHinglishTranslationTypos() {
        val result = OrezIntentRouterV12().classify("yo brother plz transalte this in hindi")
        assertEquals(OrezDomain.TRANSLATION, result.domain)
        assertTrue(result.confidence > 0f)
    }

    @Test
    fun relativeChapterResolvesAgainstActivePage() {
        val result = ClipboardUrlResolver().resolve(
            "l-an-apocalypse-shelter/chapter-633/",
            MediaMode.MANGA,
            "https://example.com/reader/chapter-632"
        )
        assertTrue(result.normalized!!.contains("l-an-apocalypse-shelter/chapter-633"))
        assertTrue(result.wasRelative)
    }

    @Test
    fun ocrPipelineRepairsHinglishWithoutAndroidGraphics() {
        val processor = OcrTextProcessor()
        val text = processor.normalizeHinglish("bhai nhi rukega")
        assertTrue(text.contains("nahi"))
        assertTrue(text.contains("rukega"))
    }
}
