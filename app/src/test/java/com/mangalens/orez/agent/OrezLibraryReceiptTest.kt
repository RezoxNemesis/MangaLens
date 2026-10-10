package com.mangalens.orez.agent
import com.mangalens.core.reader.NativeLibraryOperationReceipt
import com.mangalens.core.reader.NativeLibraryOperationReceiptCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Structural receipt controls only; actual native byte readback is the separate Android fixture. */
class OrezLibraryReceiptTest {
    private val id="a".repeat(32)
    private fun plan():OrezTaskPlan {
        val scope=OrezLibraryScope.capture(OrezLibraryRequest(OrezLibraryOperation.BOOKMARK,bookmarked=true),id,
            listOf(OrezLibraryManifestRevision(id,"b".repeat(64),100,"c".repeat(64))),false)
        return OrezAgentRuntime().decide("Bookmark this chapter",OrezAgentContext(activeChapterId=id,libraryScope=scope)).plan!!
    }
    private fun output(plan:OrezTaskPlan):Map<String,String> {
        val scope=plan.authorization!!.libraryScope!!;val request=OrezDurablePlanRules.requestId(plan.id,0)
        val row=JSONObject().put("chapterId",id).put("title","Title").put("series","").put("pageCount",2).put("position",0)
            .put("bookmarked",true).put("readingStatus","READING").put("addedAt",1).put("lastReadAt",2)
        val body=JSONObject().put("trust","APP_SAVED_METADATA").put("chapters",JSONArray(listOf(row))).put("seriesId",JSONObject.NULL)
            .put("seriesTitle",JSONObject.NULL).put("terms",JSONArray()).put("styleId",JSONObject.NULL).put("styleTarget",JSONObject.NULL).put("styleInstructions",JSONObject.NULL)
        val receipt=NativeLibraryOperationReceipt(request,scope.identity,"BOOKMARK",NativeLibraryOperationReceipt.valueDigest("BOOKMARK",id,"c".repeat(64),"true"),"d".repeat(64),3)
        return mapOf("requestId" to request,"scopeIdentity" to scope.identity,"operation" to "BOOKMARK","chapterId" to id,"seriesId" to "","associationRevision" to "",
            "metadata" to body.toString(),"incomplete" to "false","persisted" to "true","nativeReceipt" to NativeLibraryOperationReceiptCodec.encode(receipt).toString())
    }
    @Test fun receiptRequiresBoundNativeOwnerValueAndSavedMetadataKind() {
        val plan=plan();OrezDurablePlanRules.validateReceipt(plan,plan.steps.single(),output(plan),true)
        for(key in listOf("requestId","scopeIdentity","operation","chapterId","persisted")) {
            assertTrue(key,runCatching { OrezDurablePlanRules.validateReceipt(plan,plan.steps.single(),output(plan)+(key to "wrong"),true) }.isFailure)
        }
    }
    @Test fun navigationOrMissingNativeReceiptCannotCompleteAMutation() {
        val plan=plan();assertTrue(runCatching { OrezDurablePlanRules.validateReceipt(plan,plan.steps.single(),mapOf("destination" to "reader:$id"),true) }.isFailure)
        assertTrue(runCatching { OrezDurablePlanRules.validateReceipt(plan,plan.steps.single(),output(plan)+( "nativeReceipt" to ""),true) }.isFailure)
    }
    @Test fun emptyRowsOrWrongSavedBookmarkRejectPersistedClaim() {
        val plan=plan();val proof=output(plan);val body=JSONObject(proof.getValue("metadata"))
        val wrong=JSONObject(body.toString());wrong.getJSONArray("chapters").getJSONObject(0).put("bookmarked",false)
        assertTrue(runCatching { OrezDurablePlanRules.validateReceipt(plan,plan.steps.single(),proof+("metadata" to wrong.toString()),true) }.isFailure)
        body.put("chapters",JSONArray());assertTrue(runCatching { OrezDurablePlanRules.validateReceipt(plan,plan.steps.single(),proof+("metadata" to body.toString()),true) }.isFailure)
    }
    @Test fun changedNativeRequestOrValueHashRejectsOtherwiseCorrectMetadata() {
        val plan=plan();val proof=output(plan)
        for(key in listOf("requestId","valueSha256","scopeIdentity")) {
            val receipt=JSONObject(proof.getValue("nativeReceipt")).put(key,if(key=="requestId") "other-request" else "e".repeat(64))
            assertTrue(runCatching { OrezDurablePlanRules.validateReceipt(plan,plan.steps.single(),proof+("nativeReceipt" to receipt.toString()),true) }.isFailure)
        }
    }
}
