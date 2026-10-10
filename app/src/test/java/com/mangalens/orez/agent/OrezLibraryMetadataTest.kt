package com.mangalens.orez.agent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrezLibraryMetadataTest {
    private val id="a".repeat(32)
    private fun body()=JSONObject().put("version",2).put("id",id).put("title","ＦＯＯ स्वदेश").put("sourceUrl","https://private.example.org/saved")
        .put("notes","Hindi dialogue note").put("seriesTitle","Romance").put("collections",JSONArray(listOf("Favorites")))
        .put("bookmarked",true).put("position",0).put("readingStatus","READING").put("addedAt",1).put("lastReadAt",2)
        .put("pages",JSONArray(listOf(JSONObject().put("index",1).put("source","https://private.example.org/page.png").put("file","one.png"))))
    private fun decode(body:JSONObject=body())=OrezLibraryMetadata.decode(id,body.toString().toByteArray())
    @Test fun metadataSearchMatchesUnicodeCompatibilityAndAllWords() {
        val entry=decode(); assertTrue(OrezLibraryMetadata.matches(entry,"foo romance"));assertTrue(OrezLibraryMetadata.matches(entry,"dialogue favorites"));assertFalse(OrezLibraryMetadata.matches(entry,"missing"))
    }
    @Test fun returnedSummaryContainsNoSourceUrlsNotesPathsOrInstructions() {
        val summary=decode().summary().toString();assertFalse(summary.contains("private.example"));assertFalse(summary.contains("one.png"));assertFalse(summary.contains("dialogue note"))
    }
    @Test fun neverOpensMissingOriginalPixelsDuringMetadataDecode() {
        val entry=decode();assertEquals(1,entry.pageCount);assertTrue(entry.bookmarked)
    }
    @Test fun sourceTopologyChangesForSameChapterUrlWithDifferentPageOrderOrFile() {
        val first=decode();val changed=body();changed.getJSONArray("pages").getJSONObject(0).put("file","two.png")
        assertNotEquals(first.sourceTopologySha256,decode(changed).sourceTopologySha256)
        assertEquals(first.sourceTopologySha256,decode(body().put("bookmarked",false).put("position",0)).sourceTopologySha256)
    }
    @Test fun malformedScopeCannotInventChapterOrDuplicatePageIndices() {
        assertTrue(runCatching { decode(body().put("id","b".repeat(32))) }.isFailure)
        val duplicate=body();duplicate.getJSONArray("pages").put(duplicate.getJSONArray("pages").getJSONObject(0))
        assertTrue(runCatching { decode(duplicate) }.isFailure)
    }
}
