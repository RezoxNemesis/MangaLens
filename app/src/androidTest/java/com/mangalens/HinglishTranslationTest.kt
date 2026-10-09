package com.mangalens

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.core.translation.TranslationQualityPolicy
import com.mangalens.core.translation.TranslationService
import com.mangalens.core.translation.TranslationMemoryCodec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real ML Kit model download/translation. Recorded outputs still require naturalness review. */
@RunWith(AndroidJUnit4::class)
class HinglishTranslationTest {
    @Test fun englishUsesTheHindiModelBeforeRomanOutputAndKeepsTheName() = coreScreenSmoke("hinglish-mlkit") {
        val service = TranslationService()
        val record = JSONObject().apply {
            put("source_sha", BuildConfig.SOURCE_SHA)
            put("model_target", "hi")
            put("output_target", "hi-latn")
            put("assessment", "Functional model/script/flow checks; dialogue naturalness requires inspection of these outputs and supplied fixtures.")
        }
        val started = SystemClock.elapsedRealtime()
        try {
            runBlocking {
                withTimeout(240_000L) {
                    val english = "Do not worry, Jin. I am going home with you!"
                    record.put("source", english)
                    val rawHindi = service.translate(english, "hi", "en")
                    record.put("hindi_draft", rawHindi)
                    assertTrue("Real ML Kit draft did not produce Hindi: $rawHindi", rawHindi.any { it in '\u0900'..'\u097f' })
                    val hindi = TranslationQualityPolicy.choose(english, rawHindi, "", "hi")
                    record.put("accepted_hindi", hindi)

                    val viaHindi = service.translate(hindi, "hi-latn", "hi")
                    record.put("hindi_to_roman", viaHindi)
                    assertReadableRoman(hindi, viaHindi)

                    val directRoman = service.translate(english, "hi-latn", "en")
                    record.put("english_to_roman", directRoman)
                    assertReadableRoman(english, directRoman)
                    assertTrue("Source name spelling was lost: $directRoman", Regex("\\bJin\\b").containsMatchIn(directRoman))
                    assertTrue("Source shouting mark was lost: $directRoman", directRoman.endsWith("!"))
                    assertFalse("Roman target returned the English source", directRoman.equals(english, ignoreCase = true))

                    val shortNouns = JSONArray()
                    record.put("short_noun_cases", shortNouns)
                    for (source in listOf("Fire!", "Power!", "Sword!")) {
                        val draft = service.translateDraft(source, "hi-latn", "en")
                        shortNouns.put(JSONObject().put("source", source).put("hindi_draft", draft.hindiDraft).put("roman", draft.text))
                        assertNotNull("The real Hindi intermediate was lost for $source", draft.hindiDraft)
                        assertReadableRoman(source, draft.text, draft.hindiDraft)
                        val chosen = TranslationQualityPolicy.chooseDraft(source, draft, "I was beaten up.", "hi-latn")
                        assertEquals(draft, chosen)
                        assertEquals(chosen, TranslationMemoryCodec.decode(source,
                            TranslationMemoryCodec.encode(source, chosen, "hi-latn"), "hi-latn"))
                    }

                    // Reusing the same hi model client must not make ordinary Hindi
                    // return the rendered Latin output from the preceding call.
                    val ordinaryHindi = service.translate("I am fine.", "hi", "en")
                    record.put("ordinary_hindi_after_roman", ordinaryHindi)
                    assertTrue("Hindi and Roman target outputs were mixed", ordinaryHindi.any { it in '\u0900'..'\u097f' })
                }
            }
            record.put("status", "passed")
        } catch (failure: Throwable) {
            record.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            service.close()
            record.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(context.getExternalFilesDir(null), "qa/core-smoke/hinglish-mlkit/outputs.json")
                .apply { parentFile!!.mkdirs() }.writeText(record.toString(2))
        }
    }

    @Test fun hindiAndAlreadyRomanSourcesKeepTheNameAndRequestedScript() = coreScreenSmoke("hinglish-sources") {
        val service = TranslationService()
        val record = JSONObject().put("source_sha", BuildConfig.SOURCE_SHA).put("output_target", "hi-latn")
        try {
            runBlocking {
                withTimeout(30_000L) {
                    val source = "मैं Jin के साथ घर जा रहा हूँ!"
                    record.put("hindi_source", source)
                    val roman = service.translate(source, "hi-latn", "hi")
                    record.put("roman_output", roman)
                    assertEquals("main Jin ke saath ghar ja raha hoon!", roman)
                    assertReadableRoman(source, roman)
                    val alreadyRoman = "Tum ghar kab aaoge, Jin?"
                    record.put("already_roman_source", alreadyRoman)
                    val retained = service.translate(alreadyRoman, "hi-latn", "hi")
                    record.put("already_roman_output", retained)
                    assertEquals(alreadyRoman, retained)
                    assertReadableRoman(alreadyRoman, retained)
                }
            }
            record.put("status", "passed")
        } catch (failure: Throwable) {
            record.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            service.close()
            File(context.getExternalFilesDir(null), "qa/core-smoke/hinglish-sources/outputs.json")
                .apply { parentFile!!.mkdirs() }.writeText(record.toString(2))
        }
    }

    private fun assertReadableRoman(source: String, candidate: String, hindiDraft: String? = null) {
        assertTrue("Roman result was empty", candidate.isNotBlank())
        assertFalse("Roman target retained Devanagari: $candidate", candidate.any { it in '\u0900'..'\u097f' })
        assertTrue("Roman target used another script: $candidate", candidate.filter(Char::isLetter).all { it.code in 0x0041..0x024F })
        assertTrue("Roman result failed target quality checks: $candidate", TranslationQualityPolicy.isUsable(source, candidate, "hi-latn", hindiDraft))
    }
}
