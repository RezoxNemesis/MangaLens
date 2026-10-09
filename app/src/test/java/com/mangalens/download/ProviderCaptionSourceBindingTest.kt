package com.mangalens.download

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderCaptionSourceBindingTest {
    @Test fun youtubeMetadataForAnotherVideoCannotBindCaptionsToTheSelectedPage() {
        val metadata = metadata("different12", "different12")
        assertNull("Another video's genuine caption URL was accepted for the current selected page",
            ProviderCaptionDiscovery.fromMetadata(metadata, "https://www.youtube.com/watch?v=selected123", "en"))
    }

    @Test fun shortenedYoutubePageMustNameTheSameVideoAsMetadataAndTimedText() {
        assertNull(ProviderCaptionDiscovery.fromMetadata(metadata("different12", "different12"),
            "https://youtu.be/selected123", "en"))
        assertNotNull(ProviderCaptionDiscovery.fromMetadata(metadata("selected123", "selected123"),
            "https://youtu.be/selected123", "en"))
    }

    @Test fun playerAndShortsPageIdsHaveTheSameSourceBindingRule() {
        for (page in listOf("https://www.youtube.com/embed/selected123", "https://m.youtube.com/shorts/selected123")) {
            assertNull(ProviderCaptionDiscovery.fromMetadata(metadata("different12", "different12"), page, "en"))
            assertNotNull(ProviderCaptionDiscovery.fromMetadata(metadata("selected123", "selected123"), page, "en"))
        }
    }

    @Test fun ambiguousOrLandingYoutubePageCannotSupplyVideoCaptionAuthority() {
        for (page in listOf("https://www.youtube.com/", "https://www.youtube.com/@channel",
            "https://www.youtube.com/watch?v=selected123&v=different12"))
            assertNull(ProviderCaptionDiscovery.fromMetadata(metadata("selected123", "selected123"), page, "en"))
    }

    @Test fun genuineSameVideoManualOriginalsRemainPreferredOverAutomaticTracks() {
        val info = metadata("selected123", "selected123")
        info.put("automatic_captions", JSONObject("""{"en-orig":[{"url":"https://www.youtube.com/api/timedtext?v=selected123&lang=en&kind=asr","ext":"vtt"}]}"""))
        val inventory = requireNotNull(ProviderCaptionDiscovery.fromMetadata(info,
            "https://www.youtube.com/watch?v=selected123", "en"))
        assertEquals(ProviderCaptionKind.MANUAL, ProviderCaptionDiscovery.select(inventory, "auto")?.kind)
    }

    private fun metadata(metadataId: String, captionId: String) = JSONObject()
        .put("id", metadataId).put("language", "en").put("duration", 60)
        .put("subtitles", JSONObject("""{"en":[{"url":"https://www.youtube.com/api/timedtext?v=$captionId&lang=en","ext":"vtt"}]}"""))
}
