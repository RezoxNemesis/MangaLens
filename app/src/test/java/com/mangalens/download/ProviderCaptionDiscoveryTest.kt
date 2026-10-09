package com.mangalens.download

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderCaptionDiscoveryTest {
    private val page = "https://www.youtube.com/watch?v=abcdefghijk"
    private fun row(language: String, format: String = "vtt", extra: String = "") = JSONObject()
        .put("ext", format).put("url", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=$language$extra")
    private fun metadata(language: String = "en") = JSONObject().put("id", "abcdefghijk").put("extractor_key", "Youtube")
        .put("language", language).put("duration", 12.0)
    private fun tracks(vararg languages: Pair<String, JSONObject>) = JSONObject().apply {
        languages.forEach { (language, row) -> put(language, JSONArray().put(row)) }
    }
    private fun inventory(info: JSONObject, audioLanguage: String? = null) = requireNotNull(ProviderCaptionDiscovery.fromMetadata(info, page, audioLanguage))

    @Test fun manualOriginalLanguageWinsOverAutomaticAndUnrelatedTranslations() {
        val source = metadata("hi").put("subtitles", tracks("en" to row("en"), "hi" to row("hi")))
            .put("automatic_captions", tracks("hi-orig" to row("hi")))
        val selected = requireNotNull(ProviderCaptionDiscovery.select(inventory(source), "auto"))
        assertEquals("hi", selected.language)
        assertEquals(ProviderCaptionKind.MANUAL, selected.kind)
        assertEquals(12_000L, inventory(source).expectedDurationMs)
    }
    @Test fun selectedAudioLanguagePreventsUsingCaptionsFromADifferentDub() {
        val source = metadata("hi").put("subtitles", tracks("hi" to row("hi"), "en-US" to row("en-US")))
        assertEquals("en-us", ProviderCaptionDiscovery.select(inventory(source, "en-US"), "auto")?.language)
    }
    @Test fun aSingleExplicitOriginalAutomaticLanguageCanResolveMissingMetadataLanguage() {
        val source = metadata("").put("automatic_captions", tracks("ja-orig" to row("ja"), "en" to row("ja", extra="&tlang=en")))
        val selected = requireNotNull(ProviderCaptionDiscovery.select(inventory(source), "auto"))
        assertEquals("ja", selected.language)
        assertEquals(ProviderCaptionKind.AUTOMATIC, selected.kind)
        assertTrue(selected.originalAutomatic)
    }
    @Test fun ambiguousOriginalLanguageDoesNotDefaultToEnglish() {
        val source = metadata("").put("subtitles", tracks("en" to row("en"), "hi" to row("hi")))
        assertNull(ProviderCaptionDiscovery.select(inventory(source), "auto"))
        assertEquals("hi", ProviderCaptionDiscovery.select(inventory(source), "hi")?.language)
    }
    @Test fun regionPreferenceAndSupportedFormatAreDeterministic() {
        val source = metadata("en-GB").put("subtitles", JSONObject()
            .put("en", JSONArray().put(row("en", "json3")).put(row("en", "vtt")))
            .put("en-GB", JSONArray().put(row("en-GB", "srt"))))
        val selected = requireNotNull(ProviderCaptionDiscovery.select(inventory(source), "auto"))
        assertEquals("en-gb", selected.language)
        assertEquals(ProviderCaptionFormat.SRT, selected.format)
    }
    @Test fun translatedAutomaticTracksAndChatOrUnsupportedPayloadsAreNotAdmitted() {
        val source = metadata().put("automatic_captions", tracks("en" to row("en", extra="&tlang=en")))
            .put("subtitles", tracks("live_chat" to row("en", "json"), "en" to row("en", "ttml")))
        assertNull(ProviderCaptionDiscovery.fromMetadata(source, page))
    }
    @Test fun anotherVideoCredentialsAndProviderLookalikeHostsCannotSupplyCaptions() {
        val badUrls = listOf("https://www.youtube.com/api/timedtext?v=otherVideo&lang=en",
            "https://name:secret@www.youtube.com/api/timedtext?v=abcdefghijk&lang=en",
            "https://www.youtube.com.evil.invalid/api/timedtext?v=abcdefghijk&lang=en",
            "file:///private/captions.vtt", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en#fragment")
        for (url in badUrls) assertNull(ProviderCaptionDiscovery.fromMetadata(metadata().put("subtitles",
            tracks("en" to JSONObject().put("ext", "vtt").put("url", url))), page))
    }
    @Test fun overLimitInventoriesAreRejectedInsteadOfPublishingAPartialSelection() {
        val many = JSONObject()
        repeat(129) { many.put("key$it", JSONArray().put(row("en"))) }
        assertNull(ProviderCaptionDiscovery.fromMetadata(metadata().put("subtitles", many), page))
        val tooManyFormats = JSONArray()
        repeat(17) { tooManyFormats.put(row("en")) }
        assertNull(ProviderCaptionDiscovery.fromMetadata(metadata().put("subtitles", JSONObject().put("en", tooManyFormats)), page))
    }
    @Test fun capturedTrackListCannotBeChangedByTheCallerAfterDispatch() {
        val mutable = mutableListOf(ProviderCaptionTrack("https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en",
            "en", ProviderCaptionKind.MANUAL, ProviderCaptionFormat.VTT))
        val captured = ProviderCaptionInventory(page, "abcdefghijk", "en", null, 12_000, mutable).captureSnapshot()
        mutable.clear()
        assertEquals(1, captured.tracks.size)
    }
}
