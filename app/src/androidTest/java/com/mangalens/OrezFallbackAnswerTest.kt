package com.mangalens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.engine.LiveSearchAnswer
import com.mangalens.engine.OrezSearchResult
import com.mangalens.orez.OrezBrain
import com.mangalens.orez.OrezContext
import com.mangalens.orez.OrezRoomDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrezFallbackAnswerTest {
    @Test fun encyclopediaFallbackKeepsSourcesAndDoesNotClaimCurrentPrices() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://en.wikipedia.org/wiki/Gold"
        val brain = OrezBrain(OrezRoomDatabase.get(context), context) { query ->
            LiveSearchAnswer(query, listOf(OrezSearchResult("Gold", "Gold is a chemical element and a precious metal.", url)),
                "Wikipedia background excerpt", provider = "wikipedia")
        }
        try {
            val answer = brain.answer("Search online for today's gold price", OrezContext(emptyList()))
            assertTrue(answer.usedLiveSearch)
            assertFalse(answer.usedLocalKnowledge)
            assertEquals(listOf(url), answer.sources)
            assertTrue(answer.text.contains("Wikipedia encyclopedia"))
            assertTrue(answer.text.contains("cannot verify current news, prices or schedules"))
            assertTrue(answer.text.contains("Gold is a chemical element"))
        } finally { brain.close() }
    }
}
