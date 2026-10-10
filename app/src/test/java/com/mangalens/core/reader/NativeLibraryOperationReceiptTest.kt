package com.mangalens.core.reader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeLibraryOperationReceiptTest {
    @Test fun semanticStateIdentityDoesNotDependOnJsonObjectKeyOrder() {
        assertEquals(NativeLibraryOperationReceipt.stateDigest(JSONObject("{\"bookmarked\":true,\"position\":1}")),
            NativeLibraryOperationReceipt.stateDigest(JSONObject("{\"position\":1,\"bookmarked\":true}")))
    }
    @Test fun laterBookmarkAndUnrelatedMetadataEditsRetirePriorPostState() {
        val body=JSONObject().put("bookmarked",true).put("notes","old");val digest=NativeLibraryOperationReceipt.stateDigest(body)
        assertNotEquals(digest,NativeLibraryOperationReceipt.stateDigest(JSONObject(body.toString()).put("notes","new")))
        assertNotEquals(digest,NativeLibraryOperationReceipt.stateDigest(JSONObject(body.toString()).put("bookmarked",false)))
    }
    @Test fun receiptRoundTripKeepsExactRequestScopeValueAndPostState() {
        val receipt=NativeLibraryOperationReceipt("orez-task-step0","a".repeat(64),"BOOKMARK","b".repeat(64),"c".repeat(64),4)
        assertEquals(receipt,NativeLibraryOperationReceiptCodec.decode(NativeLibraryOperationReceiptCodec.encode(receipt)))
        assertTrue(runCatching { NativeLibraryOperationReceiptCodec.decode(NativeLibraryOperationReceiptCodec.encode(receipt).put("callback","reader://open")) }.isFailure)
    }
}
