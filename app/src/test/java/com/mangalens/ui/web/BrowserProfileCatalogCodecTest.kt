package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class BrowserProfileCatalogCodecTest {
    private fun custom(n: Int) = BrowserProfileChoice(BrowserProfileKind.CUSTOM, n.toString(16).padStart(32, '0'), "Custom $n")
    private fun rejects(body: () -> Unit) { try { body(); fail("Expected rejected catalog") } catch (_: IllegalArgumentException) {} }
    @Test fun fourCustomProfilesRoundTripWithoutPageContent() { val profiles = (1..4).map(::custom); val bytes = BrowserProfileCatalogCodec.encode(profiles); assertEquals(profiles, BrowserProfileCatalogCodec.decode(bytes)); assertFalse(String(bytes).contains("url")); assertFalse(String(bytes).contains("history")) }
    @Test fun fifthCustomProfileCannotBeEncoded() { rejects { BrowserProfileCatalogCodec.encode((1..5).map(::custom)) } }
    @Test fun duplicateProfileIdentityCannotBeEncoded() { rejects { BrowserProfileCatalogCodec.encode(listOf(custom(1), custom(1).copy(label = "Alias"))) } }
    @Test fun privateMetadataCannotBeWrittenIntoCatalog() { rejects { BrowserProfileCatalogCodec.encode(listOf(BrowserProfileChoice.privateSession())) } }
    @Test fun aCatalogWithPageContentIsRejectedRatherThanImported() { val root = JSONObject(String(BrowserProfileCatalogCodec.encode(emptyList()))).put("history", JSONArray()); rejects { BrowserProfileCatalogCodec.decode(root.toString().toByteArray()) } }
    @Test fun unknownCatalogVersionCannotReplaceExistingProfiles() { val root = JSONObject(String(BrowserProfileCatalogCodec.encode(emptyList()))).put("version", 2); rejects { BrowserProfileCatalogCodec.decode(root.toString().toByteArray()) } }
    @Test fun extraRowFieldsCannotCarryAHiddenPrivateUrl() { val root = JSONObject(String(BrowserProfileCatalogCodec.encode(listOf(custom(1))))); root.getJSONArray("custom").getJSONObject(0).put("page", "https://private.invalid/"); rejects { BrowserProfileCatalogCodec.decode(root.toString().toByteArray()) } }
    @Test fun oversizedCatalogIsRejectedBeforeJsonDecode() { rejects { BrowserProfileCatalogCodec.decode(ByteArray(BrowserProfilePolicy.CATALOG_BYTES + 1)) } }
}
