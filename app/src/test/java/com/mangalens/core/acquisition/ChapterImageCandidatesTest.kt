package com.mangalens.core.acquisition

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChapterImageCandidatesTest {
    private val page = "https://reader.example/chapter/8"
    private fun encoded(url: String = page, rows: JSONArray): String = JSONObject.quote(
        JSONObject().put("pageUrl", url).put("images", rows).toString())

    @Test fun structuredRecordsKeepOpaqueCommasOrderAndAnUnmarkedOriginal() {
        val url = "https://cdn.example/original.png?parts=one,two&token=private"
        val rows = JSONArray().put(JSONObject().put("url", url).put("promotion", "COMMERCIAL_LINK"))
            .put(JSONObject().put("url", "/second.png").put("promotion", "page-invented-rule"))
            .put(JSONObject().put("url", url))
        val result = ChapterImageCandidates.decode(encoded(rows = rows), page)
        assertEquals(listOf(url, "https://reader.example/second.png"), result.map { it.url })
        assertEquals(ChapterImagePromotion.COMMERCIAL_LINK, result.first().promotion)
        assertNull(result.last().promotion)
    }

    @Test fun wrongDocumentAndCredentialBearingSourcesAreRejected() {
        val rows = JSONArray().put(JSONObject().put("url", "/original.png"))
        assertTrue(ChapterImageCandidates.decode(encoded("https://reader.example/chapter/9", rows), page).isEmpty())
        val credentials = JSONArray().put(JSONObject().put("url", "https://user:secret@cdn.example/image.png"))
        assertTrue(ChapterImageCandidates.decode(encoded(rows = credentials), page).isEmpty())
    }

    @Test fun oversizedRecordListFailsClosedInsteadOfSilentlyPublishingPartialOrder() {
        val rows = JSONArray()
        repeat(ChapterImageCandidates.MAX_IMAGES + 1) { rows.put(JSONObject().put("url", "/$it.png")) }
        assertTrue(ChapterImageCandidates.decode(encoded(rows = rows), page).isEmpty())
    }
}
