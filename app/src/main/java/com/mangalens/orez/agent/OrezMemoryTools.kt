package com.mangalens.orez.agent

import com.mangalens.core.translation.memory.MemorySearchHit
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class OrezMemorySearchSnapshot(val chapterId: String, val sourceFingerprint: String,
    val associationRevision: Long, val hits: List<MemorySearchHit>, val incomplete: Boolean)

/** Read-only host searches exactly the app-captured chapter. No model-created series or path is accepted. */
interface OrezMemoryHost { suspend fun search(chapterId: String, query: String, limit: Int): OrezMemorySearchSnapshot? }

class OrezMemoryTools(private val host: OrezMemoryHost, private val authorization: OrezTaskAuthorization,
    private val isExecuting: suspend () -> Boolean = { true }) : OrezDurableTools {
    override suspend fun execute(step: OrezPlanStep, requestId: String): OrezToolResult = try {
        require(authorization.origin == OrezTrustOrigin.USER && authorization.explicitUserRequest) { "Explicit selected-chapter scope is missing." }
        require(step.call.name == "search_saved_memory" && step.call.capability == OrezCapability.LIBRARY && step.call.risk == OrezToolRisk.READ_ONLY)
        require(step.call.arguments.keys.all { it in setOf("chapterId", "query", "limit") } && step.references.isEmpty())
        val chapterId = requireNotNull(step.call.arguments["chapterId"])
        require(chapterId.matches(Regex("[a-f0-9]{32}")) && chapterId in authorization.chapterIds) { "Memory search exceeds the selected chapter scope." }
        val query = requireNotNull(step.call.arguments["query"])
        require(query.isNotBlank() && query.length <= 256 && '\u0000' !in query)
        val limit = step.call.arguments["limit"]?.let { requireNotNull(it.toIntOrNull()) { "Memory search limit must be an integer." } } ?: 8
        require(limit in 1..8)
        if (!isExecuting()) OrezToolResult.Pending("Memory search paused.", needsResume = true) else {
            val found = requireNotNull(host.search(chapterId, query, limit)) { "The selected chapter is missing or changed." }
            require(found.chapterId == chapterId && found.sourceFingerprint.matches(Regex("[a-f0-9]{64}")) && found.associationRevision >= 0)
            require(found.hits.size <= limit && found.hits.all { hit ->
                hit.source.chapterId == chapterId && hit.text.isNotBlank() && hit.text.length <= 4096 && hit.revision >= 0 &&
                    hit.bubbleId.matches(Regex("[a-f0-9]{64}")) && hit.configurationIdentity.matches(Regex("[a-f0-9]{64}")) &&
                    hit.targetLanguage.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})?")) &&
                    runCatching { hit.source.validate() }.isSuccess
            }) { "Native memory result exceeded its selected chapter scope." }
            if (!isExecuting()) OrezToolResult.Pending("Memory search paused before publication.", needsResume = true) else {
                var truncated = found.incomplete
                val rows = JSONArray().apply { found.hits.forEach { hit ->
                    var text = hit.text.trim().take(512).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
                    val row = JSONObject().put("chapterId", chapterId)
                    .put("pageIndex", hit.source.pageIndex).put("sourceSha256", hit.source.sourceSha256)
                    .put("imageWidth", hit.source.imageWidth).put("imageHeight", hit.source.imageHeight)
                    .put("bounds", JSONArray(listOf(hit.source.bounds.left, hit.source.bounds.top, hit.source.bounds.right, hit.source.bounds.bottom)))
                    .put("targetLanguage", hit.targetLanguage).put("revision", hit.revision).put("kind", hit.kind.name)
                    .put("text", text)
                    while (row.toString().length > 1000 && text.isNotEmpty()) {
                        text = text.dropLast(minOf(32, text.length)).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
                        row.put("text", text)
                    }
                    require(text.isNotEmpty()) { "Native memory metadata exceeds its safe limit." }
                    if (text != hit.text) truncated = true
                    put(row)
                } }
                require(rows.toString().length <= 8192)
                OrezToolResult.Completed(mapOf("requestId" to requestId, "chapterId" to chapterId, "sourceFingerprint" to found.sourceFingerprint,
                    "associationRevision" to found.associationRevision.toString(), "memoryHitCount" to found.hits.size.toString(),
                    "memoryHits" to rows.toString(), "memoryIncomplete" to truncated.toString()))
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { OrezToolResult.Failed(failure.message ?: "Selected chapter memory search failed.") }
}
