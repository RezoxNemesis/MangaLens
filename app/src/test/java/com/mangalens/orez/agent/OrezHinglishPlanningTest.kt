package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrezHinglishPlanningTest {
    private val chapter = "a".repeat(32)
    private val source = "b".repeat(64)
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
    private fun decide(prompt: String, preference: String = "hi") = OrezAgentRuntime().decide(prompt,
        OrezAgentContext(hasActiveChapter = true, activeChapterId = chapter,
            translationOptions = OrezTranslationOptions(targetLanguage = preference)))

    @Test fun explicitRomanHindiAliasesCaptureDistinctFullScriptTarget() {
        for (alias in listOf("Hinglish", "Roman Hindi", "Hindi Latin", "Romanized Hindi", "Romanised Hindi", "hi-latn")) {
            val result = decide("Translate this chapter into $alias")
            assertFalse(result.continueToBrain)
            assertEquals("Wrong target for $alias", "hi-latn", result.plan!!.authorization!!.translation!!.targetLanguage)
            assertEquals("hi-latn", result.plan!!.steps[1].call.arguments["targetLanguage"])
            assertEquals(OrezTaskStatus.RUNNING, result.plan!!.status)
        }
    }

    @Test fun hindiRemainsDevanagariAndAnExplicitTargetOverridesHinglishPreference() {
        assertEquals("hi", decide("Translate this chapter into Hindi").plan!!.authorization!!.translation!!.targetLanguage)
        assertEquals("hi-latn", decide("Translate this chapter", "hi-latn").plan!!.authorization!!.translation!!.targetLanguage)
        assertEquals("en", decide("Translate this chapter into English", "hi-latn").plan!!.authorization!!.translation!!.targetLanguage)
    }

    @Test fun restoredHinglishReceiptCannotBeSatisfiedByAnotherScriptTarget() = runTest {
        val journal = Journal(); val plan = decide("Translate this chapter into Hinglish").plan!!
        var store = OrezTaskStore(journal); store.checkpoint(plan)
        store = OrezTaskStore(journal)
        assertEquals("hi-latn", store.load(plan.id)!!.authorization!!.translation!!.targetLanguage)
        val result = OrezTaskExecutor(store, OrezDurableTools { step, requestId ->
            val inspected = mapOf("requestId" to requestId, "chapterId" to chapter, "sourceFingerprint" to source,
                "pageCount" to "2", "title" to "Scoped chapter")
            OrezToolResult.Completed(if (step.index == 0) inspected else inspected + mapOf(
                "translationTaskId" to "c".repeat(32), "generation" to "d".repeat(32), "targetLanguage" to "hi",
                "status" to "COMPLETED", "completedPages" to "2", "destination" to "chapter-translation:${"c".repeat(32)}"))
        }).run(plan.id)
        assertTrue(result is OrezTaskExecutor.Result.Failed)
        assertEquals(OrezStepStatus.COMPLETED, store.load(plan.id)!!.steps[0].status)
        assertEquals(OrezStepStatus.FAILED, store.load(plan.id)!!.steps[1].status)
    }
}
