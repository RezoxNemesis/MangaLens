package com.mangalens.core.adblock

import org.junit.Assert.*
import org.junit.Test

class AdBlockRequestPolicyTest {
    @Test fun knownAdNetworkMediaIsStillBlocked() {
        assertNotNull(AdBlockRequestPolicy.blockingReason("https://cdn.doubleclick.net/video.mp4"))
    }
    @Test fun explicitFirstPartyAdMediaIsStillBlocked() {
        assertNotNull(AdBlockRequestPolicy.blockingReason("https://video.example/ads/preroll/ad.mp4"))
        assertNotNull(AdBlockRequestPolicy.blockingReason("https://video.example/preroll/ad.mp4"))
    }
    @Test fun signedEditorialVideoDoesNotBecomeAnAdBecauseOfItsCampaignParameter() {
        assertNull(AdBlockRequestPolicy.blockingReason("https://media.example/movie.mp4?campaign=editorial&token=123"))
    }
    @Test fun ordinaryCreatorVideoCanUseASponsorCategory() {
        assertNull(AdBlockRequestPolicy.blockingReason("https://media.example/sponsor/creator-interview.mp4"))
    }
    @Test fun ordinaryMainFrameEditorialNavigationRemainsAllowed() {
        assertNull(AdBlockRequestPolicy.blockingReason("https://reader.example/promotions/history", "navigation"))
        assertNull(AdBlockRequestPolicy.blockingReason("https://reader.example/advertising/review", "document"))
    }
    @Test fun ordinaryMainFrameLoginConsentIsNotAPopunder() {
        assertNull(AdBlockRequestPolicy.blockingReason("https://reader.example/account/popup-consent", "navigation"))
    }
    @Test fun ordinaryConsentResourcesAndCallbacksAreNotAdEndpoints() {
        assertNull(AdBlockRequestPolicy.blockingReason("https://reader.example/account/popup-consent.js", "script"))
        assertNull(AdBlockRequestPolicy.blockingReason("https://reader.example/popup/consent?choice=accept"))
    }
    @Test fun explicitClickunderNavigationRemainsBlocked() {
        assertNotNull(AdBlockRequestPolicy.blockingReason("https://reader.example/clickunder/offer", "navigation"))
    }
    @Test fun unrelatedQueryValuesCannotImpersonateAdParameterNames() {
        assertNull(AdBlockRequestPolicy.blockingReason("https://reader.example/image.webp?return_to=%2Fother%3Fadtag%3Dchapter"))
    }
    @Test fun explicitAdTagQueryStillIdentifiesAnAd() {
        assertNotNull(AdBlockRequestPolicy.blockingReason("https://video.example/video.mp4?adtag=preroll"))
    }
    @Test fun boundedAdDomainMatchingDoesNotBlockLookalikes() {
        assertNull(AdBlockRequestPolicy.blockingReason("https://doubleclick.net.example/movie.mp4"))
        assertNotNull(AdBlockRequestPolicy.blockingReason("https://deep.cdn.popads.net/ad.js"))
    }
    @Test fun SharedYouTubeAndInstagramMediaDeliveryStaysAllowed() {
        assertNull(AdBlockRequestPolicy.blockingReason("https://rr1---sn.example.googlevideo.com/videoplayback?id=42&mime=video%2Fmp4"))
        assertNull(AdBlockRequestPolicy.blockingReason("https://video.cdninstagram.com/media/clip.mp4"))
    }
    @Test fun ordinaryStreamAndCloudflareVerificationRemainAllowed() {
        assertNull(AdBlockRequestPolicy.blockingReason("https://media.example/stream/master.m3u8"))
        assertNull(AdBlockRequestPolicy.blockingReason("https://challenges.cloudflare.com/tracking/widget.js"))
        assertNotNull(AdBlockRequestPolicy.blockingReason("https://tracker.example/tracker/pixel.js"))
    }
    @Test fun explicitPixelTrackersKeepTheirPriorBlockingWhileOrdinaryGifMediaPasses() {
        assertNotNull(AdBlockRequestPolicy.blockingReason("https://tracker.example/pixel.gif"))
        assertNotNull(AdBlockRequestPolicy.blockingReason("https://tracker.example/event.gif"))
        assertNull(AdBlockRequestPolicy.blockingReason("https://reader.example/manga/illustration.gif"))
    }

    @Test fun ordinaryAudioAndFragmentedVideoKeepTheSameEditorialMediaExemption() {
        for (extension in listOf("mp4", "m4a", "aac", "mp3", "opus", "ogg", "m4s")) {
            assertNull(extension, AdBlockRequestPolicy.blockingReason("https://media.example/sponsor/interview.$extension"))
        }
    }

    @Test fun audioExemptionDoesNotBypassPinnedAdHostsOrExplicitAdPathsAndQueries() {
        for (extension in listOf("m4a", "aac", "mp3", "opus", "ogg", "m4s")) {
            assertNotNull(AdBlockRequestPolicy.blockingReason("https://ads.doubleclick.net/interview.$extension"))
            assertNotNull(AdBlockRequestPolicy.blockingReason("https://media.example/preroll/interview.$extension"))
            assertNotNull(AdBlockRequestPolicy.blockingReason("https://media.example/interview.$extension?adtag=preroll"))
        }
    }

    @Test fun blockedResponseIsDecidedOnceBeforeAnyMediaObservation() {
        val calls = mutableListOf<String>()
        val response = Any()
        assertSame(response, interceptBeforeMediaObservation({ calls.add("intercept"); response }, { calls.add("sniff") }))
        assertEquals(listOf("intercept"), calls)
    }

    @Test fun ordinaryMediaIsObservedOnlyAfterTheSingleAllowDecision() {
        val calls = mutableListOf<String>()
        assertNull(interceptBeforeMediaObservation<Any>({ calls.add("intercept"); null }, { calls.add("sniff") }))
        assertEquals(listOf("intercept", "sniff"), calls)
    }
    @Test fun dedicatedPopupNetworksAreBlockedWithoutBlockingProviderPlaybackAndControlRequests() {
        for (host in listOf("monetag.com", "onclckstr.com", "popadscdn.net", "adsterra.net", "highperformanceformat.com"))
            assertNotNull(AdBlockRequestPolicy.blockingReason("https://cdn.$host/resource.js"))
        for (url in listOf("https://www.youtube.com/youtubei/v1/player?key=public",
            "https://rr1.googlevideo.com/videoplayback?itag=137&mime=video%2Fmp4&sig=opaque",
            "https://www.instagram.com/api/v1/media/42/info/",
            "https://video.cdninstagram.com/story.mp4?signature=opaque",
            "https://challenges.cloudflare.com/widget.js")) assertNull(url, AdBlockRequestPolicy.blockingReason(url))
    }
}
