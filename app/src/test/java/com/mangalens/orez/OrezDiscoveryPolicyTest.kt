package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

class OrezDiscoveryPolicyTest {
    @Test fun creatorAndRecapRequestsSearchWithoutAnExtraCommand() {
        assertTrue(OrezDiscoveryPolicy.isVideoQuery("Carryminati videos"))
        assertTrue(OrezDiscoveryPolicy.isVideoQuery("find latest manhwa recap videos"))
        assertTrue(OrezDiscoveryPolicy.prefersYoutube("find latest manhwa recap videos"))
        assertFalse(OrezDiscoveryPolicy.prefersYoutube("find videos on Vimeo"))
        assertFalse(OrezDiscoveryPolicy.prefersYoutube("find adult videos across the web"))
    }

    @Test fun mangaAndPlaybackQuestionsDoNotBecomeVideoSearches() {
        assertFalse(OrezDiscoveryPolicy.isVideoQuery("find latest manhwa"))
        assertFalse(OrezDiscoveryPolicy.isVideoQuery("how do I translate YouTube videos?"))
        assertFalse(OrezDiscoveryPolicy.isVideoQuery("why is YouTube broken?"))
        assertFalse(OrezDiscoveryPolicy.isVideoQuery("download this video"))
    }

    @Test fun channelAndSitePagesNeverClaimToBeIndividualVideos() {
        assertFalse(OrezDiscoveryPolicy.isVideoResult("https://www.youtube.com"))
        assertFalse(OrezDiscoveryPolicy.isVideoResult("https://www.youtube.com/@ManhwaFresh"))
        assertFalse(OrezDiscoveryPolicy.isVideoResult("https://manhwarecap.com/"))
        assertTrue(OrezDiscoveryPolicy.isVideoResult("https://www.youtube.com/watch?v=abcdefghijk"))
        assertTrue(OrezDiscoveryPolicy.isVideoResult("https://youtu.be/abcdefghijk"))
        assertTrue(OrezDiscoveryPolicy.isVideoResult("https://www.dailymotion.com/video/x12345"))
    }
}
