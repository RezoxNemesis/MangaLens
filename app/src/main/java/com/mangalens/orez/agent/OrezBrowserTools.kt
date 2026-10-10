package com.mangalens.orez.agent

import com.mangalens.ui.web.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Foreground typed tools. Grants come from trusted UI/runtime scope, never model or DOM JSON. */
internal class OrezBrowserTools(private val executor: BrowserDomExecutor) {
    fun currentOwner(): BrowserDomOwner? = executor.owner()
    fun retire() = executor.retire()

    /** Native user-selected Reader handoff. Exact target never becomes an agent output field. */
    suspend fun openSelectedChapter(selection: BrowserChapterSelection, onOpenReader: (String) -> Unit): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val target = executor.selectedChapter(selection) ?: return@withContext false
            if (executor.owner() != selection.owner) return@withContext false
            onOpenReader(target)
            true
        }

    suspend fun execute(call: OrezToolCall, grant: BrowserDomGrant): OrezToolResult = withContext(Dispatchers.Main.immediate) {
        when (val result = executor.execute(call, grant)) {
            is BrowserDomResult.Evidence -> OrezToolResult.Completed(mapOf(
                "browserEvidence" to BrowserDomCodec.encode(result.page), "trustOrigin" to OrezTrustOrigin.WEB_CONTENT.name))
            is BrowserDomResult.Dispatched -> OrezToolResult.Completed(mapOf(
                "browserAction" to JSONObject().put("pageId", result.pageId).put("action", result.action)
                    .put("address", result.address).put("status", "DISPATCHED").put("externalCompletionClaim", false).toString(),
                "trustOrigin" to OrezTrustOrigin.APP_STATE.name))
            is BrowserDomResult.Unavailable -> OrezToolResult.Failed(result.reason)
        }
    }
}
