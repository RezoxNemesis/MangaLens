package com.mangalens.orez.research

import java.security.MessageDigest
import java.util.Locale

enum class ResearchFreshnessRequest { NOT_SPECIFIED, CURRENT_REQUESTED }

data class OrezResearchRequest(val query: String, val freshness: ResearchFreshnessRequest, val limit: Int = 3) {
    fun arguments() = mapOf("query" to query, "limit" to limit.toString(), "freshness" to freshness.name)
    fun validate() {
        require(query.isNotBlank() && query == query.trim() && query.length <= 512 && query.toByteArray(Charsets.UTF_8).size <= 1024 &&
            query.none { it.isISOControl() } && limit == 3) { "Choose one short public research question." }
    }
    companion object {
        private val command = Regex("""^(?:please\s+)?(?:research|search\s+online\s+for|find\s+sources\s+for)\s+["“]([^"“”\r\n]+)["”]$""", RegexOption.IGNORE_CASE)
        fun parseExplicit(objective: String): OrezResearchRequest? = runCatching {
            val query = command.matchEntire(objective.trim())?.groupValues?.get(1)?.trim() ?: return null
            OrezResearchRequest(query, if (Regex("""\b(latest|current|today|now|recent|news)\b""").containsMatchIn(query.lowercase(Locale.ROOT)))
                ResearchFreshnessRequest.CURRENT_REQUESTED else ResearchFreshnessRequest.NOT_SPECIFIED).also { it.validate() }
        }.getOrNull()
        fun captured(objective: String, arguments: Map<String, String>): OrezResearchRequest =
            requireNotNull(parseExplicit(objective)) { "Research needs an explicit quoted user question." }.also {
                require(arguments == it.arguments()) { "Research query differs from the captured user request." }
            }
    }
}

internal fun researchSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
