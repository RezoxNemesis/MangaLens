package com.mangalens.ui.web

import com.mangalens.orez.agent.OrezToolCall
import com.mangalens.orez.agent.OrezToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Calls are foreground-only. This executor cannot reopen a page or replay an external effect. */
internal class BrowserDomExecutor(private val currentHost: () -> BrowserDomLiveHost?, private val clock: () -> Long) {
    private data class Element(val owner: BrowserDomOwner, val createdAt: Long, val locator: String,
        val signature: String, val kind: String, val target: String)
    private val mutex = Mutex()
    private val generation = AtomicLong(0)
    private val elements = LinkedHashMap<String, Element>()

    fun owner(): BrowserDomOwner? = currentHost()?.owner
    fun retire() { generation.incrementAndGet(); synchronized(elements) { elements.clear() } }

    suspend fun execute(call: OrezToolCall, grant: BrowserDomGrant): BrowserDomResult {
        // Model adapters may supply a Map with a mutable alias. Freeze before waiting,
        // validation, grant consumption or any suspension, then use only this request.
        val request = call.copy(arguments = call.arguments.toMap())
        return mutex.withLock {
            try {
                OrezToolRegistry().validate(request)
                require(request.name in BrowserDomPolicy.names)
                val host = currentHost() ?: return@withLock unavailable("Open a loaded page in Web to use its browser tools.")
                val captured = host.owner
                if (request.arguments["pageId"] != captured.pageId || !grant.consume(request, captured))
                    return@withLock unavailable("The browser request or its approval is stale. Inspect the current page again.")
                val operation = generation.get()
                fun current() = generation.get() == operation && currentHost()?.owner == captured
                val result = withTimeoutOrNull(5_000L) {
                    if (!current()) return@withTimeoutOrNull unavailable("The selected browser page changed.")
                    if (request.name == "browser_navigate") {
                        val target = request.arguments.getValue("value")
                        val job = currentCoroutineContext()[Job]
                        // Native navigation retires the previous document as part of dispatch. It must
                        // still honor cancellation, but cannot require the retired owner afterwards.
                        return@withTimeoutOrNull if (host.navigate(target) { job?.isActive == true })
                            BrowserDomResult.Dispatched(captured.pageId, request.name, BrowserDomPolicy.displayAddress(target))
                        else unavailable("Navigation was not dispatched. The page or request changed.")
                    }
                    val script = when (request.name) {
                        "browser_observe" -> BrowserDomScripts.observe(request.arguments["query"].orEmpty())
                        "browser_extract" -> BrowserDomScripts.extract(request.arguments["query"].orEmpty())
                        "browser_scroll" -> BrowserDomScripts.scroll(request.arguments.getValue("delta").toInt())
                        else -> {
                            val selected = synchronized(elements) { elements[request.arguments.getValue("elementId")] }
                            if (selected == null || selected.owner != captured || clock() - selected.createdAt !in 0..BrowserDomPolicy.HANDLE_LIFETIME_MS)
                                return@withTimeoutOrNull unavailable("That observed element expired. Inspect the page again.")
                            if (request.name == "browser_fill" && selected.kind != "field" ||
                                request.name == "browser_click" && selected.kind !in setOf("link", "chapter_link", "button"))
                                return@withTimeoutOrNull unavailable("The selected element does not support this action.")
                            BrowserDomScripts.element(request.name, selected.locator, selected.signature, request.arguments["text"].orEmpty())
                        }
                    }
                    val raw = host.evaluate(script)
                    if (!current()) return@withTimeoutOrNull unavailable("The page changed during the operation; its result was retired. Inspect it before another action.")
                    if (raw == null || raw.length > BrowserDomPolicy.MAX_RESPONSE)
                        return@withTimeoutOrNull unavailable("The current page did not return bounded readable evidence.")
                    val payload = JSONObject(raw)
                    if (payload.has("error")) return@withTimeoutOrNull unavailable("The element changed or the page does not permit this operation. Inspect it again.")
                    if (!WebPageLoadState.sameDocument(captured.url, payload.optString("url")))
                        return@withTimeoutOrNull unavailable("The returned page belongs to another source; its result was retired.")
                    when (request.name) {
                        "browser_observe", "browser_extract" -> {
                            val rows = payload.getJSONArray("elements")
                            if (rows.length() > BrowserDomPolicy.MAX_ELEMENTS) return@withTimeoutOrNull unavailable("The page returned too many elements.")
                            val exposed = ArrayList<BrowserDomElement>()
                            val capturedElements = LinkedHashMap<String, Element>()
                            for (index in 0 until rows.length()) {
                                val row = rows.getJSONObject(index)
                                val locator = row.optString("locator"); val signature = row.optString("signature")
                                val kind = row.optString("kind"); val target = row.optString("target")
                                if (!BrowserDomPolicy.validLocator(locator) || signature.length !in 1..2_048 ||
                                    kind !in setOf("link", "chapter_link", "button", "field", "media") ||
                                    (target.isNotEmpty() && !BrowserDomPolicy.permittedAddress(target))) continue
                                val id = UUID.randomUUID().toString().replace("-", "")
                                exposed += BrowserDomElement(id, kind, BrowserDomPolicy.clean(row.optString("label"), 120), BrowserDomPolicy.displayAddress(target))
                                capturedElements[id] = Element(captured, clock(), locator, signature, kind, target)
                            }
                            if (request.name == "browser_observe") synchronized(elements) { elements.clear(); elements.putAll(capturedElements) }
                            BrowserDomResult.Evidence(BrowserDomEvidence(captured.pageId, BrowserDomPolicy.displayAddress(captured.url),
                                BrowserDomPolicy.clean(payload.optString("title"), 160), BrowserDomPolicy.clean(payload.optString("text"), BrowserDomPolicy.MAX_TEXT),
                                exposed, payload.optBoolean("truncated") || payload.optString("text").length > BrowserDomPolicy.MAX_TEXT))
                        }
                        else -> if (payload.optString("status") == "dispatched" && payload.optString("action") == request.name)
                            BrowserDomResult.Dispatched(captured.pageId, request.name)
                        else unavailable("The page did not acknowledge this action.")
                    }
                }
                result ?: unavailable("The page operation timed out. Inspect its current state before trying another action.")
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { unavailable("The browser tool is unavailable for this request. Inspect a loaded current page and retry.") }
        }
    }

    /** Read a selected href into the native Reader only; this is absent from model/tool JSON catalogs. */
    suspend fun selectedChapter(selection: BrowserChapterSelection): String? = mutex.withLock {
        try {
            val host = currentHost() ?: return@withLock null
            val captured = host.owner
            if (!selection.consume(captured)) return@withLock null
            val operation = generation.get()
            fun current() = generation.get() == operation && currentHost()?.owner == captured
            val selected = synchronized(elements) { elements[selection.elementId] }
            if (selected == null || selected.owner != captured || selected.kind != "chapter_link" ||
                clock() - selected.createdAt !in 0..BrowserDomPolicy.HANDLE_LIFETIME_MS || !BrowserDomPolicy.permittedAddress(selected.target)) return@withLock null
            withTimeoutOrNull(5_000L) {
                if (!current()) return@withTimeoutOrNull null
                val raw = host.evaluate(BrowserDomScripts.chapterTarget(selected.locator, selected.signature))
                if (!current() || raw == null || raw.length > BrowserDomPolicy.MAX_RESPONSE) return@withTimeoutOrNull null
                val payload = JSONObject(raw)
                if (payload.has("error") || !WebPageLoadState.sameDocument(captured.url, payload.optString("url")) ||
                    payload.optString("target") != selected.target) return@withTimeoutOrNull null
                selected.target
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
    }

    private fun unavailable(reason: String) = BrowserDomResult.Unavailable(reason)
}
