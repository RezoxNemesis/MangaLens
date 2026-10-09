package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test

class BrowserFileUploadPolicyTest {
    private val scope = BrowserUploadScope("0123456789abcdef0123456789abcdef", 4, "https://example.com/upload")
    private val good = BrowserUploadSelection("content://documents/user-chosen-image", "image/png", "chosen.png", true)

    @Test fun explicitReadableMatchingDocumentIsReturnedOnceToItsOriginatingRequest() {
        val gate = BrowserUploadGate(); val results = mutableListOf<List<String>?>()
        val request = gate.begin(scope, listOf("image/*"), false, results::add)
        assertNotNull("A site file input must receive an owned chooser request", request)
        gate.finish(request!!.token, scope, listOf(good))
        gate.finish(request.token, scope, listOf(good))
        assertEquals(listOf(listOf(good.uri)), results)
    }

    @Test fun navigationOrTabReplacementReceivesNullInsteadOfOldSelectedAuthority() {
        for (replacement in listOf(scope.copy(tabId = "abcdef0123456789abcdef0123456789"), scope.copy(navigationEpoch = 5), scope.copy(pageUrl = "https://example.com/new"))) {
            val gate = BrowserUploadGate(); val results = mutableListOf<List<String>?>()
            val request = gate.begin(scope, listOf("*/*"), true, results::add)
            assertNotNull(request)
            gate.finish(request!!.token, replacement, listOf(good))
            assertEquals(listOf<List<String>?>(null), results)
        }
    }

    @Test fun aSecondChooserCannotStealTheFirstOutstandingPlatformResult() {
        val gate = BrowserUploadGate(); val first = mutableListOf<List<String>?>(); val second = mutableListOf<List<String>?>()
        val a = gate.begin(scope, emptyList(), false, first::add)
        assertNotNull(a)
        assertNull(gate.begin(scope.copy(navigationEpoch = 5), emptyList(), false, second::add))
        assertEquals(listOf<List<String>?>(null), second)
        gate.finish(a!!.token, scope, listOf(good))
        assertEquals(listOf(listOf(good.uri)), first)
        assertNotNull(gate.begin(scope, emptyList(), false) {})
    }

    @Test fun cancellationReturnsNullOnceButKeepsTheOutstandingResultUnassignable() {
        val gate = BrowserUploadGate(); val results = mutableListOf<List<String>?>()
        val request = gate.begin(scope, emptyList(), false, results::add)
        assertNotNull(request)
        gate.cancel(); gate.cancel()
        assertEquals(listOf<List<String>?>(null), results)
        assertNull(gate.begin(scope, emptyList(), false) {})
        gate.finish(request!!.token, scope, listOf(good))
        assertEquals(1, results.size)
        assertNotNull(gate.begin(scope, emptyList(), false) {})
    }

    @Test fun fileUrisUnsafeAuthoritiesUnreadableAndWrongTypesReturnNull() {
        val unsafe = listOf(
            good.copy(uri = "file:///data/private/secret"), good.copy(uri = "https://example.com/prompt-selected-file"),
            good.copy(uri = "content://user:password@documents/a"), good.copy(uri = "content:///a"),
            good.copy(readable = false), good.copy(mimeType = "application/pdf"), good.copy(uri = "content://documents/a\n")
        )
        unsafe.forEach { selected ->
            val gate = BrowserUploadGate(); val results = mutableListOf<List<String>?>()
            val request = gate.begin(scope, listOf("image/*"), false, results::add)
            assertNotNull(request)
            gate.finish(request!!.token, scope, listOf(selected))
            assertEquals(listOf<List<String>?>(null), results)
        }
    }

