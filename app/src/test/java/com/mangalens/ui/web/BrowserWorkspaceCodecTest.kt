package com.mangalens.ui.web

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserWorkspaceCodecTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val url = "https://example.com/one?q=1"
    private val snapshot = BrowserWorkspaceSnapshot(
        listOf(BrowserTab(id, listOf(url, "https://example.com/two"), 0, "Title", true)), id,
        listOf(BrowserVisit(url, "Title", 123, 2)),
        listOf(BrowserBookmark("abcdef0123456789abcdef0123456789", url, "Saved", 122)), 4
    )

    @Test fun roundTripRetainsFullCursorMetadataAndSelection() {
        assertEquals(snapshot, BrowserWorkspaceCodec.decode(BrowserWorkspaceCodec.encode(snapshot)))
    }

    @Test fun unsupportedVersionAndUnsafeNestedUrlsAreRejected() {
        val encoded = BrowserWorkspaceCodec.encode(snapshot)
        assertTrue("Encoded session must carry bounded tab entries", JSONObject(String(encoded)).has("tabs"))
        assertNull(BrowserWorkspaceCodec.decode(JSONObject(String(encoded)).put("version", 2).toString().toByteArray()))
        val root = JSONObject(String(encoded))
        root.getJSONArray("tabs").getJSONObject(0).getJSONArray("entries").put(0, "javascript:alert(1)")
        assertNull(BrowserWorkspaceCodec.decode(root.toString().toByteArray()))
    }

    @Test fun duplicateTabOrBookmarkIdentitiesAndInvalidCursorAreRejected() {
        val duplicate = snapshot.copy(tabs = snapshot.tabs + snapshot.tabs)
        assertThrows(IllegalArgumentException::class.java) { BrowserWorkspaceCodec.encode(duplicate) }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserWorkspaceCodec.encode(snapshot.copy(tabs = listOf(snapshot.activeTab.copy(position = 20))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserWorkspaceCodec.encode(snapshot.copy(bookmarks = snapshot.bookmarks + snapshot.bookmarks))
        }
    }

    @Test fun oversizedAndWrongTypedFieldsDoNotProduceAnActiveUrl() {
        assertNull(BrowserWorkspaceCodec.decode(ByteArray(BrowserWorkspaceLimits.ENCODED_BYTES + 1)))
        val root = JSONObject(String(BrowserWorkspaceCodec.encode(snapshot)))
        root.put("revision", "4")
        assertNull(BrowserWorkspaceCodec.decode(root.toString().toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            BrowserWorkspaceCodec.encode(snapshot.copy(tabs = listOf(snapshot.activeTab.copy(title = "x".repeat(BrowserWorkspaceLimits.TITLE_CHARS + 1)))))
        }
    }
}
