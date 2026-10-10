package com.mangalens.orez.research

import com.mangalens.core.router.UrlEngineRouter
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ResearchHttpHop(val url: String, val status: Int)
data class ResearchHttpCapture(val requestedUrl: String, val finalUrl: String, val hops: List<ResearchHttpHop>,
    val status: Int, val contentType: String, val capturedAtMs: Long, val capturedBytes: Int, val bodySha256: String,
    val bodyTruncated: Boolean, val responseDate: String = "", val lastModified: String = "", val transportEncoding: String = "identity", val textCharset: String = "UTF-8")
data class ResearchProviderObservation(val providerId: String, val capture: ResearchHttpCapture?, val outcome: String)
data class ResearchCitation(val ordinal: Int, val providerId: String, val title: String, val excerpt: String,
    val source: ResearchHttpCapture, val excerptTruncated: Boolean = false)
data class OrezResearchEvidence(val query: String, val freshnessRequest: ResearchFreshnessRequest,
    val startedAtMs: Long, val completedAtMs: Long, val providers: List<ResearchProviderObservation>,
    val citations: List<ResearchCitation>, val incomplete: Boolean) {
    fun metadataCompletion() = "Saved ${citations.size} public source excerpts. Open Saved research results in Orez to inspect citations and retrieval dates. Current facts remain unverified."
}