    @Test fun multipleSelectionHonorsCapturedModeAndDeduplicatesActualUris() {
        val gate = BrowserUploadGate(); val results = mutableListOf<List<String>?>()
        val request = gate.begin(scope, listOf("image/*"), true, results::add)
        assertNotNull(request)
        val other = good.copy(uri = "content://documents/second")
        gate.finish(request!!.token, scope, listOf(good, other, good))
        assertEquals(listOf(listOf(good.uri, other.uri)), results)
        val single = gate.begin(scope, listOf("image/*"), false, results::add)!!
        gate.finish(single.token, scope, listOf(good, other))
        assertNull(results.last())
    }

    @Test fun acceptsAreNormalizedBoundedAndUnknownExtensionsRemainExplicit() {
        val types = browserUploadTypes(listOf(" IMAGE/PNG, .CBZ ", "image/*", "image/png"))
        assertEquals(listOf("image/png", "image/*"), types.mimeTypes)
        assertEquals(listOf(".cbz"), types.extensions)
        assertEquals(listOf("*/*"), browserUploadTypes(emptyList()).mimeTypes)
        val gate = BrowserUploadGate(); val results = mutableListOf<List<String>?>()
        val request = gate.begin(scope, listOf(".cbz"), false, results::add)
        assertNotNull(request)
        gate.finish(request!!.token, scope, listOf(good.copy(mimeType = "application/octet-stream", displayName = "User Comic.CBZ")))
        assertEquals(listOf(listOf(good.uri)), results)
    }

    @Test fun lateResultWithAnotherTokenCannotConsumeTheCurrentRequest() {
        val gate = BrowserUploadGate(); val results = mutableListOf<List<String>?>()
        val request = gate.begin(scope, emptyList(), false, results::add)
        assertNotNull(request)
        gate.finish("abcdef0123456789abcdef0123456789", scope, listOf(good))
        assertTrue(results.isEmpty())
        gate.finish(request!!.token, scope, null)
        assertEquals(listOf<List<String>?>(null), results)
    }

    @Test fun legalAndroidProviderAuthorityWithAnUnderscoreIsNotTreatedAsAnHttpHost() {
        val gate = BrowserUploadGate(); val results = mutableListOf<List<String>?>()
        val request = gate.begin(scope, emptyList(), false, results::add)!!
        val chosen = good.copy(uri = "content://my_app.documents/explicit-file")
        gate.finish(request.token, scope, listOf(chosen))
        assertEquals(listOf(listOf(chosen.uri)), results)
    }

    @Test fun readableButWrongAcceptTypeProvidesOwnedRejectionFeedback() {
        val gate = BrowserUploadGate(); val results = mutableListOf<List<String>?>()
        val request = gate.begin(scope, listOf("text/plain"), false, results::add)!!
        assertEquals(BrowserUploadFinish.REJECTED, gate.finish(request.token, scope, listOf(good)))
        assertEquals(listOf<List<String>?>(null), results)
    }

    @Test fun anOldNavigationCannotShowRejectionFeedbackOnTheReplacementPage() {
        val gate = BrowserUploadGate(); val results = mutableListOf<List<String>?>()
        val request = gate.begin(scope, listOf("text/plain"), false, results::add)!!
        assertEquals(BrowserUploadFinish.STALE, gate.finish(request.token, scope.copy(navigationEpoch = 5), listOf(good)))
        assertEquals(listOf<List<String>?>(null), results)
    }

    @Test fun acceptedResultFeedbackCannotBeRepeatedOrReviveACancelledRequest() {
        val gate = BrowserUploadGate()
        val request = gate.begin(scope, listOf("image/*"), false) {}!!
        assertEquals(BrowserUploadFinish.ACCEPTED, gate.finish(request.token, scope, listOf(good)))
        assertEquals(BrowserUploadFinish.IGNORED, gate.finish(request.token, scope, listOf(good)))
        val cancelled = gate.begin(scope, listOf("image/*"), false) {}!!
        gate.cancel()
        assertEquals(BrowserUploadFinish.IGNORED, gate.finish(cancelled.token, scope, listOf(good)))
    }
}
