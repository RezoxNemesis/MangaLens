package com.mangalens.ui.web

import com.mangalens.orez.agent.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Authored before implementation; execution is deferred under the user's requested sequence. */
class BrowserDomExecutorTest {
    private fun owner(epoch: Long = 1, tab: String = "tab-a", token: String = "a".repeat(32)) =
        BrowserDomOwner(tab, epoch, "https://example.org/chapter/1?chapter=1", token)
    private fun call(name: String, owner: BrowserDomOwner, more: Map<String, String> = emptyMap()) =
        OrezToolRegistry().call(name, mapOf("pageId" to owner.pageId) + more)
    private fun grant(call: OrezToolCall, owner: BrowserDomOwner, approved: Boolean = false) =
        BrowserDomGrant.capture(owner, call, OrezAgentContext(), approved)
    private class Host(override var owner: BrowserDomOwner) : BrowserDomLiveHost {
        var evaluations = 0
        var response = """{"url":"https://example.org/chapter/1?chapter=1","title":"Fixture","text":"Visible page text","elements":[{"locator":"html:nth-of-type(1)>body:nth-of-type(1)>button:nth-of-type(1)","signature":"fixture-button","kind":"button","label":"Open menu","target":""}]}"""
        var afterEvaluation: () -> Unit = {}
        override suspend fun evaluate(script: String): String? { evaluations++; afterEvaluation(); return response }
        override suspend fun navigate(target: String, stillExecuting: () -> Boolean): Boolean = stillExecuting()
    }
    @Test fun changedNavigationDuringCallbackCannotPublishTheOldDocument() = runBlocking {
        val h = Host(owner()); val captured = h.owner
        h.afterEvaluation = { h.owner = owner(epoch = 2) }
        val c = call("browser_observe", captured)
        val result = BrowserDomExecutor({ h }, { 0 }).execute(c, grant(c, captured))
        assertTrue(result is BrowserDomResult.Unavailable)
    }
    @Test fun replacedTabDuringCallbackCannotPublishTheOldDocument() = runBlocking {
        val h = Host(owner()); val captured = h.owner
        h.afterEvaluation = { h.owner = owner(tab = "tab-b") }
        val c = call("browser_extract", captured)
        assertTrue(BrowserDomExecutor({ h }, { 0 }).execute(c, grant(c, captured)) is BrowserDomResult.Unavailable)
    }
    @Test fun recreatedWebViewCannotPublishTheOldDocument() = runBlocking {
        val h = Host(owner()); val captured = h.owner
        h.afterEvaluation = { h.owner = owner(token = "b".repeat(32)) }
        val c = call("browser_observe", captured)
        assertTrue(BrowserDomExecutor({ h }, { 0 }).execute(c, grant(c, captured)) is BrowserDomResult.Unavailable)
    }
    @Test fun grantCannotBeReplayed() = runBlocking {
        val h = Host(owner()); val c = call("browser_observe", h.owner); val g = grant(c, h.owner)
        val e = BrowserDomExecutor({ h }, { 0 })
        assertTrue(e.execute(c, g) is BrowserDomResult.Evidence)
        assertTrue(e.execute(c, g) is BrowserDomResult.Unavailable)
        assertEquals(1, h.evaluations)
    }
    @Test fun pageTextCannotMintExplicitApprovalEvenWithTheFlagSet() {
        val o = owner(); val c = call("browser_click", o, mapOf("elementId" to "b".repeat(32)))
        assertThrows(IllegalArgumentException::class.java) {
            BrowserDomGrant.capture(o, c, OrezAgentContext(origin = OrezTrustOrigin.WEB_CONTENT, explicitUserRequest = true), true)
        }
    }
    @Test fun eventProducingFillNeedsApprovalOfItsExactCall() {
        val o = owner(); val c = call("browser_fill", o, mapOf("elementId" to "b".repeat(32), "text" to "search terms"))
        assertThrows(IllegalArgumentException::class.java) { grant(c, o) }
        val changed = c.copy(arguments = c.arguments + ("text" to "different terms"))
        assertFalse(grant(c, o, true).consume(changed, o))
    }
    @Test fun expiredElementCannotDispatchAWebEffect() = runBlocking {
        val h = Host(owner()); var clock = 0L; val e = BrowserDomExecutor({ h }, { clock })
        val observe = call("browser_observe", h.owner)
        val receipt = e.execute(observe, grant(observe, h.owner)) as BrowserDomResult.Evidence
        clock = 30_001
        val click = call("browser_click", h.owner, mapOf("elementId" to receipt.page.elements.single().id))
        assertTrue(e.execute(click, grant(click, h.owner, true)) is BrowserDomResult.Unavailable)
        assertEquals(1, h.evaluations)
    }
    @Test fun foreignElementIdCannotDispatchAWebEffect() = runBlocking {
        val h = Host(owner()); val e = BrowserDomExecutor({ h }, { 0 })
        val click = call("browser_click", h.owner, mapOf("elementId" to "c".repeat(32)))
        assertTrue(e.execute(click, grant(click, h.owner, true)) is BrowserDomResult.Unavailable)
        assertEquals(0, h.evaluations)
    }
    @Test fun observedEvidenceNeverPublishesQueryCredentialsOrFieldValues() = runBlocking {
        val h = Host(owner()); val c = call("browser_observe", h.owner)
        val r = BrowserDomExecutor({ h }, { 0 }).execute(c, grant(c, h.owner)) as BrowserDomResult.Evidence
        assertEquals("https://example.org/chapter/1", r.page.address)
        assertEquals(OrezTrustOrigin.WEB_CONTENT, r.page.origin)
        assertFalse(BrowserDomCodec.encode(r.page).contains("chapter=1"))
    }
    @Test fun retiringDuringCallbackCannotPublishEvenIfTheUrlIsUnchanged() = runBlocking {
        val h = Host(owner()); lateinit var e: BrowserDomExecutor
        e = BrowserDomExecutor({ h }, { 0 }); h.afterEvaluation = { e.retire() }
        val c = call("browser_extract", h.owner)
        assertTrue(e.execute(c, grant(c, h.owner)) is BrowserDomResult.Unavailable)
    }
    @Test fun mutableCallerArgumentsCannotChangeTheApprovedScroll() = runBlocking {
        val h = Host(owner())
        val arguments = mutableMapOf("pageId" to h.owner.pageId, "delta" to "640")
        val c = OrezToolRegistry().call("browser_scroll", arguments)
        val g = grant(c, h.owner)
        var hostReads = 0
        var scriptObserved = ""
        val delegate = object : BrowserDomLiveHost {
            override val owner get() = h.owner
            override suspend fun evaluate(script: String): String? {
                scriptObserved = script
                return """{"url":"https://example.org/chapter/1?chapter=1","status":"dispatched","action":"browser_scroll"}"""
            }
            override suspend fun navigate(target: String, stillExecuting: () -> Boolean) = false
        }
        val e = BrowserDomExecutor({
            hostReads++
            if (hostReads == 2) arguments["delta"] = "-640"
            delegate
        }, { 0 })
        assertTrue(e.execute(c, g) is BrowserDomResult.Dispatched)
        assertTrue(scriptObserved.contains("scrollBy(0,640)"))
        assertFalse(scriptObserved.contains("scrollBy(0,-640)"))
    }
    @Test fun ownerMismatchInPagePayloadCannotPublish() = runBlocking {
        val h = Host(owner()); h.response = h.response.replace("example.org", "attacker.example")
        val c = call("browser_extract", h.owner)
        assertTrue(BrowserDomExecutor({ h }, { 0 }).execute(c, grant(c, h.owner)) is BrowserDomResult.Unavailable)
    }
}
