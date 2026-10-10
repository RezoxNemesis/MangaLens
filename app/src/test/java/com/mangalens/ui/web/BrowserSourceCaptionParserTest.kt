package com.mangalens.ui.web

import com.mangalens.download.ProviderCaptionDiscovery
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserSourceCaptionParserTest {
    private val page="https://source.invalid/watch"
    private fun owner()=BrowserCaptionPageOwner("tab1",7,page,"a".repeat(32))
    private fun data()=JSONObject().put("schemaVersion",1).put("status","ready").put("pageUrl",page)
        .put("documentNonce","b".repeat(32)).put("elementId","c".repeat(32)).put("sourceVersion","d".repeat(32))
        .put("currentSrc","blob:https://source.invalid/actual-video").put("audioTrackKey","element-lang:en")
        .put("audioLanguage","en").put("audioBinding","element-language").put("originalLanguage","en")
        .put("readyState",4).put("currentTimeMs",1100).put("durationMs",8000).put("playbackRate",1.0)
        .put("paused",true).put("seeking",false).put("tracks",JSONArray().put(track("en")))
    private fun track(lang:String)=JSONObject().put("url","https://source.invalid/$lang.vtt").put("language",lang)
        .put("kind","MANUAL").put("format","VTT").put("originalAutomatic",false)
    private fun raw(data:JSONObject)=JSONObject.quote(data.toString())

    @Test fun actualWebViewDoubleEncodedMetadataProducesACapturedSourceAndClock() {
        val scope=requireNotNull(BrowserSourceCaptionParser.capture(raw(data()),owner()))
        assertEquals(1100L,scope.captured.positionMs)
        assertEquals("blob:https://source.invalid/actual-video",scope.captured.currentSrc)
        assertEquals(page,scope.inventory.sourcePageUrl)
        assertEquals("en",scope.inventory.originalLanguage)
        assertNull("Publisher element language is not an observed dub",scope.inventory.selectedAudioLanguage)
        assertEquals("en",ProviderCaptionDiscovery.select(scope.inventory,"auto")?.language)
        assertEquals(scope.captured,BrowserSourceCaptionParser.clock(raw(data())))
    }
    @Test fun actualSelectedAudioPrecedesPublisherOriginalLanguage() {
        val payload=data().put("audioBinding","observed-track").put("audioTrackKey","audio-hi")
            .put("audioLanguage","hi").put("tracks",JSONArray().put(track("en")).put(track("hi")))
        val scope=requireNotNull(BrowserSourceCaptionParser.capture(raw(payload),owner()))
        assertEquals("hi",scope.inventory.selectedAudioLanguage)
        assertEquals("hi",ProviderCaptionDiscovery.select(scope.inventory,"auto")?.language)
    }
    @Test fun anotherDocumentOrUnboundAudioCannotCreateASource() {
        assertNull(BrowserSourceCaptionParser.capture(raw(data().put("pageUrl","https://other.invalid/watch")),owner()))
        assertNull(BrowserSourceCaptionParser.capture(raw(data().put("audioBinding","unknown")),owner()))
        assertNull(BrowserSourceCaptionParser.capture(raw(data().put("audioLanguage",JSONObject.NULL)),owner()))
        assertNull(BrowserSourceCaptionParser.capture(raw(data().put("status","unavailable")),owner()))
    }
    @Test fun malformedOrUnsupportedClockNeverInventsAPlaybackPosition() {
        assertNull(BrowserSourceCaptionParser.clock(raw(data().put("currentTimeMs","1100"))))
        assertNull(BrowserSourceCaptionParser.clock(raw(data().put("durationMs",JSONObject.NULL))))
        assertNull(BrowserSourceCaptionParser.clock(raw(data().put("playbackRate",0))))
        assertNull(BrowserSourceCaptionParser.clock(raw(data().put("currentTimeMs",9000))))
        assertNull(BrowserSourceCaptionParser.clock(raw(data().put("documentNonce","not-opaque"))))
    }
    @Test fun externalOrTranslatedTracksCannotSupplyTheCurrentElementCaptions() {
        val external=data().put("tracks",JSONArray().put(track("en").put("url","https://other.invalid/en.vtt")))
        assertNull(BrowserSourceCaptionParser.capture(raw(external),owner()))
        val translated=data().put("tracks",JSONArray().put(track("en").put("url","https://source.invalid/en.vtt?tlang=hi")))
        assertNull(BrowserSourceCaptionParser.capture(raw(translated),owner()))
    }
    @Test fun overLimitInventoriesAreRejectedWithoutPartialSelection() {
        val tracks=JSONArray(); repeat(129){tracks.put(track("en"))}
        assertNull(BrowserSourceCaptionParser.capture(raw(data().put("tracks",tracks)),owner()))
        assertNull(BrowserSourceCaptionParser.capture("x".repeat(512*1024+1),owner()))
    }
}
