package com.mangalens

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.core.translation.TranslationQualityPolicy
import com.mangalens.core.translation.TranslationService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real installed ML Kit translator. Semantic/fluency review still uses the exported actual outputs. */
@RunWith(AndroidJUnit4::class)
class TranslationMeaningInstrumentedTest {
    @Test fun negativePossessionAndMoneyUseActualHindiAndRomanHindiModels() = coreScreenSmoke("translation-meaning") {
        val outputs = JSONArray()
        val service = TranslationService()
        var failure: Throwable? = null
        try {
            runBlocking { withTimeout(180_000) {
                for (target in listOf("hi", "hi-latn")) for (source in listOf(
                    "A student with no family or guardian.",
                    "Families who are short on money.",
                    "Hey, Velora! I couldn't find the 3 coins."
                )) {
                    val row = JSONObject().put("source", source).put("target", target); outputs.put(row)
                    val draft = service.translateDraft(source, target, "en")
                    row.put("output", draft.text).put("hindi_draft", draft.hindiDraft)
                    val selected = TranslationQualityPolicy.chooseDraft(source, draft, "", target)
                    assertTrue(TranslationQualityPolicy.isUsable(source, selected.text, target, selected.hindiDraft))
                    if (target == "hi-latn") {
                        assertNotNull(selected.hindiDraft)
                        assertTrue(selected.text.none { it in '\u0900'..'\u097f' })
                    } else assertTrue(selected.text.any { it in '\u0900'..'\u097f' })
                }
            } }
        } catch (problem: Throwable) { failure = problem; throw problem }
        finally {
            service.close()
            File(context.getExternalFilesDir(null), "qa/core-smoke/translation-meaning/outputs.json").apply { parentFile!!.mkdirs() }
                .writeText(JSONObject().put("source_sha", BuildConfig.SOURCE_SHA).put("status", if (failure == null) "passed" else "failed")
                    .put("failure", failure?.toString()).put("quality_assessment", "UNASSESSED: guards do not prove fluency or full meaning; inspect actual model output.")
                    .put("outputs", outputs).toString(2))
        }
    }
}
