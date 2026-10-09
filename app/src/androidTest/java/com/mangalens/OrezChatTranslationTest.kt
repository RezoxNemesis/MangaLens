package com.mangalens

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.TranslationDraft
import com.mangalens.core.translation.TranslationMemoryCodec
import com.mangalens.core.translation.TranslationQualityPolicy
import com.mangalens.orez.OrezBrain
import com.mangalens.orez.OrezContext
import com.mangalens.orez.OrezIntent
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.OrezTranslationEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual Brain request routing and Room translation memories, with no chapter or URL context. */
@RunWith(AndroidJUnit4::class)
class OrezChatTranslationTest {
    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val name = "orez-chat-translation-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, OrezRoomDatabase::class.java, name).build()
            private set
        var brain = OrezBrain(database, context) { error("Text translation must not dispatch live search") }
            private set
        suspend fun save(source: String, target: String, language: String) {
            database.datasets().upsertTranslation(OrezTranslationEntity(UUID.randomUUID().toString(), source, target, language))
        }
        fun reopen() {
            brain.close(); database.close()
            database = Room.databaseBuilder(context, OrezRoomDatabase::class.java, name).build()
            brain = OrezBrain(database, context) { error("Text translation must not dispatch live search") }
        }
        fun close() { brain.close(); database.close(); context.deleteDatabase(name) }
    }

    @Test fun explicitRomanAliasesAndHindiUseDistinctActualRoomMemories() = runBlocking {
        val fixture = Fixture()
        val source = "I am fine."
        val roman = "main theek hoon."
        val hindi = "मैं ठीक हूँ।"
        try {
            fixture.save(source, roman, "hi-latn")
            fixture.save(source, hindi, "hi")
            val context = OrezContext(emptyList(), targetLanguage = "hi")
            for (alias in listOf("Hinglish", "Roman Hindi", "Hindi Latin", "Romanized Hindi", "Romanised Hindi", "hi-latn")) {
                val result = withTimeout(15_000) { fixture.brain.answer("Translate \"$source\" into $alias", context) }
                assertEquals("Wrong routing for $alias", OrezIntent.TRANSLATION, result.intent)
                assertEquals("Wrong script or memory for $alias", roman, result.text)
                assertTrue(result.usedLocalKnowledge)
            }
            val prefix = fixture.brain.answer("Please translate into Roman Hindi: \"$source\"", context)
            assertEquals(roman, prefix.text)
            val nativeHindi = fixture.brain.answer("Translate \"$source\" into Hindi", context.copy(targetLanguage = "hi-latn"))
            assertEquals(hindi, nativeHindi.text)
            assertEquals(roman, fixture.database.datasets().exactTranslation(source, "hi-latn"))
            assertEquals(hindi, fixture.database.datasets().exactTranslation(source, "hi"))
        } finally { fixture.close() }
    }

    @Test fun wrongScriptCachedRomanAnswerFallsThroughToAUsableLatinAnswer() = runBlocking {
        val fixture = Fixture()
        // A Hindi source uses the real service's local Hindi-to-Roman branch without
        // fetching ML Kit weights. The optional local model must pass the same gate.
        val source = "मैं ठीक हूँ।"
        try {
            fixture.save(source, source, "hi-latn")
            val result = withTimeout(30_000) {
                fixture.brain.answer("Translate \"$source\" into Hinglish", OrezContext(emptyList(), targetLanguage = "hi"))
            }
            assertEquals(OrezIntent.TRANSLATION, result.intent)
            assertTrue(result.usedLocalKnowledge)
            assertNotEquals(source, result.text)
            assertTrue(result.text.filter(Char::isLetter).all { it.code in 0x0041..0x024F })
            assertTrue(TranslationQualityPolicy.isUsable(source, result.text, "hi-latn"))
        } finally { fixture.close() }
    }

    @Test fun checkedShortNounProofSurvivesRoomReopenAndActualChatRetrieval() = runBlocking {
        val fixture = Fixture()
        try {
            val source = "Fire!"
            val persisted = TranslationMemoryCodec.encode(source, TranslationDraft("aag!", "आग!"), "hi-latn")
            fixture.save(source, persisted, "hi-latn")
            fixture.reopen()
            val read = fixture.database.datasets().exactTranslation(source, "hi-latn")!!
            assertEquals("आग!", TranslationMemoryCodec.decode(source, read, "hi-latn")!!.hindiDraft)
            val result = withTimeout(15_000) {
                fixture.brain.answer("Translate \"$source\" into Roman Hindi", OrezContext(emptyList(), targetLanguage = "hi"))
            }
            assertEquals(OrezIntent.TRANSLATION, result.intent)
            assertEquals("aag!", result.text)
            assertTrue(result.usedLocalKnowledge)
            assertFalse(result.text.contains("hindiDraft"))
        } finally { fixture.close() }
    }
}
