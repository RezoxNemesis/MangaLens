package com.mangalens.orez.agent

import com.mangalens.orez.research.OrezResearchPublicNetworkPolicy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale

/** Accepted only from the literal USER command; never from an answer or extracted page text. */
internal object OrezNextChapterRequest {
    private val command = Regex("""^(?:please\s+)?(?:save|download|acquire)\s+(?:the\s+)?next\s+chapter(?:\s+(?:for\s+offline\s+reading|to\s+(?:my\s+)?library))?[.!]?$""", RegexOption.IGNORE_CASE)
    fun isRequested(input: String) = command.matches(input.trim())
    private val direct = Regex("""^(?:please\s+)?(?:save|download|acquire)\s+(?:this\s+|the\s+)?(?:manga\s+)?chapter(?:\s+(?:url|at))?\s+(https?://[^\s<>"']+)$""", RegexOption.IGNORE_CASE)
    private val plainChapter = Regex("""^(?:please\s+)?(?:save|download|acquire)\s+(https?://[^\s<>"']+)$""", RegexOption.IGNORE_CASE)
    fun directUrl(input: String): String? = direct.matchEntire(input.trim())?.groupValues?.get(1)
        ?: plainChapter.matchEntire(input.trim())?.groupValues?.get(1)?.takeIf { runCatching { OrezNextChapterPolicy.chapterUrl(it) }.isSuccess }
}

/** Direct URL scope is copied from USER text; a next request adds its independent saved-source proof. */
data class OrezChapterAcquisitionScope(val targetUrl: String, val next: OrezNextChapterScope? = null) {
    fun validated(): OrezChapterAcquisitionScope {
        OrezNextChapterPolicy.publicUrl(targetUrl)
        next?.validated()?.let { require(it.targetUrl == targetUrl) { "Next-chapter destination changed." } }
        return this
    }
    internal fun encode() = JSONObject().put("targetUrl", targetUrl).put("next", next?.encode() ?: JSONObject.NULL)
    internal val fingerprint: String get() = OrezNextChapterPolicy.sha("orez-chapter-acquisition-v1\u0000$targetUrl\u0000${next?.fingerprint.orEmpty()}")
    companion object {
        internal fun decode(value: JSONObject) = OrezChapterAcquisitionScope(value.getString("targetUrl"),
            value.optJSONObject("next")?.let(OrezNextChapterScope::decode)).validated()
    }
}

/** A native capture of a saved source and one literal, same-series successor. No model URL argument. */
data class OrezNextChapterScope(
    val chapterId: String,
    val sourceFingerprint: String,
    val sourcePageCount: Int,
    val sourceUrl: String,
    val targetUrl: String,
    val relationFingerprint: String,
    val sourceDocumentSha256: String
) {
    fun validated(): OrezNextChapterScope {
        require(chapterId.matches(Regex("[a-f0-9]{32}")) && sourcePageCount in 1..1000 &&
            listOf(sourceFingerprint, relationFingerprint, sourceDocumentSha256).all { it.matches(Regex("[a-f0-9]{64}")) }) { "Next-chapter source evidence is incomplete." }
        val source = OrezNextChapterPolicy.chapterUrl(sourceUrl)
        val target = OrezNextChapterPolicy.chapterUrl(targetUrl)
        require(OrezNextChapterPolicy.sameOrigin(source, target)) { "Next chapter is outside the captured public source." }
        val from = OrezNextChapterPolicy.identity(source)
        val to = OrezNextChapterPolicy.identity(target)
        require(from.series == to.series && to.number > from.number) { "Next chapter is not a qualified successor in the same series." }
        require(relationFingerprint == OrezNextChapterPolicy.relation(source.toString(), target.toString())) { "Next-chapter relation evidence changed." }
        return this
    }
    internal fun encode() = JSONObject().put("chapterId", chapterId).put("sourceFingerprint", sourceFingerprint)
        .put("sourcePageCount", sourcePageCount).put("sourceUrl", sourceUrl).put("targetUrl", targetUrl)
        .put("relationFingerprint", relationFingerprint).put("sourceDocumentSha256", sourceDocumentSha256)
    internal val fingerprint: String get() = OrezNextChapterPolicy.sha("orez-next-source-v1\u0000$chapterId\u0000$sourceFingerprint\u0000$sourcePageCount\u0000$sourceUrl\u0000$targetUrl\u0000$relationFingerprint\u0000$sourceDocumentSha256")
    companion object {
        internal fun decode(value: JSONObject) = OrezNextChapterScope(value.getString("chapterId"), value.getString("sourceFingerprint"),
            value.getInt("sourcePageCount"), value.getString("sourceUrl"), value.getString("targetUrl"),
            value.getString("relationFingerprint"), value.getString("sourceDocumentSha256")).validated()
    }
}

