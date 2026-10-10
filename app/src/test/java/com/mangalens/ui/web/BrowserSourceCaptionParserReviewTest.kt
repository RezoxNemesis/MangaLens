package com.mangalens.ui.web

import com.mangalens.download.ProviderCaptionDiscovery
import com.mangalens.download.ProviderCaptionKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Typed boundary controls; these synthetic payloads do not claim actual browser execution. */
class BrowserSourceCaptionParserReviewTest {
    private val page = "https://source.invalid/watch"
    private val youtube = "https://www.youtube.com/watch?v=abcdefghijk"
    private fun owner(url: String = page) = BrowserCaptionPageOwner("tab1", 7, url, "a".repeat(32))
    private fun track(url: String = "https://source.invalid/en.vtt", language: String = "en") = JSONObject()
        .put("url", url).put("language", language).put("kind", "MANUAL").put("format", "VTT")
        .put("originalAutomatic", false)
    private fun payload(url: String = page) = JSONObject().put("schemaVersion", 1).put("status", "ready")
        .put("pageUrl", url).put("documentNonce", "b".repeat(32)).put("elementId", "c".repeat(32))
        .put("sourceVersion", "d".repeat(32)).put("currentSrc", "blob:https://source.invalid/actual-video")
        .put("audioTrackKey", "element-lang:en").put("audioLanguage", "en").put("audioBinding", "element-language")
        .put("originalLanguage", "en").put("readyState", 4).put("currentTimeMs", 1100).put("durationMs", 8000)
        .put("playbackRate", 1.0).put("paused", true).put("seeking", false).put("tracks", JSONArray().put(track()))
    private fun raw(payload: JSONObject) = JSONObject.quote(payload.toString())
    private fun youtubePayload() = payload(youtube).put("videoId", "abcdefghijk")
        .put("audioTrackKey", "en.4").put("audioBinding", "observed-track")
        .put("tracks", JSONArray().put(track("https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&fmt=vtt")))

