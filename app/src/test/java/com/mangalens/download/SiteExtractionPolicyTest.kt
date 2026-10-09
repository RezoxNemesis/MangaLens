package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

class SiteExtractionPolicyTest {
    @Test fun installedDefaultAlwaysPrecedesSingleClientFallbacks() {
        val clients = SiteExtractionPolicy.clients("https://www.youtube.com/watch?v=fixture")
        assertNull(clients.first())
        assertEquals(listOf("tv", "web_embedded", "web_safari", "ios", "android"), clients.drop(1))
        assertEquals(listOf<String?>(null), SiteExtractionPolicy.clients("https://youtube.com.attacker.example/watch"))
    }

    @Test fun extractionReservesPartOfSharedDeadlineForHtmlFallback() {
        assertEquals(33_750L, SiteExtractionPolicy.extractorBudgetMs(45_000L))
        assertEquals(67_500L, SiteExtractionPolicy.extractorBudgetMs(90_000L))
    }

    @Test fun attemptsCannotExtendTheRemainingBudget() {
        assertEquals(16_000L, SiteExtractionPolicy.attemptBudgetMs(0, 45_000L))
        assertEquals(8_000L, SiteExtractionPolicy.attemptBudgetMs(1, 20_000L))
        assertEquals(750L, SiteExtractionPolicy.attemptBudgetMs(4, 750L))
    }
}
