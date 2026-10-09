package com.mangalens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.engine.LiveSearchAnswer
import com.mangalens.engine.OrezSearchResult
import com.mangalens.orez.OrezBrain
import com.mangalens.orez.OrezContext
import com.mangalens.orez.OrezEngineMode
import com.mangalens.orez.OrezRoomDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class OrezFallbackAnswerTest {
    @Test fun encyclopediaFallbackKeepsSourcesAndDoesNotClaimCurrentPrices() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("orez_engine", android.content.Context.MODE_PRIVATE)
        val oldMode = preferences.getString("mode", null)
        assertTrue(preferences.edit().putString("mode", OrezEngineMode.HYBRID_AUTO.name).commit())
        val url = "https://en.wikipedia.org/wiki/Gold"
        var calls = 0
        val brain = OrezBrain(OrezRoomDatabase.get(context), context) { query ->
            calls++
            LiveSearchAnswer(query, listOf(OrezSearchResult("Gold", "Gold is a chemical element and a precious metal.", url)),
                "Wikipedia background excerpt", provider = "wikipedia")
        }
        try {
            val answer = brain.answer("Search online for today's gold price", OrezContext(emptyList()))
            File(context.getExternalFilesDir(null) ?: context.filesDir, "qa/core-smoke/orez-fallback-answer/result.json").apply {
                parentFile!!.mkdirs()
                writeText(JSONObject().put("evidence_kind", "controlled encyclopedia-provider fixture; no public provider request")
                    .put("provider_calls", calls).put("text", answer.text)
                    .put("used_live_search", answer.usedLiveSearch).put("used_local_knowledge", answer.usedLocalKnowledge)
                    .put("sources", org.json.JSONArray(answer.sources)).toString(2))
            }
            assertEquals(1, calls)
            assertTrue(answer.usedLiveSearch)
            assertFalse(answer.usedLocalKnowledge)
            assertEquals(listOf(url), answer.sources)
            assertTrue(answer.text.contains("Wikipedia encyclopedia"))
            assertTrue(answer.text.contains("cannot verify current news, prices or schedules"))
            assertTrue(answer.text.contains("Gold is a chemical element"))
        } finally {
            brain.close()
            assertTrue(preferences.edit().apply {
                if (oldMode == null) remove("mode") else putString("mode", oldMode)
            }.commit())
        }
    }
}
