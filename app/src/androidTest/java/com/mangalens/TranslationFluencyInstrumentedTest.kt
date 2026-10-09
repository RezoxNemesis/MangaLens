package com.mangalens

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.core.translation.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real provider outputs remain unassessed until source-aware bilingual inspection. */
@RunWith(AndroidJUnit4::class)
class TranslationFluencyInstrumentedTest {
    @Test fun actualModelsKeepPluralInabilityMeaningAndHindiProof() = coreScreenSmoke("translation-fluency") {
        val service = TranslationService()
        val rows = JSONArray()
        var failure: Throwable? = null
        try {
            runBlocking { withTimeout(180_000) {
                for (target in listOf("hi", "hi-latn")) for (source in listOf(
                    "Hey, Velora! I couldn't find the 3 coins.",
                    "Hello, Mareth! I couldn't find the 2 books.",
                    "We could not find the 4 tickets.",
                    "I couldn't find the 1 coin."
                )) {
                    val row = JSONObject().put("source", source).put("target", target)
                    rows.put(row)
                    val draft = service.translateDraft(source, target, "en")
                    row.put("draft", draft.text).put("hindi_draft", draft.hindiDraft)
                    val selected = TranslationQualityPolicy.chooseDraft(source, draft, "", target, TranslationStyleProfile.NATURAL)
                    row.put("selected", selected.text).put("saved_hindi_draft", selected.hindiDraft)
                    assertTrue("Rejected actual output for $source: ${selected.text}",
                        TranslationQualityPolicy.isUsable(source, selected.text, target, selected.hindiDraft))
                    if (target == "hi-latn") {
                        assertNotNull(selected.hindiDraft)
                        assertEquals(selected, TranslationMemoryCodec.decode(source,
                            TranslationMemoryCodec.encode(source, selected, target), target))
                    } else assertTrue(selected.text.any { it in '\u0900'..'\u097f' })
                }
            } }
        } catch (problem: Throwable) { failure = problem; throw problem }
        finally {
            service.close()
            File(context.getExternalFilesDir(null), "qa/core-smoke/translation-fluency/outputs.json").apply { parentFile!!.mkdirs() }
                .writeText(JSONObject().put("source_sha", BuildConfig.SOURCE_SHA).put("status", if (failure == null) "passed" else "failed")
                    .put("failure", failure?.toString()).put("quality_assessment", "UNASSESSED: a narrow grammar/meaning guard and proof roundtrip do not certify fluent or excellent localization.")
                    .put("outputs", rows).toString(2))
        }
    }

    @Test fun capturedFormalCasualAndCustomHindiStylesRequireAnActualSelectedModelReceipt() = coreScreenSmoke("translation-fluency-styles") {
        val service = TranslationService()
        val refiner = TranslationOrezRefiner(context)
        val rows = JSONArray()
        var failure: Throwable? = null
        try {
            runBlocking { withTimeout(180_000) {
                val captured = refiner.captureRequest(true, TranslationStyleProfile.NATURAL)
                assertNotNull("Install and verify the acceptance pack before the explicit style corpus.", captured.pinnedModel)
                val errors = mutableListOf<String>()
                for (style in listOf(TranslationStyleProfile.FORMAL, TranslationStyleProfile.CASUAL,
                    TranslationStyleProfile.custom("Use respectful, concise Hindi dialogue. Keep names, numbers and negation exact."))) {
                    val request = captured.copy(style = style)
                    val source = if (style == TranslationStyleProfile.CASUAL)
                        "Hey, Velora! I couldn't find the 3 coins."
                    else "Sir, I could not find the 2 keys."
                    val row = JSONObject().put("source", source).put("style", style.id).put("instruction", style.instruction)
                        .put("captured_request", TranslationRefinementRequestCodec.identity(request))
                    rows.put(row)
                    try {
                        val draft = service.translateDraft(source, "hi", "en")
                        row.put("hindi_draft", draft.text)
                        val result = refiner.refineCaptured(source, draft.text, "hi", request)
                        row.put("refinement_status", result.status.name).put("generated_candidate", result.text)
                        result.receipt?.let { receipt -> row.put("attempt_receipt", receiptJson(receipt)) }
                        assertTrue("Explicit style generation unavailable: ${result.status}",
                            TranslationRefinementPolicy.matches(result, source, draft.text, "hi", request))
                        val selectedHindi = TranslationQualityPolicy.chooseDraft(source, draft, result.text, "hi", style)
                        row.put("selected_hindi", selectedHindi.text)
                        assertEquals("A fallback draft cannot claim this captured style was produced.", result.text, selectedHindi.text)
                        assertTrue(TranslationQualityPolicy.isUsable(source, selectedHindi.text, "hi"))
                        val roman = HinglishTranslationOutput.fromHindiDraft(source, selectedHindi.text, style)
                        val selectedRoman = TranslationQualityPolicy.chooseDraft(source, roman, "", "hi-latn", style)
                        assertEquals(selectedHindi.text, selectedRoman.hindiDraft)
                        assertTrue(TranslationQualityPolicy.isUsable(source, selectedRoman.text, "hi-latn", selectedRoman.hindiDraft))
                        row.put("selected_roman", selectedRoman.text).put("saved_hindi_draft", selectedRoman.hindiDraft)
                            .put("selected_refinement_receipt", receiptJson(requireNotNull(result.receipt)))
                            .put("status", "passed observable meaning, grammar and provenance checks; manual style review required")
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (problem: Throwable) {
                        row.put("status", "rejected").put("failure", problem.toString())
                        errors += "${style.id}: ${problem.message}"
                    }
                }
                assertTrue("Actual style corpus remains incomplete: ${errors.joinToString("; ")}", errors.isEmpty())
            } }
        } catch (problem: Throwable) { failure = problem; throw problem }
        finally {
            service.close(); refiner.close()
            File(context.getExternalFilesDir(null), "qa/core-smoke/translation-fluency-styles/outputs.json").apply { parentFile!!.mkdirs() }
                .writeText(JSONObject().put("source_sha", BuildConfig.SOURCE_SHA).put("status", if (failure == null) "passed" else "failed")
                    .put("failure", failure?.toString()).put("quality_assessment", "UNASSESSED: exact selected-model receipts and language/meaning/grammar guards do not prove that actual formal/casual/custom style is excellent.")
                    .put("outputs", rows).toString(2))
        }
    }

    private fun receiptJson(receipt: TranslationRefinementReceipt) = JSONObject()
        .put("model_id", receipt.model.modelId).put("model_sha256", receipt.model.sha256).put("model_bytes", receipt.model.bytes)
        .put("prompt_sha256", receipt.promptSha256).put("output_sha256", receipt.outputSha256)
}
