package com.mangalens.ui.web

import com.mangalens.orez.agent.*
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** A foreground document receipt. A URL alone never identifies a live browser authority. */
internal data class BrowserDomOwner(val tabId: String, val navigationEpoch: Long, val url: String, val viewToken: String, val profileKey: String = "normal") {
    init {
        require(tabId.isNotBlank() && tabId.length <= 128 && '\u0000' !in tabId)
        require(navigationEpoch > 0 && viewToken.matches(Regex("[a-f0-9]{32}")))
        require(BrowserDomPolicy.permittedAddress(url))
        require(BrowserProfilePolicy.validKey(profileKey))
    }
    val pageId: String get() = MessageDigest.getInstance("SHA-256")
        .digest((listOf(tabId, navigationEpoch.toString(), url, viewToken) +
            if (profileKey == "normal") emptyList() else listOf(profileKey)).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal object BrowserDomPolicy {
    const val MAX_ELEMENTS = 40
    const val MAX_TEXT = 6_000
    const val MAX_RESPONSE = 65_536
    const val HANDLE_LIFETIME_MS = 30_000L
    private val credentialQuery = Regex("(?i)^(?:password|passwd|pwd|token|access_token|refresh_token|id_token|secret|api_key|apikey|authorization|auth|session|sessionid|jwt|code)$")
    private val locator = Regex("[a-z][a-z0-9-]{0,40}:nth-of-type\\([1-9][0-9]{0,3}\\)(?:>[a-z][a-z0-9-]{0,40}:nth-of-type\\([1-9][0-9]{0,3}\\)){0,24}")
    val names = setOf("browser_observe", "browser_extract", "browser_navigate", "browser_click", "browser_fill", "browser_scroll")

    fun permittedAddress(value: String): Boolean = runCatching {
        if (value.length !in 1..8_192 || !com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(value)) return@runCatching false
        val uri = URI(value)
        uri.rawUserInfo == null && uri.host != null && uri.rawQuery.orEmpty().split('&').none { item ->
            val key = java.net.URLDecoder.decode(item.substringBefore('='), "UTF-8")
            credentialQuery.matches(key)
        }
    }.getOrDefault(false)

    /** Query strings and fragments stay inside the native host, outside agent evidence. */
    fun displayAddress(value: String): String = runCatching {
        val uri = URI(value)
        if (!permittedAddress(value)) "" else URI(uri.scheme, null, uri.host, uri.port, uri.path.ifBlank { "/" }, null, null).toASCIIString()
    }.getOrDefault("")

    fun validLocator(value: String) = value.length <= 2_048 && locator.matches(value)
    fun clean(value: String, limit: Int): String = value.replace(Regex("[\\p{Cntrl}\\s]+"), " ")
        .replace(Regex("(?i)\\b(password|passwd|token|api[_ -]?key|secret)\\s*[:=]\\s*\\S+"), "[redacted]")
        .trim().take(limit).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }

    fun validateArguments(name: String, arguments: Map<String, String>) {
        require(name in names) { "Unknown browser tool." }
        val required = when (name) {
            "browser_navigate" -> setOf("pageId", "value")
            "browser_click" -> setOf("pageId", "elementId")
            "browser_fill" -> setOf("pageId", "elementId", "text")
            "browser_scroll" -> setOf("pageId", "delta")
            else -> setOf("pageId")
        }
        val allowed = required + if (name in setOf("browser_observe", "browser_extract")) setOf("query") else emptySet()
        require(arguments.keys.containsAll(required) && arguments.keys.all { it in allowed }) { "Browser tool arguments do not match its typed contract." }
        require(arguments.getValue("pageId").matches(Regex("[a-f0-9]{64}"))) { "Capture the current browser page first." }
        arguments["elementId"]?.let { require(it.matches(Regex("[a-f0-9]{32}"))) { "Select one observed browser element." } }
        arguments["query"]?.let { require(it.length <= 256 && '\u0000' !in it) { "Page queries accept up to 256 literal characters." } }
        arguments["text"]?.let { require(it.length in 1..256 && '\u0000' !in it && clean(it, 256) == it.trim().replace(Regex("\\s+"), " ")) { "Fill accepts bounded non-sensitive text only." } }
        arguments["value"]?.let { require(permittedAddress(it)) { "Use an HTTP or HTTPS address without account credentials or credential tokens." } }
        arguments["delta"]?.let { require(it.toIntOrNull()?.let { delta -> delta in -2_048..2_048 && delta != 0 } == true) { "Scroll accepts a nonzero distance up to 2048 pixels." } }
    }
}

/** Native-only, one-use authority; there is no JSON constructor or page-controlled approval flag. */
internal class BrowserDomGrant private constructor(private val owner: BrowserDomOwner, private val call: OrezToolCall) {
    private val used = AtomicBoolean(false)
    fun consume(request: OrezToolCall, current: BrowserDomOwner): Boolean =
        used.compareAndSet(false, true) && current == owner && request == call

