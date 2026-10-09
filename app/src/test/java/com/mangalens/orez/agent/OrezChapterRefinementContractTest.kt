package com.mangalens.orez.agent

import com.mangalens.core.translation.ChapterTranslationConfig
import com.mangalens.core.translation.TranslationRefinementRequest
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrezChapterRefinementContractTest {
    private val request = TranslationRefinementRequest(true,
        TranslationStyleProfile("custom", "Chosen register", "Keep respectful short dialogue.", false, false, false),
        OrezModelPin("chosen-local-model", "a".repeat(64), 2_000_000))
    private fun options() = OrezTranslationOptions("hi-latn", "custom", request.style.instruction, localRefinement = true, refinementRequest = request)
    private fun plan(options: OrezTranslationOptions = options()) = OrezAgentRuntime().decide("Translate this chapter into Hinglish",
        OrezAgentContext(hasActiveChapter = true, activeChapterId = "c".repeat(32), translationOptions = options)).plan!!
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }

    @Test fun actualNativeConfigMapperPreservesTheFullStyleFlagsAndModelIdentity() {
        val expected = ChapterTranslationConfig("hi-latn", "custom", request.style.instruction, localRefinement = true, refinementRequest = request).normalized()
        assertEquals(expected, options().nativeChapterConfig())
        assertEquals(options(), expected.orezChapterOptions())
    }

    @Test fun fullRequestAndPinSurviveAnOrezJournalReopenWithExplicitNewSchema() = runTest {
        val original = plan(); val dao = Journal(); val store = OrezTaskStore(dao)
        assertTrue(store.checkpoint(original))
        assertEquals(4, JSONObject(dao.row!!.planJson).getInt("schema"))
        val reopened = OrezTaskStore(dao).load(original.id)!!
        assertEquals(original.authorization, reopened.authorization)
        assertEquals(request, reopened.authorization!!.translation!!.nativeChapterConfig().refinementRequest)
    }

    @Test fun legacyEnabledNullRequestRetainsItsExactScopeRatherThanAnAmbientModel() = runTest {
        val original = plan(OrezTranslationOptions("hi", localRefinement = true))
        val dao = Journal(); val store = OrezTaskStore(dao); store.checkpoint(original)
        val legacy = JSONObject(dao.row!!.planJson).put("schema", 2)
        legacy.getJSONObject("authorization").getJSONObject("translation").remove("refinementRequest")
        val reopened = store.decode(legacy.toString())
        assertTrue(reopened.authorization!!.translation!!.localRefinement)
        assertNull(reopened.authorization!!.translation!!.refinementRequest)
        assertNull(reopened.authorization!!.translation!!.nativeChapterConfig().refinementRequest)
    }

    @Test fun changedCapturedModelOrStyleFlagsCannotBeDroppedDuringNativeMapping() {
        val baseline = options().nativeChapterConfig()
        val alteredPin = options().copy(refinementRequest = request.copy(pinnedModel = request.pinnedModel!!.copy(sha256 = "b".repeat(64))))
        val alteredStyle = options().copy(refinementRequest = request.copy(style = request.style.copy(preserveNames = true)))
        assertNotEquals(baseline, alteredPin.nativeChapterConfig())
        assertNotEquals(baseline, alteredStyle.nativeChapterConfig())
        assertEquals(alteredPin.refinementRequest, alteredPin.nativeChapterConfig().orezChapterOptions().refinementRequest)
        assertEquals(alteredStyle.refinementRequest, alteredStyle.nativeChapterConfig().orezChapterOptions().refinementRequest)
    }

    @Test fun enabledFlagOrCustomInstructionMismatchCannotBecomeNativeAuthority() {
        assertThrows(IllegalArgumentException::class.java) {
            options().copy(refinementRequest = request.copy(enabled = false, pinnedModel = null)).nativeChapterConfig()
        }
        assertThrows(IllegalArgumentException::class.java) {
            options().copy(refinementRequest = request.copy(style = request.style.copy(instruction = "A different custom request."))).nativeChapterConfig()
        }
    }

    @Test fun capturedSchemaCannotSilentlyLoseItsRefinementRequestOrModelField() = runTest {
        val original = plan(); val dao = Journal(); val store = OrezTaskStore(dao); store.checkpoint(original)
        val requestMissing = JSONObject(dao.row!!.planJson)
        requestMissing.getJSONObject("authorization").getJSONObject("translation").remove("refinementRequest")
        assertTrue(runCatching { store.decode(requestMissing.toString()) }.isFailure)
        val modelMissing = JSONObject(dao.row!!.planJson)
        modelMissing.getJSONObject("authorization").getJSONObject("translation").getJSONObject("refinementRequest").remove("model")
        assertTrue(runCatching { store.decode(modelMissing.toString()) }.isFailure)
    }

    @Test fun unavailableCapturedPinOrLegacyEnabledNullFailsBeforeAnyNativeDispatch() = runTest {
        for (options in listOf(options().copy(refinementRequest = request.copy(pinnedModel = null)),
            OrezTranslationOptions("hi-latn", localRefinement = true))) {
            val original = plan(options); val chapter = OrezChapterSnapshot("c".repeat(32), "Saved selected chapter", 1, "d".repeat(64))
            var starts = 0
            val host = object : OrezChapterHost {
                override suspend fun inspect(chapterId: String) = chapter
                override suspend fun start(chapter: OrezChapterSnapshot, options: OrezTranslationOptions, requestId: String, allowReplacement: Boolean): OrezChapterReceipt {
                    starts++; error("A missing captured model must never dispatch native work.")
                }
                override suspend fun observe(taskId: String): OrezChapterReceipt? = null
                override suspend fun findOwned(requestId: String): OrezChapterReceipt? = null
                override suspend fun pause(receipt: OrezChapterReceipt): OrezChapterReceipt? = null
                override suspend fun resume(receipt: OrezChapterReceipt): OrezChapterReceipt? = null
                override suspend fun cancel(receipt: OrezChapterReceipt): OrezChapterReceipt? = null
            }
            val resolved = original.steps[1].copy(call = original.steps[1].call.copy(arguments = mapOf(
                "chapterId" to chapter.chapterId, "sourceFingerprint" to chapter.sourceFingerprint, "targetLanguage" to options.targetLanguage)))
            val result = OrezChapterTools(host, original.authorization!!).execute(resolved, OrezDurablePlanRules.requestId(original.id, 1))
            assertTrue(result is OrezToolResult.Failed)
            assertTrue((result as OrezToolResult.Failed).reason.contains("new translation", ignoreCase = true))
            assertEquals(0, starts)
        }
    }
}
