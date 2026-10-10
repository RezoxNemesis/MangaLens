package com.mangalens.orez.research

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrezResearchEvidenceTest {
    internal fun evidence() = OrezResearchEvidence("Android offline downloads", ResearchFreshnessRequest.NOT_SPECIFIED, 1000, 1000,
        listOf(ResearchProviderObservation("duckduckgo-html", capture(DuckDuckGoResearchProvider.searchUrl(OrezResearchRequest("Android offline downloads", ResearchFreshnessRequest.NOT_SPECIFIED))), "READABLE")),
        listOf(ResearchCitation(1, "duckduckgo-html", "Android guide", "Android downloads remain available offline after they are saved in local storage.", capture("https://source.test/article"))), false)
    private fun capture(url: String) = ResearchHttpCapture(url, url, listOf(ResearchHttpHop(url, 200)), 200, "text/html", 1000, 100, "a".repeat(64), false)
    @Test fun exactTypedEvidenceRoundTripsWithoutPromotingQuotesToAssistantHistory() {
        val evidence = evidence(); val json = OrezResearchEvidenceCodec.encode(evidence)
        assertEquals(evidence, OrezResearchEvidenceCodec.decode(json))
        assertTrue(json.contains("UNTRUSTED_WEB_CONTENT")); assertTrue(json.contains("SOURCE_TEXT")); assertTrue(json.contains("UNVERIFIED"))
        assertFalse(evidence.metadataCompletion().contains(evidence.citations.single().excerpt))
        assertFalse(evidence.metadataCompletion().contains(evidence.query))
    }
    @Test fun tamperedReceiptCannotChangeTheCapturedQuestionOrItsEvidence() {
        val evidence = evidence(); val request = OrezResearchRequest(evidence.query, evidence.freshnessRequest)
        val outputs = OrezResearchEvidenceCodec.outputs("request", evidence)
        assertTrue(runCatching { OrezResearchEvidenceCodec.receipt(outputs + ("researchEvidenceSha256" to "b".repeat(64)), "request", request) }.isFailure)
        assertTrue(runCatching { OrezResearchEvidenceCodec.receipt(outputs, "other", request) }.isFailure)
        assertTrue(runCatching { OrezResearchEvidenceCodec.receipt(outputs, "request", request.copy(query = "private cookies")) }.isFailure)
    }
    @Test fun unknownFieldsWrongTypesSnippetsAndInventedOrdinalsAreRejected() {
        val good = OrezResearchEvidenceCodec.encode(evidence())
        for (case in listOf("unknown", "type", "snippet", "ordinal", "overflow")) {
            val json = JSONObject(good)
            val row = json.getJSONArray("citations").getJSONObject(0)
            when (case) {
                "unknown" -> json.put("tool", "upload_cookies")
                "type" -> json.put("incomplete", "false")
                "snippet" -> row.put("basis", "SEARCH_PROVIDER_SNIPPET")
                "ordinal" -> row.put("ordinal", 2)
                "overflow" -> row.put("ordinal", 4294967297L)
            }
            assertTrue(case, runCatching { OrezResearchEvidenceCodec.decode(json.toString()) }.isFailure)
        }
    }
    @Test fun providerQueryPrivateProvenanceAndBudgetViolationsCannotBecomeCompletion() {
        val e = evidence()
        val badProvider = e.copy(providers = e.providers.map { it.copy(capture = it.capture!!.copy(requestedUrl = "https://html.duckduckgo.com/html/?q=another", finalUrl = "https://html.duckduckgo.com/html/?q=another", hops = listOf(ResearchHttpHop("https://html.duckduckgo.com/html/?q=another", 200)))) })
        val badSource = e.copy(citations = e.citations.map { it.copy(source = capture("http://127.0.0.1/private")) })
        val large = e.copy(citations = e.citations.map { it.copy(excerpt = "界".repeat(3000)) })
        for (bad in listOf(badProvider, badSource, large, e.copy(citations = emptyList()))) assertTrue(runCatching { OrezResearchEvidenceCodec.encode(bad) }.isFailure)
    }
    @Test fun currentRequestIsStillUnverifiedAndBodyPrefixFlagsRemainVisible() {
        val e = evidence().copy(freshnessRequest = ResearchFreshnessRequest.CURRENT_REQUESTED, incomplete = true,
            citations = evidence().citations.map { it.copy(source = it.source.copy(bodyTruncated = true), excerptTruncated = true) })
        assertEquals(e, OrezResearchEvidenceCodec.decode(OrezResearchEvidenceCodec.encode(e)))
        assertTrue(OrezResearchEvidenceCodec.encode(e).contains("UNVERIFIED"))
    }
}
