package com.mangalens.orez.agent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrezLibraryScopeTest {
    private fun row(id: String="a".repeat(32),bytes: Long=100)=OrezLibraryManifestRevision(id,"b".repeat(64),bytes,"c".repeat(64))
    private fun search()=OrezLibraryRequest(OrezLibraryOperation.SEARCH,query="One")
    @Test fun snapshotsCallerOwnedInventoryAndIdentity() {
        val list=mutableListOf(row());val scope=OrezLibraryScope.capture(search(),null,list,false);val hash=scope.identity
        list.clear();assertEquals(1,scope.inventory.size);assertEquals(hash,scope.identity)
        assertTrue(runCatching { (scope.inventory as MutableList<*> ).clear() }.isFailure)
    }
    @Test fun rejectsDuplicateIdentitiesAndPerManifestSizeOverflow() {
        assertTrue(runCatching { OrezLibraryScope.capture(search(),null,listOf(row(),row()),false) }.isFailure)
        assertTrue(runCatching { row(bytes=2_000_001).validate() }.isFailure)
    }
    @Test fun rejectsOver128And16MiBInventoryBeforeDigest() {
        val many=(0..128).map { row(it.toString(16).padStart(32,'0')) }
        assertTrue(runCatching { OrezLibraryScope.capture(search(),null,many,false) }.isFailure)
        val large=(0..8).map { row(it.toString(16).padStart(32,'0'),2_000_000) }
        assertTrue(runCatching { OrezLibraryScope.capture(search(),null,large,false) }.isFailure)
    }
    @Test fun selectedRequestCannotBorrowAnotherChapterOrGlobalScope() {
        val request=OrezLibraryRequest(OrezLibraryOperation.BOOKMARK,bookmarked=true)
        assertTrue(runCatching { OrezLibraryScope.capture(request,"d".repeat(32),listOf(row()),false) }.isFailure)
        assertTrue(runCatching { OrezLibraryScope.capture(search(),"a".repeat(32),listOf(row()),false) }.isFailure)
    }
    @Test fun existingUuidSeriesAndAssociationRevisionRoundTrip() {
        val request=OrezLibraryRequest(OrezLibraryOperation.READ_SERIES)
        val series=OrezLibrarySeriesRevision("e3ab5270-9a82-44b7-a972-56e466035bc6",2,"d".repeat(64),"e".repeat(64))
        val scope=OrezLibraryScope.capture(request,"a".repeat(32),listOf(row()),false,series)
        assertEquals(scope,OrezLibraryScopeCodec.decode(OrezLibraryScopeCodec.encode(scope)))
        assertTrue(runCatching { series.copy(seriesId="../other").validate() }.isFailure)
    }
    @Test fun strictDecoderRejectsWrongSeriesTypeAndInventedIdentity() {
        val json=OrezLibraryScopeCodec.encode(OrezLibraryScope.capture(search(),null,listOf(row()),false))
        assertTrue(runCatching { OrezLibraryScopeCodec.decode(JSONObject(json.toString()).put("series","ignored")) }.isFailure)
        assertTrue(runCatching { OrezLibraryScopeCodec.decode(JSONObject(json.toString()).put("identity","f".repeat(64))) }.isFailure)
    }
    @Test fun incompleteCoverageAndSourceTopologyJoinIdentity() {
        val full=OrezLibraryScope.capture(search(),null,listOf(row()),false)
        assertNotEquals(full,OrezLibraryScope.capture(search(),null,listOf(row()),true))
        assertNotEquals(full,OrezLibraryScope.capture(search(),null,listOf(row().copy(sourceTopologySha256="d".repeat(64))),false))
    }
}
