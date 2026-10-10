package com.mangalens.ui.web

import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.agent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Authored session controls use actual typed DOM executor and fake host/model, never native inference. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserGoalSessionTest {
    private val initial = BrowserDomOwner("tab-a", 1, "https://example.org/page", "a".repeat(32))
    private class Host(override var owner: BrowserDomOwner) : BrowserDomLiveHost {
        var text = "Before"; var actions = 0; var reads = 0; var missingActionReceipt = false
        override suspend fun evaluate(script: String): String? {
            if (script.contains("scrollBy") || script.contains("e.click()")) {
                actions++; text = "After actual action"
                if (missingActionReceipt) return null
                val action = if (script.contains("scrollBy")) "browser_scroll" else "browser_click"
                return JSONObject().put("url", owner.url).put("status", "dispatched").put("action", action).toString()
            }
            reads++
            val element = JSONObject().put("locator", "html:nth-of-type(1)>body:nth-of-type(1)>button:nth-of-type(1)")
                .put("signature", "fixture").put("kind", "button").put("label", "Open details").put("target", "")
            return JSONObject().put("url", owner.url).put("title", "Fixture").put("text", text)
                .put("elements", org.json.JSONArray().apply { if (!script.contains("createTreeWalker")) put(element) }).toString()
        }
        override suspend fun navigate(target: String, stillExecuting: () -> Boolean): Boolean {
            if (!stillExecuting()) return false
            actions++; owner = owner.copy(navigationEpoch = owner.navigationEpoch + 1, url = target); return true
        }
    }
    private class Model(var reply: (String) -> String?) : BrowserGoalModel {
        var proposals = 0
        override suspend fun capturePin() = OrezModelPin("fixture", "b".repeat(64), 1234)
        override suspend fun propose(input: String, pin: OrezModelPin, owner: NativeComputePrecondition): String? {
            owner.validate(false); proposals++; return reply(input)
        }
    }
    private fun raw(tool: String, arguments: JSONObject = JSONObject()) = JSONObject().put("answer", "Suggestion")
        .put("step", JSONObject().put("tool", tool).put("arguments", arguments)).toString()
    private suspend fun TestScope.fixture(block: suspend () -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }
    private fun tools(host: Host) = OrezBrowserTools(BrowserDomExecutor({ host }, { 0L }))
    private fun session(host: Host, model: Model) = BrowserGoalSession(tools(host), model, { 0L }, Dispatchers.Unconfined)

    @Test fun proposedNetworkEffectDoesNotRunWithoutExactNativeConfirmation() = runTest { fixture {
        val host = Host(initial)
        val model = Model { input -> val id = JSONObject(input).getJSONObject("WEB_CONTENT").getJSONArray("elements").getJSONObject(0).getString("id")
            raw("browser_click", JSONObject().put("elementId", id)) }
        val session = session(host, model); val view = session.start("Open details")
        assertEquals(0, host.actions)
        try { session.execute(view.proposal!!, false); fail("Approval required") } catch (_: IllegalArgumentException) { }
        assertEquals(0, host.actions)
    } }
    @Test fun changedOwnerDuringInferenceCannotPresentOrDispatchTheOldPlan() = runTest { fixture {
        val host = Host(initial); val model = Model { host.owner = initial.copy(navigationEpoch = 2); raw("browser_scroll", JSONObject().put("delta", "640")) }
        val session = session(host, model)
        try { session.start("Scroll down"); fail("Retired owner required") } catch (_: IllegalStateException) { }
        assertFalse(session.ownsCurrentPage()); assertEquals(0, host.actions)
        assertEquals(1, model.proposals)
        assertEquals(initial.copy(navigationEpoch = 2), host.owner)
    } }
    @Test fun changedProposalArgumentsCannotBorrowLaterApproval() = runTest { fixture {
        val host = Host(initial); val model = Model { raw("browser_scroll", JSONObject().put("delta", "640")) }
        val session = session(host, model); val view = session.start("Scroll down")
        val proposal = requireNotNull(view.proposal)
        @Suppress("UNCHECKED_CAST") val arguments = proposal.call!!.arguments as MutableMap<String, String>
        arguments["delta"] = "-640"
        try { session.execute(proposal, true); fail("Changed candidate must fail") } catch (_: IllegalArgumentException) { }
        assertEquals(0, host.actions)
    } }
    @Test fun postActionTurnUsesActualFreshEvidenceInsteadOfModelSuccessText() = runTest { fixture {
        val host = Host(initial); var promptSeen = ""
        val model = Model { input -> promptSeen = input
            if (host.actions == 0) raw("browser_scroll", JSONObject().put("delta", "640")) else """{"answer":"Read the current evidence","step":null}""" }
        val session = session(host, model); val first = session.start("Scroll down and tell me what is visible")
        val second = session.execute(first.proposal!!, false)
        assertEquals("After actual action", second.page.text); assertTrue(promptSeen.contains("After actual action"))
        assertTrue(second.finished); assertTrue(second.summary.contains("completion remain unverified"))
        assertEquals(1, host.actions)
    } }
    @Test fun threeProposalsCannotContinueIntoAnUnboundedLocalLoop() = runTest { fixture {
        val host = Host(initial); val model = Model { raw("browser_scroll", JSONObject().put("delta", "640")) }
        val session = session(host, model); var view = session.start("Scroll down")
        repeat(3) { view = session.execute(view.proposal!!, false) }
        assertTrue(view.finished); assertNull(view.proposal); assertEquals(3, model.proposals); assertEquals(3, host.actions)
        assertEquals(8, host.reads)
    } }
    @Test fun navigationDispatchRetiresInsteadOfInventingANewDocumentScope() = runTest { fixture {
        val host = Host(initial); val model = Model { raw("browser_navigate", JSONObject().put("value", "https://example.org/next")) }
        val session = session(host, model); val view = session.start("Open https://example.org/next")
        try { session.execute(view.proposal!!, true); fail("New document needs a fresh goal") } catch (_: IllegalStateException) { }
        assertFalse(session.ownsCurrentPage()); assertEquals(1, model.proposals); assertEquals(1, host.actions)
        assertTrue(session.summary().contains("1 browser action(s) have confirmed DISPATCHED"))
    } }
    @Test fun missingDispatchReceiptDoesNotClaimNoWebsiteActionOccurred() = runTest { fixture {
        val host = Host(initial); host.missingActionReceipt = true
        val model = Model { raw("browser_scroll", JSONObject().put("delta", "640")) }
        val session = session(host, model); val view = session.start("Scroll down")
        try { session.execute(view.proposal!!, false); fail("Missing receipt required") } catch (_: IllegalStateException) { }
        assertEquals(1, host.actions); assertTrue(session.summary().contains("attempted action has no dispatch receipt"))
    } }
    @Test fun explicitRetirementDuringInferenceCannotPublishACall() = runTest { fixture {
        val host = Host(initial); lateinit var session: BrowserGoalSession
        val model = Model { session.retire(); raw("browser_scroll", JSONObject().put("delta", "640")) }
        session = session(host, model)
        try { session.start("Scroll down"); fail("Retired goal required") } catch (_: IllegalStateException) { }
        assertFalse(session.ownsCurrentPage()); assertEquals(0, host.actions)
    } }
}
