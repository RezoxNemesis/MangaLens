package com.mangalens.orez.agent

import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.orez.*
import com.mangalens.ui.web.*
import org.json.JSONObject

/** Model output is a suggestion. Only the native UI can turn it into a one-use grant. */
internal data class BrowserGoalProposal(val answer: String, val call: OrezToolCall?)
internal interface BrowserGoalModel {
    suspend fun capturePin(): OrezModelPin?
    suspend fun propose(input: String, pin: OrezModelPin, owner: NativeComputePrecondition): String?
}
internal class OrezBrowserGoalPlanner(private val service: OrezLocalModelService) : BrowserGoalModel {
    override suspend fun capturePin() = service.captureModelPin(OrezModelTask.BROWSER_GOAL)
    override suspend fun propose(input: String, pin: OrezModelPin, owner: NativeComputePrecondition): String? =
        qualifiedText(input, pin, service.planBrowserGoalWithReceipt(input, pin, owner))
    companion object {
        internal fun qualifiedText(input: String, pin: OrezModelPin, answer: OrezModelAnswer?): String? =
            answer?.takeIf { it.model == pin && OrezBrowserAgentProfile.completed(input, it.completion) }
                ?.text?.trim()?.takeIf { it.isNotBlank() && it.length <= 8_192 }
    }
}

internal object BrowserGoalPlanDecoder {
    const val MAX_GOAL = 1_000
    const val MAX_TURNS = 3
    /** Native page identity is injected. Risk, permission and page identity never come from JSON. */
    fun decode(raw: String, goal: String, owner: BrowserDomOwner, evidence: BrowserDomEvidence): BrowserGoalProposal {
        require(raw.length <= 8_192 && evidence.pageId == owner.pageId && evidence.origin == OrezTrustOrigin.WEB_CONTENT)
        val json = JSONObject(raw)
        require(json.keys().asSequence().toSet() == setOf("answer", "step")) { "Local planning returned an unsupported response." }
        val answer = json.get("answer"); require(answer is String && answer.length <= 1_200)
        if (json.isNull("step")) return BrowserGoalProposal(BrowserDomPolicy.clean(answer, 1_200), null)
        val step = json.getJSONObject("step")
        require(step.keys().asSequence().toSet() == setOf("tool", "arguments"))
        val name = step.get("tool"); require(name is String && name in BrowserDomPolicy.names)
        val args = step.getJSONObject("arguments")
        val arguments = args.keys().asSequence().associateWith { key ->
            val value = args.get(key); require(value is String && value.length <= 8_192); value
        }
        require("pageId" !in arguments) { "A model cannot choose browser ownership." }
        val call = OrezToolRegistry().call(name, (arguments + ("pageId" to owner.pageId)).toMap())
        if (name in setOf("browser_click", "browser_fill")) {
            val element = requireNotNull(evidence.elements.firstOrNull { it.id == arguments["elementId"] }) { "Select an actual observed element." }
            require(if (name == "browser_fill") element.kind == "field" else element.kind in setOf("link", "chapter_link", "button"))
        }
        if (name == "browser_fill") require(goal.contains(arguments.getValue("text"))) { "Fill text must be supplied in your goal." }
        if (name == "browser_navigate") {
            val requested = Regex("https?://[^\\s<>\\\"']+").findAll(goal).map { it.value.trimEnd('.', ',', ';', ')', ']') }.toSet()
            require(arguments.getValue("value") in requested) { "Opening a new address requires a URL supplied in your goal. Use an observed link for page navigation." }
        }
        return BrowserGoalProposal(BrowserDomPolicy.clean(answer, 1_200), call)
    }

    fun prompt(goal: String, page: BrowserDomEvidence, dispatched: List<String>, turn: Int): String {
        // Limit total characters rather than silently cutting a JSON document mid-field.
        var elements = page.elements.take(16).map { it.copy(address = it.address.take(160)) }
        var text = page.text.take(2_800)
        fun encode(): String = JSONObject().put("USER_GOAL", goal).put("turn", turn).put("maximumTurns", MAX_TURNS)
            .put("WEB_CONTENT", JSONObject(BrowserDomCodec.encode(page.copy(text = text, elements = elements,
                truncated = page.truncated || text.length < page.text.length || elements.size < page.elements.size))))
            .put("NATIVE_DISPATCH_RECEIPTS", org.json.JSONArray(dispatched.take(MAX_TURNS)))
            .put("AVAILABLE_TOOLS", OrezToolRegistry().browserCatalog())
            .put("NATIVE_SCOPE", "Arguments are string values. Do not supply pageId, risk, approval or other fields. Native scope pins the current document and requires exact user confirmation of each network mutation.")
            .toString()
        var input = encode()
        while (input.length > OrezBrowserAgentProfile.MAX_INPUT && elements.isNotEmpty()) { elements = elements.dropLast(1); input = encode() }
        while (input.length > OrezBrowserAgentProfile.MAX_INPUT && text.length > 500) { text = text.take(text.length / 2); input = encode() }
        require(input.length <= OrezBrowserAgentProfile.MAX_INPUT) { "This goal exceeds the local planning budget. Narrow your goal." }
        return input
    }
}