internal object OrezNextChapterPolicy {
    const val MAX_HTML_BYTES = 1_500_000L
    const val MAX_PAGES = 300
    const val MAX_CHAPTER_BYTES = 128L * 1024 * 1024
    const val MAX_LOCAL_PROOF_BYTES = 768L * 1024 * 1024
    data class Identity(val series: String, val number: BigDecimal)
    private val chapter = Regex("(?i)(?:chapter|chap|episode|ep)[/_-]+(\\d{1,8}(?:\\.\\d{1,4})?)(?=/|$)")
    private val sensitiveQuery = Regex("(?i)(?:token|access.?token|auth(?:orization)?|password|passwd|secret|session(?:id)?|jwt|signature|sig|credential|security.?token|api.?key|policy|expires|x-amz-.+|x-goog-.+)")
    fun publicUrl(value: String): HttpUrl {
        val url = value.toHttpUrl()
        OrezResearchPublicNetworkPolicy.requireUrl(url)
        require(url.toString() == value && url.querySize <= 16 && url.queryParameterNames.none { sensitiveQuery.matches(it) } &&
            (0 until url.querySize).all { (url.queryParameterValue(it)?.length ?: 0) <= 256 }) { "Credential-bearing or noncanonical sources require an explicit foreground browser session." }
        return url
    }
    fun chapterUrl(value: String): HttpUrl = publicUrl(value).also {
        require(it.query == null) { "Query-based chapters are unavailable to this bounded native next-chapter tool. Open their exact source in Reader." }
        identity(it)
    }
    fun identity(url: HttpUrl): Identity {
        val matches = chapter.findAll(url.encodedPath).toList()
        require(matches.size == 1) { "The source has no unambiguous numeric chapter identity." }
        val match = matches.single()
        val prefix = url.encodedPath.substring(0, match.range.first)
        val suffix = url.encodedPath.substring(match.range.last + 1).trimEnd('/')
        require(prefix.trim('/').isNotBlank() && suffix.isEmpty()) { "The chapter URL does not identify one series and chapter." }
        return Identity(prefix.trimEnd('/'), match.groupValues[1].toBigDecimal().stripTrailingZeros())
    }
    fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    fun requireChapterDocument(actual: String, expected: String) {
        val fetched = chapterUrl(actual); val captured = chapterUrl(expected)
        require(sameOrigin(fetched, captured) && identity(fetched) == identity(captured)) {
            "The source redirected to another chapter. Open its actual source and start a new explicit request."
        }
    }
    fun relation(source: String, target: String) = sha("orez-next-relation-v1\u0000$source\u0000$target")
    fun sha(value: String) = sha(value.toByteArray(Charsets.UTF_8))
    fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    fun next(html: String, sourceUrl: String): String {
        require(html.length <= MAX_HTML_BYTES)
        val source = chapterUrl(sourceUrl); val current = identity(source)
        val document = readable(html, sourceUrl)
        val links = document.select("a[href],link[href][rel]")
        require(links.size <= 2000) { "The chapter catalog exceeds the native inspection limit." }
        val qualified = links.mapNotNull { node ->
            if (node.closest("form,[role=dialog],[aria-modal=true]") != null) return@mapNotNull null
            val url = source.resolve(node.attr("href").trim())?.newBuilder()?.fragment(null)?.build() ?: return@mapNotNull null
            val candidate = runCatching { chapterUrl(url.toString()) }.getOrNull() ?: return@mapNotNull null
            val item = identity(candidate)
            if (!sameOrigin(source, candidate) || item.series != current.series || item.number <= current.number) null
            else Triple(candidate.toString(), item.number, node.attr("rel").split(Regex("\\s+")).any { it.equals("next", true) })
        }.distinctBy { it.first }
        require(qualified.isNotEmpty()) { "No public next-chapter relation is available. Inspect this source in Reader." }
        val explicit = qualified.filter { it.third }
        require(explicit.size <= 1) { "The source advertises conflicting next chapters." }
        val minimum = qualified.minOf { it.second }
        val nearest = qualified.filter { it.second == minimum }
        require(nearest.size == 1 && (explicit.isEmpty() || explicit.single().first == nearest.single().first)) { "The chapter catalog has an ambiguous successor." }
        return nearest.single().first
    }
    fun readable(html: String, url: String): org.jsoup.nodes.Document {
        require(html.length <= MAX_HTML_BYTES)
        return Jsoup.parse(html, url).also { document ->
            require(document.select("form input[type=password],#challenge-form,#cf-challenge-running,meta[http-equiv=refresh]").isEmpty()) {
                "This source requires authentication or a browser challenge. Native acquisition cannot bypass it."
            }
        }
    }
}