    @Test fun callbackRequiresOneStrictJsonStringContainingOneObject() {
        val good = payload()
        assertNotNull(BrowserSourceCaptionParser.capture(raw(good), owner()))
        for (bad in listOf(good.toString(), "null", "123", "[${raw(good)}]", raw(good) + " null",
            JSONObject.quote("[]"), JSONObject.quote("{'status':'ready'}"), JSONObject.quote(good.toString() + " {}"))) {
            assertNull("Unencoded/lenient/trailing input was accepted", BrowserSourceCaptionParser.capture(bad, owner()))
        }
    }
    @Test fun duplicateFieldsCannotReplaceAnEarlierObservedIdentity() {
        val inner = payload().toString().dropLast(1) + ",\"audioLanguage\":\"hi\"}"
        assertNull(BrowserSourceCaptionParser.capture(JSONObject.quote(inner), owner()))
        val escapedDuplicate = payload().toString().dropLast(1) + ",\"audio\\u004canguage\":\"en\"}"
        assertNull(BrowserSourceCaptionParser.capture(JSONObject.quote(escapedDuplicate), owner()))
    }
    @Test fun coercibleStringsAndFractionalClockIntegersAreRejected() {
        for ((key, wrong) in listOf("schemaVersion" to "1", "readyState" to "4", "durationMs" to "8000",
            "playbackRate" to "1.0", "paused" to "true", "seeking" to 0, "currentTimeMs" to 1100.5)) {
            assertNull("Clock field $key was coerced", BrowserSourceCaptionParser.clock(raw(payload().put(key, wrong))))
        }
    }
    @Test fun finiteCurrentVideoClockMustStayWithinItsActualDuration() {
        for (bad in listOf(payload().put("readyState", 0), payload().put("readyState", 5), payload().put("durationMs", 0),
            payload().put("durationMs", 21_600_001), payload().put("durationMs", JSONObject.NULL),
            payload().put("currentTimeMs", -1), payload().put("currentTimeMs", 8001), payload().put("playbackRate", 17))) {
            assertNull(BrowserSourceCaptionParser.clock(raw(bad)))
        }
        assertNotNull(BrowserSourceCaptionParser.clock(raw(payload().put("currentTimeMs", 8000))))
    }
    @Test fun ownerMustBeANonemptyCurrentHttpsNavigationWithAnOpaqueWebViewToken() {
        val good = raw(payload())
        for (bad in listOf(owner().copy(tabId = ""), owner().copy(tabId = "x".repeat(129)), owner().copy(navigationEpoch = 0),
            owner().copy(webViewToken = "not-a-live-token"), owner().copy(pageUrl = "http://source.invalid/watch"))) {
            assertNull(BrowserSourceCaptionParser.capture(good, bad))
        }
        assertNull(BrowserSourceCaptionParser.capture(raw(payload("http://source.invalid/watch")), owner("http://source.invalid/watch")))
        assertNull(BrowserSourceCaptionParser.capture(raw(payload("https://source.invalid/watch#fragment")), owner("https://source.invalid/watch#fragment")))
        assertNull(BrowserSourceCaptionParser.capture(raw(payload("https://u:p@source.invalid/watch")), owner("https://u:p@source.invalid/watch")))
        assertNull(BrowserSourceCaptionParser.capture(raw(payload("https://source.invalid/bad path")), owner("https://source.invalid/bad path")))
    }
    @Test fun publisherLanguageBindingRequiresItsExactKeyAndOriginalLanguage() {
        assertNull(BrowserSourceCaptionParser.capture(raw(payload().put("audioTrackKey", "element-lang:hi")), owner()))
        assertNull(BrowserSourceCaptionParser.capture(raw(payload().put("originalLanguage", "hi")), owner()))
        assertNull(BrowserSourceCaptionParser.capture(raw(payload().put("originalLanguage", JSONObject.NULL)), owner()))
    }
    @Test fun unknownSelectedAudioCannotUseAnUnrelatedOriginalCaptionLanguage() {
        val onlyHindi = payload().put("tracks", JSONArray().put(track("https://source.invalid/hi.vtt", "hi")))
        assertNull(BrowserSourceCaptionParser.capture(raw(onlyHindi), owner()))
        val selectedHindi = onlyHindi.put("audioBinding", "observed-track").put("audioTrackKey", "actual-hi")
            .put("audioLanguage", "hi")
        assertEquals("hi", ProviderCaptionDiscovery.select(requireNotNull(BrowserSourceCaptionParser.capture(raw(selectedHindi), owner())).inventory, "auto")?.language)
    }
    @Test fun originalSameOriginTrackCannotAuthorizeExternalOrTranslatedCandidates() {
        val mixed = payload().put("tracks", JSONArray().put(track()).put(track("https://other.invalid/en.vtt"))
            .put(track("https://source.invalid/translated.vtt?tlang=hi")))
        val scope = requireNotNull(BrowserSourceCaptionParser.capture(raw(mixed), owner()))
        assertEquals(listOf("https://source.invalid/en.vtt"), scope.inventory.tracks.map { it.url })
        assertNull(BrowserSourceCaptionParser.capture(raw(payload().put("tracks", JSONArray().put(track("https://source.invalid/en.vtt?tlang=")))), owner()))
    }
    @Test fun malformedTrackTypesCannotSupplyAnInventory() {
        for (bad in listOf(track().put("language", 9), track().put("originalAutomatic", "false"),
            track().put("format", "UNKNOWN"), track().put("url", true))) {
            assertNull(BrowserSourceCaptionParser.capture(raw(payload().put("tracks", JSONArray().put(bad))), owner()))
        }
    }
    @Test fun genericHtmlOnlyAcceptsTheRealManualWebVttProducerShape() {
        for (bad in listOf(track().put("kind", "AUTOMATIC").put("originalAutomatic", true),
            track().put("originalAutomatic", true), track().put("format", "JSON3"), track().put("format", "SRT"))) {
            assertNull(BrowserSourceCaptionParser.capture(raw(payload().put("tracks", JSONArray().put(bad))), owner()))
        }
    }
    @Test fun rawUrlsAndCombinedMetadataRemainBoundedBeforeSelection() {
        val huge = track("https://source.invalid/" + "x".repeat(16_384))
        assertNull(BrowserSourceCaptionParser.capture(raw(payload().put("tracks", JSONArray().put(huge))), owner()))
        val many = JSONArray()
        repeat(16) { many.put(track("https://source.invalid/en.vtt?value=" + "x".repeat(9000) + it)) }
        assertNull(BrowserSourceCaptionParser.capture(raw(payload().put("tracks", many)), owner()))
    }
    @Test fun youtubePageResponseIdAndOriginalTimedtextAreBoundTogether() {
        val scope = requireNotNull(BrowserSourceCaptionParser.capture(raw(youtubePayload()), owner(youtube)))
        assertEquals("abcdefghijk", scope.inventory.videoId)
        assertEquals("en", scope.inventory.selectedAudioLanguage)
        assertEquals(1, scope.inventory.tracks.size)
        assertNull(BrowserSourceCaptionParser.capture(raw(youtubePayload().put("videoId", "lmnopqrstuv")), owner(youtube)))
        assertNull(BrowserSourceCaptionParser.capture(raw(youtubePayload().put("videoId", JSONObject.NULL)), owner(youtube)))
    }
    @Test fun youtubeActualEncodedAudioKeyCannotDisagreeWithObservedLanguage() {
        assertNull(BrowserSourceCaptionParser.capture(raw(youtubePayload().put("audioTrackKey", "ko.4")), owner(youtube)))
        assertNull(BrowserSourceCaptionParser.clock(raw(youtubePayload().put("audioTrackKey", "ko.4"))))
    }
    @Test fun youtubeTranslatedWrongVideoWrongLanguageAndDuplicateQueriesAreRejected() {
        for (url in listOf("https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&tlang=hi",
            "https://www.youtube.com/api/timedtext?v=lmnopqrstuv&lang=en",
            "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=ko",
            "https://www.youtube.com/api/timedtext?v=abcdefghijk&v=abcdefghijk&lang=en")) {
            assertNull(BrowserSourceCaptionParser.capture(raw(youtubePayload().put("tracks", JSONArray().put(track(url)))), owner(youtube)))
        }
    }
    @Test fun youtubeOriginalAsrMetadataCanBeAdmittedWithoutPretendingAudioCompletion() {
        val row = track("https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&fmt=json3")
            .put("kind", "AUTOMATIC").put("format", "JSON3").put("originalAutomatic", true)
        val scope = requireNotNull(BrowserSourceCaptionParser.capture(raw(youtubePayload().put("tracks", JSONArray().put(row))), owner(youtube)))
        assertEquals(ProviderCaptionKind.AUTOMATIC, scope.inventory.tracks.single().kind)
        assertTrue(scope.inventory.tracks.single().originalAutomatic)
    }
}