/** Actual bounded source captures; no source text is reclassified as an assistant message. */
object OrezResearchEvidenceCodec {
    const val MAX_BYTES = 8192
    private val sha = Regex("[a-f0-9]{64}")
    private val providers = setOf("duckduckgo-html", "wikipedia-rest")
    private val receiptKeys = setOf("requestId", "querySha256", "researchEvidence", "researchEvidenceSha256")
    fun outputs(requestId: String, evidence: OrezResearchEvidence): Map<String, String> {
        val encoded = encode(evidence)
        return mapOf("requestId" to requestId, "querySha256" to researchSha256(evidence.query.toByteArray(Charsets.UTF_8)),
            "researchEvidence" to encoded, "researchEvidenceSha256" to researchSha256(encoded.toByteArray(Charsets.UTF_8)))
    }
    fun receipt(outputs: Map<String, String>, requestId: String, request: OrezResearchRequest): OrezResearchEvidence {
        require(outputs.keys == receiptKeys && outputs["requestId"] == requestId)
        require(outputs["querySha256"] == researchSha256(request.query.toByteArray(Charsets.UTF_8)))
        val encoded = requireNotNull(outputs["researchEvidence"])
        require(outputs["researchEvidenceSha256"] == researchSha256(encoded.toByteArray(Charsets.UTF_8)))
        return decode(encoded).also { require(it.query == request.query && it.freshnessRequest == request.freshness) }
    }
    fun encode(value: OrezResearchEvidence): String {
        validate(value)
        val json = JSONObject().put("schema", 1).put("policy", "public-research-v1").put("query", value.query)
            .put("freshnessRequest", value.freshnessRequest.name).put("factFreshness", "UNVERIFIED")
            .put("startedAtMs", value.startedAtMs).put("completedAtMs", value.completedAtMs).put("incomplete", value.incomplete)
            .put("providers", JSONArray().apply { value.providers.forEach { row -> put(JSONObject().put("providerId", row.providerId)
                .put("outcome", row.outcome).put("capture", row.capture?.let(::captureJson) ?: JSONObject.NULL)) } })
            .put("citations", JSONArray().apply { value.citations.forEach { row -> put(JSONObject().put("ordinal", row.ordinal)
                .put("providerId", row.providerId).put("title", row.title).put("excerpt", row.excerpt).put("basis", "SOURCE_TEXT")
                .put("trust", "UNTRUSTED_WEB_CONTENT").put("excerptTruncated", row.excerptTruncated).put("source", captureJson(row.source))) } })
        return json.toString().also { require(it.length <= MAX_BYTES && it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }
    fun decode(encoded: String): OrezResearchEvidence {
        require(encoded.length <= MAX_BYTES && encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(encoded)
        keys(root, setOf("schema", "policy", "query", "freshnessRequest", "factFreshness", "startedAtMs", "completedAtMs", "incomplete", "providers", "citations"))
        require(number(root, "schema") == 1L && string(root, "policy") == "public-research-v1" && string(root, "factFreshness") == "UNVERIFIED")
        val ps = root.getJSONArray("providers"); val cs = root.getJSONArray("citations")
        require(ps.length() in 1..2 && cs.length() in 1..3)
        return OrezResearchEvidence(string(root, "query"), ResearchFreshnessRequest.valueOf(string(root, "freshnessRequest")),
            number(root, "startedAtMs"), number(root, "completedAtMs"), (0 until ps.length()).map { index ->
                ps.getJSONObject(index).let { row -> keys(row, setOf("providerId", "outcome", "capture"))
                    ResearchProviderObservation(string(row, "providerId"), if (row.isNull("capture")) null else capture(row.getJSONObject("capture")), string(row, "outcome")) }
            }, (0 until cs.length()).map { index -> cs.getJSONObject(index).let { row ->
                keys(row, setOf("ordinal", "providerId", "title", "excerpt", "basis", "trust", "excerptTruncated", "source"))
                require(string(row, "basis") == "SOURCE_TEXT" && string(row, "trust") == "UNTRUSTED_WEB_CONTENT")
                ResearchCitation(integer(row, "ordinal"), string(row, "providerId"), string(row, "title"), string(row, "excerpt"),
                    capture(row.getJSONObject("source")), bool(row, "excerptTruncated")) }
            }, bool(root, "incomplete")).also(::validate)
    }
    private fun validate(e: OrezResearchEvidence) {
        OrezResearchRequest(e.query, e.freshnessRequest).validate()
        require(e.startedAtMs >= 0 && e.completedAtMs >= e.startedAtMs && e.providers.size in 1..2 && e.citations.size in 1..3)
        require(e.providers.map { it.providerId }.distinct().size == e.providers.size && e.providers.all {
            it.providerId in providers && it.outcome in setOf("READABLE", "NO_RESULTS", "UNAVAILABLE") })
        for (p in e.providers) p.capture?.let { validateCapture(it, e); require(providerUrl(p.providerId, it.requestedUrl, e.query)) }
        val captures = e.providers.mapNotNull { it.capture } + e.citations.map { it.source }
        require(captures.sumOf { it.capturedBytes.toLong() } <= 6_000_000 && captures.sumOf { it.hops.size } <= 10 && captures.sumOf { it.hops.size - 1 } <= 5)
        require(e.citations.map { it.ordinal } == (1..e.citations.size).toList())
        require(e.citations.map { it.source.finalUrl }.distinct().size == e.citations.size)
        for (c in e.citations) {
            require(e.providers.any { it.providerId == c.providerId && it.capture?.status?.let { status -> status in 200..299 } == true && it.outcome == "READABLE" } && c.title.length in 1..160 &&
                c.excerpt.length in 30..600 && c.title.none(Char::isISOControl) && c.excerpt.none(Char::isISOControl))
            validateCapture(c.source, e)
            require(c.source.status in 200..299 && c.source.capturedBytes > 0 && c.source.contentType in setOf("text/html", "application/xhtml+xml", "text/plain"))
        }
    }
    private fun providerUrl(id: String, url: String, query: String): Boolean = runCatching {
        val request = OrezResearchRequest(query, ResearchFreshnessRequest.NOT_SPECIFIED)
        url == when (id) {
            "duckduckgo-html" -> DuckDuckGoResearchProvider.searchUrl(request).toHttpUrl().toString()
            "wikipedia-rest" -> WikipediaResearchProvider.searchUrl(request).toHttpUrl().toString()
            else -> false
        }
    }.getOrDefault(false)
    private fun validateCapture(c: ResearchHttpCapture, e: OrezResearchEvidence) {
        require(c.hops.size in 1..6 && c.hops.first().url == c.requestedUrl && c.hops.last().url == c.finalUrl && c.hops.last().status == c.status)
        require(c.hops.all { it.url.length in 1..1024 && UrlEngineRouter.isSafeWebUrl(it.url) && it.status in 100..599 })
        c.hops.forEach { OrezResearchPublicNetworkPolicy.requireUrl(it.url.toHttpUrl()) }
        require(c.hops.dropLast(1).all { it.status in setOf(301, 302, 303, 307, 308) })
        require(c.capturedAtMs in e.startedAtMs..e.completedAtMs && c.capturedBytes in 0..1_500_000 && c.bodySha256.matches(sha) &&
            c.transportEncoding == "identity" && c.textCharset.length in 1..40 && runCatching { java.nio.charset.Charset.forName(c.textCharset) }.isSuccess &&
            c.contentType.length <= 80 && c.responseDate.length <= 128 && c.lastModified.length <= 128)
    }
    private fun captureJson(c: ResearchHttpCapture) = JSONObject().put("requestedUrl", c.requestedUrl).put("finalUrl", c.finalUrl)
        .put("hops", JSONArray().apply { c.hops.forEach { put(JSONObject().put("url", it.url).put("status", it.status)) } })
        .put("status", c.status).put("contentType", c.contentType).put("capturedAtMs", c.capturedAtMs).put("capturedBytes", c.capturedBytes)
        .put("bodySha256", c.bodySha256).put("bodyTruncated", c.bodyTruncated).put("responseDate", c.responseDate).put("lastModified", c.lastModified)
        .put("transportEncoding", c.transportEncoding).put("textCharset", c.textCharset)
    private fun capture(j: JSONObject): ResearchHttpCapture {
        keys(j, setOf("requestedUrl", "finalUrl", "hops", "status", "contentType", "capturedAtMs", "capturedBytes", "bodySha256", "bodyTruncated", "responseDate", "lastModified", "transportEncoding", "textCharset"))
        val hops = j.getJSONArray("hops"); require(hops.length() in 1..6)
        return ResearchHttpCapture(string(j, "requestedUrl"), string(j, "finalUrl"), (0 until hops.length()).map { i -> hops.getJSONObject(i).let {
            keys(it, setOf("url", "status")); ResearchHttpHop(string(it, "url"), integer(it, "status")) } },
            integer(j, "status"), string(j, "contentType"), number(j, "capturedAtMs"), integer(j, "capturedBytes"),
            string(j, "bodySha256"), bool(j, "bodyTruncated"), string(j, "responseDate"), string(j, "lastModified"), string(j, "transportEncoding"), string(j, "textCharset"))
    }
    private fun keys(j: JSONObject, expected: Set<String>) { require(j.keys().asSequence().toSet() == expected) }
    private fun string(j: JSONObject, key: String): String { require(j.get(key) is String); return j.getString(key) }
    private fun bool(j: JSONObject, key: String): Boolean { require(j.get(key) is Boolean); return j.getBoolean(key) }
    private fun number(j: JSONObject, key: String): Long { require(j.get(key) is Int || j.get(key) is Long); return j.getLong(key) }
    private fun integer(j: JSONObject, key: String): Int = number(j, key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
}
