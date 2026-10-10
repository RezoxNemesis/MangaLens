package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test

/** Authored during implementation phase; no execution receipt accompanies this packet. */
class BrowserProfilePolicyTest {
    private fun custom(id: String = "a".repeat(32), label: String = "Study") = BrowserProfileChoice(BrowserProfileKind.CUSTOM, id, label)
    private fun rejects(body: () -> Unit) { try { body(); fail("Expected rejected profile") } catch (_: IllegalArgumentException) {} }
    @Test fun normalRetainsLegacyDirectoryAndNativeDefault() { assertEquals("browser_workspace", BrowserProfilePolicy.workspaceDirectory(BrowserProfileChoice.Normal)); assertEquals("Default", BrowserProfileChoice.Normal.nativeName) }
    @Test fun workHasSeparateStableNativeAndJournalIdentity() { assertEquals("browser_profiles/work", BrowserProfilePolicy.workspaceDirectory(BrowserProfileChoice.Work)); assertEquals("mangalens_work_v1", BrowserProfileChoice.Work.nativeName) }
    @Test fun customNamesNeverBecomeDirectoryPaths() { val p = custom(label = "../../Work"); assertEquals("browser_profiles/custom_" + "a".repeat(32), BrowserProfilePolicy.workspaceDirectory(p)); assertFalse(p.nativeName.contains("../")) }
    @Test fun distinctCustomPinsHaveDistinctStores() { assertNotEquals(custom().nativeName, custom("b".repeat(32)).nativeName); assertNotEquals(custom().key, custom("b".repeat(32)).key) }
    @Test fun privateCannotObtainAPersistentJournalDirectory() { rejects { BrowserProfilePolicy.workspaceDirectory(BrowserProfileChoice.privateSession()) } }
    @Test fun eachPrivateVisitGetsANewNativeLifetime() { assertNotEquals(BrowserProfileChoice.privateSession().nativeName, BrowserProfileChoice.privateSession().nativeName) }
    @Test fun privateCleanupCannotDeleteForeignOrMalformedNames() { assertTrue(BrowserProfilePolicy.ownedPrivateName("mangalens_private_v1_" + "a".repeat(32))); for (p in listOf("Default", "mangalens_work_v1", custom().nativeName, "mangalens_private_v1_../foreign", "mangalens_private_v1_" + "A".repeat(32))) assertFalse(BrowserProfilePolicy.ownedPrivateName(p)) }
    @Test fun malformedProfileTokensAreRejected() { rejects { custom("../other").validate() }; rejects { custom("A".repeat(32)).validate() } }
    @Test fun profileLabelsAreBoundedAndControlFree() { rejects { custom(label = "").validate() }; rejects { custom(label = "x".repeat(41)).validate() }; rejects { custom(label = "a\nb").validate() } }
    @Test fun defaultIdentitiesCannotBeForgedWithExtraTokens() { rejects { BrowserProfileChoice(BrowserProfileKind.NORMAL, "a".repeat(32), "Normal").validate() }; rejects { BrowserProfileChoice(BrowserProfileKind.WORK, "", "Normal").validate() } }
    @Test fun everyIsolatedProfileSuppressesDefaultCookieHandoffs() { assertTrue(BrowserProfileChoice.Normal.allowsNativeSourceHandoff); for (p in listOf(BrowserProfileChoice.Work, custom(), BrowserProfileChoice.privateSession())) assertFalse(p.allowsNativeSourceHandoff) }
    @Test fun pageReceiptProfileKeysAreStrictlyBounded() { for (p in listOf("normal", "work", custom().key, BrowserProfileChoice.privateSession().key)) assertTrue(BrowserProfilePolicy.validKey(p)); for (p in listOf("", "normal_other", "custom_../x", "private_" + "a".repeat(33))) assertFalse(BrowserProfilePolicy.validKey(p)) }
}
