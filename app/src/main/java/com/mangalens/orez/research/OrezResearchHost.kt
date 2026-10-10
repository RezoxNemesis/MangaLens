package com.mangalens.orez.research

import com.mangalens.engine.OrezSearchParser
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

internal data class ResearchCandidate(val title: String, val url: String)
internal interface OrezResearchProvider {
    val id: String
    fun searchUrl(request: OrezResearchRequest): String
    fun candidates(document: ResearchCapturedDocument, limit: Int): List<ResearchCandidate>
}
internal object DuckDuckGoResearchProvider : OrezResearchProvider {
    override val id = "duckduckgo-html"
    override fun searchUrl(request: OrezResearchRequest) = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(request.query, "UTF-8")
    override fun candidates(document: ResearchCapturedDocument, limit: Int) = OrezSearchParser.parse(document.text, limit).map { ResearchCandidate(it.title, it.url) }
}
internal object WikipediaResearchProvider : OrezResearchProvider {
    override val id = "wikipedia-rest"
    override fun searchUrl(request: OrezResearchRequest) = "https://en.wikipedia.org/w/rest.php/v1/search/page?q=" + URLEncoder.encode(request.query, "UTF-8") + "&limit=${request.limit}"
    override fun candidates(document: ResearchCapturedDocument, limit: Int) = OrezSearchParser.parseWikipedia(document.text, limit).map { ResearchCandidate(it.title, it.url) }
}

/** No browser/session/context access. The registered providers receive only the captured explicit query. */
internal class OrezResearchHost(private val transport: ResearchHttpTransport = OrezResearchTransport.production(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val providers: List<OrezResearchProvider> = listOf(DuckDuckGoResearchProvider, WikipediaResearchProvider),
    private val beforeBoundary: suspend () -> Unit = {
        com.mangalens.core.compute.ResourceGovernorRuntime.shared.awaitBoundary(com.mangalens.core.compute.ResourceWorkKind.BACKGROUND) { true }
    }) {
    suspend fun research(request: OrezResearchRequest, isExecuting: suspend () -> Boolean): OrezResearchEvidence? = withTimeoutOrNull(20_000L) {
        withContext(Dispatchers.IO) {
            request.validate(); require(providers.size in 1..2 && providers.map { it.id }.distinct().size == providers.size)
            val started = clock(); val budget = ResearchOperationBudget()
            val observations = ArrayList<ResearchProviderObservation>(); val citations = ArrayList<ResearchCitation>()
            val opened = HashSet<String>(); var incomplete = false; var sourceOpens = 0
            for ((providerIndex, provider) in providers.withIndex()) {
                currentCoroutineContext().ensureActive(); if (!isExecuting()) throw CancellationException("Research task retired.")
                beforeBoundary(); if (!isExecuting()) throw CancellationException("Research task retired.")
                val search = try { transport.get(provider.searchUrl(request), budget, isExecuting) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (paused: com.mangalens.core.compute.ResourcePausedException) { throw paused }
                    catch (_: Exception) { incomplete = true; observations += ResearchProviderObservation(provider.id, null, "UNAVAILABLE"); continue }
                val candidates = if (search.capture.status in 200..299) runCatching { provider.candidates(search, request.limit) }.getOrDefault(emptyList()) else emptyList()
                observations += ResearchProviderObservation(provider.id, search.capture, if (candidates.isEmpty()) "NO_RESULTS" else "READABLE")
                if (search.capture.bodyTruncated) incomplete = true
                for (candidate in candidates) {
                    if (!opened.add(candidate.url)) continue
                    // Reserve one source attempt for a different provider when all primary pages fail.
                    val sourceCeiling = if (providerIndex == 0 && providers.size > 1) 2 else 3
                    if (sourceOpens >= sourceCeiling) { incomplete = true; break }
                    sourceOpens++
                    beforeBoundary(); if (!isExecuting()) throw CancellationException("Research task retired.")
                    val source = try { transport.get(candidate.url, budget, isExecuting) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (paused: com.mangalens.core.compute.ResourcePausedException) { throw paused }
                        catch (_: Exception) { incomplete = true; continue }
                    if (source.capture.status !in 200..299 || source.capture.contentType !in setOf("text/html", "application/xhtml+xml", "text/plain")) { incomplete = true; continue }
                    beforeBoundary(); if (!isExecuting()) throw CancellationException("Research task retired.")
                    val excerpt = readableExcerpt(source.text, request.query)
                    if (excerpt.length < 30) { incomplete = true; continue }
                    if (citations.any { it.source.finalUrl == source.capture.finalUrl }) continue
                    val title = cleanText(candidate.title).take(160).ifBlank { "Source ${citations.size + 1}" }
                    citations += ResearchCitation(citations.size + 1, provider.id, title, excerpt, source.capture,
                        excerptTruncated = source.capture.bodyTruncated)
                    if (source.capture.bodyTruncated) incomplete = true
                    if (citations.size >= request.limit) break
                }
                if (citations.isNotEmpty() || sourceOpens >= 3) break
            }
            currentCoroutineContext().ensureActive(); if (!isExecuting()) throw CancellationException("Research task retired.")
            if (citations.isEmpty()) return@withContext null
            var evidence = OrezResearchEvidence(request.query, request.freshness, started, clock(), observations.toList(), citations.toList(), incomplete)
            // Preserve complete provenance. Shrink/drop optional extra quotes rather than modifying proof URLs.
            while (runCatching { OrezResearchEvidenceCodec.encode(evidence) }.isFailure) {
                val rows = evidence.citations
                if (rows.last().excerpt.length > 100) evidence = evidence.copy(incomplete = true, citations = rows.dropLast(1) + rows.last().copy(
                    excerpt = rows.last().excerpt.take((rows.last().excerpt.length - 100).coerceAtLeast(100)).trimEnd().let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }, excerptTruncated = true))
                else if (rows.size > 1) evidence = evidence.copy(incomplete = true, citations = rows.dropLast(1))
                else return@withContext null
            }
            evidence
        }
    }
    private fun readableExcerpt(html: String, query: String): String {
        val document = Jsoup.parse(html)
        document.select("script,style,noscript,svg,nav,header,footer,aside,form").remove()
        val text = cleanText(document.body()?.text().orEmpty())
        val terms = Regex("""[\p{L}\p{N}]{4,}""").findAll(query.lowercase(Locale.ROOT)).map { it.value }.distinct().take(10).toSet()
        val sentences = text.split(Regex("""(?<=[.!?])\s+|(?<=。)\s*""")).map(::cleanText).filter { it.length >= 30 }
            .filterNot { Regex("""(?i)\b(log in|sign up|privacy policy|cookie policy|accept all cookies|complete verification)\b""").containsMatchIn(it) }
        val ranked = sentences.sortedByDescending { sentence -> terms.count { sentence.lowercase(Locale.ROOT).contains(it) } }
        return ranked.take(3).joinToString(" ").take(600).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }.trim()
    }
    private fun cleanText(value: String) = value.replace(Regex("""[\p{Cntrl}\s]+"""), " ").trim()
}
