package com.mangalens.orez.agent

import com.mangalens.core.translation.ChapterTranslationConfig
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. Captured options are scalar authority, not proof that reconstruction executed. */
class OrezReconstructionCaptureTest {
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
    private fun plan(options: OrezTranslationOptions) = OrezAgentRuntime().decide("Translate this chapter into Hindi",
        OrezAgentContext(hasActiveChapter = true, activeChapterId = "c".repeat(32), translationOptions = options)).plan!!
    @Test fun oldUnversionedOptionsRetainLegacyNativeIdentity() { assertEquals(1, OrezTranslationOptions().normalized().nativeChapterConfig().reconstructionVersion) }
    @Test fun onlyExplicitNewRequestCaptureChoosesTwo() { val old = OrezTranslationOptions(); val fresh = old.captureForNewRequest(); assertEquals(1, old.reconstructionVersion); assertEquals(2, fresh.reconstructionVersion); assertEquals(2, fresh.nativeChapterConfig().reconstructionVersion) }
    @Test fun normalizedReplayDoesNotUpgradeCapturedOne() { assertEquals(OrezTranslationOptions(), OrezTranslationOptions().normalized()) }
    @Test fun nativeToDurableOptionsRetainsTheActualVersionInsteadOfRecapturingIt() { for (v in 1..2) { val config = ChapterTranslationConfig("hi", reconstructionVersion = v); assertEquals(config, config.orezChapterOptions().nativeChapterConfig()) } }
    @Test fun unknownVersionCannotBorrowSupportedTwo() { for (v in listOf(0, 3, Int.MAX_VALUE)) try { OrezTranslationOptions(reconstructionVersion = v).normalized(); fail() } catch (_: IllegalArgumentException) { } }
    @Test fun newCapturePreservesExactOtherOptions() { val old = OrezTranslationOptions("ja", "faithful", "", "JAPANESE", false, false); assertEquals(old.copy(reconstructionVersion = 2), old.captureForNewRequest()) }
    @Test fun realLegacyJournalOmitsTheFieldAndColdReplayRetainsOne() = runTest {
        val original = plan(OrezTranslationOptions()); val journal = Journal()
        assertTrue(OrezTaskStore(journal).checkpoint(original))
        assertFalse(JSONObject(journal.row!!.planJson).getJSONObject("authorization").getJSONObject("translation").has("reconstructionVersion"))
        val reopened = OrezTaskStore(journal).load(original.id)!!
        assertEquals(original.authorization, reopened.authorization)
        assertEquals(1, reopened.authorization!!.translation!!.nativeChapterConfig().reconstructionVersion)
    }
    @Test fun realFreshJournalPersistsTwoAndColdReplayNeverRecapturesOptions() = runTest {
        val original = plan(OrezTranslationOptions().captureForNewRequest()); val journal = Journal()
        assertTrue(OrezTaskStore(journal).checkpoint(original))
        assertEquals(2, JSONObject(journal.row!!.planJson).getJSONObject("authorization").getJSONObject("translation").getInt("reconstructionVersion"))
        val reopened = OrezTaskStore(journal).load(original.id)!!
        assertEquals(original.authorization, reopened.authorization)
        assertEquals(2, reopened.authorization!!.translation!!.nativeChapterConfig().reconstructionVersion)
    }
    @Test fun actualJournalDecoderRejectsAnUnsupportedCapturedVersion() = runTest {
        val original = plan(OrezTranslationOptions().captureForNewRequest()); val journal = Journal(); val store = OrezTaskStore(journal)
        assertTrue(store.checkpoint(original))
        val changed = JSONObject(journal.row!!.planJson)
        changed.getJSONObject("authorization").getJSONObject("translation").put("reconstructionVersion", 77)
        assertTrue(runCatching { store.decode(changed.toString()) }.isFailure)
        assertEquals(original.authorization, OrezTaskStore(journal).load(original.id)!!.authorization)
    }
}