    companion object {
        fun capture(owner: BrowserDomOwner, call: OrezToolCall, context: OrezAgentContext, approved: Boolean = false): BrowserDomGrant {
            val capturedCall = call.copy(arguments = call.arguments.toMap())
            OrezToolRegistry().validate(capturedCall)
            require(capturedCall.name in BrowserDomPolicy.names && capturedCall.arguments["pageId"] == owner.pageId)
            require(context.origin == OrezTrustOrigin.USER && context.explicitUserRequest) { "Only an explicit user request can authorize browser tools." }
            val verdict = OrezPolicyEngine().evaluate(capturedCall, context)
            require(verdict.allowed && (!verdict.requiresApproval || approved)) { "Approve this exact browser action before it can change the page." }
            return BrowserDomGrant(owner, capturedCall)
        }
    }
}

/** Exact user-selected chapter handle; source URLs remain native and are never serialized to a model. */
internal class BrowserChapterSelection private constructor(val owner: BrowserDomOwner, val elementId: String) {
    private val consumed = AtomicBoolean(false)
    fun consume(current: BrowserDomOwner): Boolean = consumed.compareAndSet(false, true) && current == owner
    companion object {
        fun capture(owner: BrowserDomOwner, elementId: String, context: OrezAgentContext): BrowserChapterSelection {
            require(context.origin == OrezTrustOrigin.USER && context.explicitUserRequest)
            require(elementId.matches(Regex("[a-f0-9]{32}")))
            return BrowserChapterSelection(owner, elementId)
        }
    }
}

internal data class BrowserDomElement(val id: String, val kind: String, val label: String, val address: String = "")
internal data class BrowserDomEvidence(val pageId: String, val address: String, val title: String, val text: String,
    val elements: List<BrowserDomElement>, val truncated: Boolean, val origin: OrezTrustOrigin = OrezTrustOrigin.WEB_CONTENT)
internal sealed interface BrowserDomResult {
    data class Evidence(val page: BrowserDomEvidence) : BrowserDomResult
    data class Dispatched(val pageId: String, val action: String, val address: String = "") : BrowserDomResult
    data class Unavailable(val reason: String) : BrowserDomResult
}

internal interface BrowserDomLiveHost {
    val owner: BrowserDomOwner
    suspend fun evaluate(script: String): String?
    suspend fun navigate(target: String, stillExecuting: () -> Boolean): Boolean
}

internal object BrowserDomCodec {
    fun encode(page: BrowserDomEvidence): String = JSONObject().put("pageId", page.pageId)
        .put("trustOrigin", page.origin.name).put("address", page.address).put("title", page.title).put("text", page.text)
        .put("truncated", page.truncated).put("elements", JSONArray().apply { page.elements.forEach {
            put(JSONObject().put("id", it.id).put("kind", it.kind).put("label", it.label).put("address", it.address))
        } }).toString()

    fun decode(value: String): BrowserDomEvidence {
        require(value.length <= BrowserDomPolicy.MAX_RESPONSE)
        val json = JSONObject(value); require(json.getString("trustOrigin") == OrezTrustOrigin.WEB_CONTENT.name)
        val array = json.getJSONArray("elements"); require(array.length() <= BrowserDomPolicy.MAX_ELEMENTS)
        return BrowserDomEvidence(json.getString("pageId"), BrowserDomPolicy.clean(json.getString("address"), 8_192),
            BrowserDomPolicy.clean(json.getString("title"), 160), BrowserDomPolicy.clean(json.getString("text"), BrowserDomPolicy.MAX_TEXT),
            (0 until array.length()).map { val e = array.getJSONObject(it)
                BrowserDomElement(e.getString("id"), e.getString("kind"), BrowserDomPolicy.clean(e.getString("label"), 120), e.getString("address"))
            }, json.getBoolean("truncated"))
    }
}
