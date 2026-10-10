package com.mangalens

import android.content.Context
import android.os.SystemClock
import com.mangalens.core.translation.*
import com.mangalens.orez.OrezGenerationCompletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Uses the production retained Task, captured localizer and Hindi-to-Roman paths.
 * No illustrative reference or semantic requirement is used as generation input or an oracle.
 */
internal object TranslationQualityCorpusConsumer {
    private const val CORPUS_SHA256 = "96248e9ddcb40393439e76478e8d0ca189d47dee986efadd00d5e337c7b64f58"
    private const val MAX_CORPUS_BYTES = 65_536
    private const val DEADLINE_MS = 180_000L

    fun collect(context: Context, testContext: Context, caseId: String, mode: String) {
        require(caseId.matches(Regex("[a-z][a-z0-9_]{1,63}")))
        require(mode in setOf("draft_only", "captured"))
        val item = loadCase(testContext, caseId)
        val source = item.getString("source")
        val chapterContext = item.getString("context")
        val glossaryJson = item.getJSONObject("glossary")
        val glossary = glossaryJson.keys().asSequence().associateWith { glossaryJson.getString(it) }.toMap()
        val style = TranslationStyleProfile.NATURAL
        val started = SystemClock.elapsedRealtime()
        val report = JSONObject().put("source_sha", BuildConfig.SOURCE_SHA)
            .put("corpus_sha256", CORPUS_SHA256).put("case_id", caseId).put("mode", mode)
            .put("scope", "one explicit developer-authored source; Hindi and actual paired Roman output")
            .put("source", source).put("source_language", "en").put("source_text_sha256", hash(source))
            .put("captured_context", chapterContext).put("context_sha256", hash(chapterContext))
            .put("captured_glossary", JSONObject(glossary)).put("style_id", style.id).put("style_instruction", style.instruction)
            .put("deadline_ms", DEADLINE_MS).put("native_cleanup", "Retained Task return precedes ephemeral translator close; the existing native model close owns its work.")
            .put("draft_provider", JSONObject().put("api", "ML Kit translation")
                .put("declared_library_at_harness_authoring", "com.google.mlkit:translate:17.0.3")
                .put("provider_model_identity", "UNEXPOSED_BY_PROVIDER_API")
                .put("language_pair", "en->hi").put("scene_context_supplied", false)
                .put("possible_inputs_from_existing_policy", JSONArray(EnglishHindiTranslationInputs.candidates(source)))
                .put("individual_provider_attempts", "NOT_EXPOSED_BY_CURRENT_SERVICE; possible inputs are not executed-attempt evidence"))
            .put("human_review_only", JSONObject().put("provenance", item.getString("provenance"))
                .put("illustrative_references", item.getJSONObject("references"))
                .put("semantic_requirements", item.getJSONArray("semantic_requirements"))
                .put("reference_exact_match_used", false).put("references_supplied_to_models", false)
                .put("meaning", "UNASSESSED").put("naturalness", "UNASSESSED")
                .put("register", "UNASSESSED").put("hi_latn_accuracy", "UNASSESSED_INDEPENDENT_OF_HINDI")
                .put("hi_latn_spelling_readability", "UNASSESSED; legitimate Roman spellings are not reference exact-match errors"))
        val hindi = JSONObject().put("target", "hi").put("structural_assessment", "NOT_REACHED")
            .put("meaning_assessment", "UNASSESSED").put("naturalness_assessment", "UNASSESSED")
        val roman = JSONObject().put("target", "hi-latn")
            .put("provider_path", "Production romanization of this actual independently accepted Hindi candidate")
            .put("structural_assessment", "NOT_REACHED").put("meaning_assessment", "UNASSESSED")
            .put("naturalness_assessment", "UNASSESSED").put("spelling_readability_assessment", "UNASSESSED")
            .put("reference_exact_match_used", false)
        val outputs = JSONArray().put(hindi).put(roman)
        report.put("outputs", outputs)
        var failure: Throwable? = null
        val service = TranslationService()
        val refiner = TranslationOrezRefiner(context)
        try {
            runBlocking(Dispatchers.IO) { withTimeout(DEADLINE_MS) {
                val draft = service.translateDraftRetainingNative(source, "hi", "en")
                hindi.put("raw_draft", draft.text).put("raw_draft_sha256", hash(draft.text))
                    .put("raw_hindi_proof", draft.hindiDraft ?: JSONObject.NULL).put("structural_assessment", "UNASSESSED")
                var generated: TranslationRefinementResult? = null
                var completionMatches = false
                if (mode == "captured") {
                    val request = refiner.captureRequest(true, style)
                    hindi.put("captured_request", TranslationRefinementRequestCodec.identity(request))
                        .put("captured_input_profile", request.inputProfileRevision ?: JSONObject.NULL)
                    request.pinnedModel?.let { hindi.put("captured_model", modelJson(it)) }
                    check(request.pinnedModel != null) { "No already installed compatible localization model was captured; no replacement was selected." }
                    val result = refiner.refineCaptured(source, draft.text, "hi", request, chapterContext, glossary)
                    generated = result
                    completionMatches = TranslationRefinementPolicy.matches(result, source, draft.text, "hi", request, chapterContext, glossary)
                    hindi.put("refiner_returned_text", result.text).put("refiner_returned_text_sha256", hash(result.text))
                        .put("refinement_status", result.status.name).put("exact_captured_completion_matches", completionMatches)
                    if (result.status == TranslationRefinementStatus.GENERATED)
                        hindi.put("generated_candidate", result.text).put("generated_candidate_sha256", hash(result.text))
                    result.receipt?.let { hindi.put("attempt_receipt", receiptJson(it)) }
                    check(completionMatches) { "The captured localization attempt supplied no exact completed native receipt: ${result.status}." }
                } else hindi.put("refinement_status", "NOT_REQUESTED")
                val selected = try {
                    TranslationQualityPolicy.chooseDraft(source, draft, if (completionMatches) generated!!.text else "", "hi", style, glossary)
                } catch (rejected: TranslationQualityException) {
                    hindi.put("structural_assessment", "REJECTED").put("structural_failure", rejected.toString())
                    null
                }
                if (selected == null) {
                    roman.put("structural_assessment", "UNAVAILABLE_REJECTED_HINDI")
                        .put("selected", JSONObject.NULL).put("hindi_proof", JSONObject.NULL)
                } else {
                    val selectedFromGenerated = completionMatches && selected.text == generated!!.text
                    hindi.put("selected", selected.text).put("selected_sha256", hash(selected.text))
                        .put("structural_assessment", if (TranslationQualityPolicy.isUsable(source, selected.text, "hi")) "ACCEPTED_OBSERVABLE_GUARDS" else "REJECTED")
                        .put("selected_equals_generated_candidate", selectedFromGenerated)
                        .put("selected_generation_receipt_owns_exact_text", selectedFromGenerated)
                    val encoded = TranslationMemoryCodec.encode(source, selected, "hi")
                    hindi.put("proof_codec_roundtrip", TranslationMemoryCodec.decode(source, encoded, "hi") == selected)
                    try {
                        val paired = HinglishTranslationOutput.fromHindiDraft(source, selected.text, style)
                        val finalRoman = TranslationQualityPolicy.chooseDraft(source, paired, "", "hi-latn", style, glossary)
                        roman.put("selected", finalRoman.text).put("selected_sha256", hash(finalRoman.text))
                            .put("hindi_proof", finalRoman.hindiDraft ?: JSONObject.NULL)
                            .put("hindi_proof_sha256", finalRoman.hindiDraft?.let(::hash) ?: JSONObject.NULL)
                            .put("structural_assessment", if (TranslationQualityPolicy.isUsable(source, finalRoman.text, "hi-latn", finalRoman.hindiDraft)) "ACCEPTED_OBSERVABLE_GUARDS" else "REJECTED")
                            .put("exact_hindi_rendering_evidence", finalRoman.hindiDraft != null)
                            .put("independent_roman_model_generation", false)
                            .put("hindi_attempt_receipt_available", generated?.receipt != null)
                            .put("generation_receipt_does_not_claim_roman_text", true)
                            .put("proof_codec_roundtrip", TranslationMemoryCodec.decode(source,
                                TranslationMemoryCodec.encode(source, finalRoman, "hi-latn"), "hi-latn") == finalRoman)
                    } catch (rejected: TranslationQualityException) {
                        roman.put("structural_assessment", "REJECTED").put("structural_failure", rejected.toString())
                    }
                }
            } }
        } catch (problem: Throwable) { failure = problem; throw problem }
        finally {
            val primaryFailure = failure
            var cleanupFailure: Throwable? = null
            try { service.close() } catch (problem: Throwable) { cleanupFailure = problem }
            try { refiner.close() } catch (problem: Throwable) {
                if (cleanupFailure == null) cleanupFailure = problem else cleanupFailure!!.addSuppressed(problem)
            }
            if (cleanupFailure != null) {
                report.put("cleanup_failure", cleanupFailure.toString())
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure!!) else failure = cleanupFailure
            }
            report.put("collection_status", if (failure == null) "OUTPUTS_COLLECTED" else "FAILED_OR_INCOMPLETE")
                .put("failure", failure?.toString() ?: JSONObject.NULL)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("quality_acceptance", false)
                .put("assessment_note", "Collector completion and structural guards are not meaning, register, naturalness or Hindi/Hinglish equivalence certification. Inspect actual outputs against the source and context; allow valid variants.")
            File(context.getExternalFilesDir(null), "qa/core-smoke/translation-quality-corpus/outputs.json")
                .apply { check(parentFile!!.mkdirs() || parentFile!!.isDirectory) }.writeText(report.toString(2))
            if (primaryFailure == null && cleanupFailure != null) throw cleanupFailure!!
        }
    }

    private fun loadCase(context: Context, caseId: String): JSONObject {
        val bytes = context.assets.open("translation-quality-corpus.jsonl").use { input ->
            val output = ByteArrayOutputStream()
            val block = ByteArray(4096)
            while (true) {
                val count = input.read(block)
                if (count == -1) break
                check(output.size() + count <= MAX_CORPUS_BYTES) { "Corpus exceeds its fixed input budget." }
                output.write(block, 0, count)
            }
            output.toByteArray()
        }
        check(hash(bytes) == CORPUS_SHA256) { "The authored corpus changed; review and capture its new SHA before collecting outputs." }
        val cases = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        check(cases.size == 18 && cases.map { it.getString("id") }.distinct().size == 18)
        val item = requireNotNull(cases.singleOrNull { it.getString("id") == caseId }) { "Unknown authored source case." }
        check(item.getString("source_language") == "en" && item.getString("source").length in 1..4000 && item.getString("context").length <= 4500)
        val glossary = item.getJSONObject("glossary")
        check(glossary.length() <= 20 && glossary.keys().asSequence().all { it.length in 1..256 && glossary.getString(it).length in 1..256 })
        check(item.getJSONObject("references").has("hi") && item.getJSONObject("references").has("hi-latn"))
        return item
    }

    private fun modelJson(model: com.mangalens.orez.OrezModelPin) = JSONObject()
        .put("model_id", model.modelId).put("sha256", model.sha256).put("bytes", model.bytes)

    private fun receiptJson(receipt: TranslationRefinementReceipt): JSONObject = JSONObject()
        .put("model", modelJson(receipt.model)).put("prompt_sha256", receipt.promptSha256).put("output_sha256", receipt.outputSha256)
        .put("completion", receipt.completion?.let(::completionJson) ?: JSONObject.NULL)

    private fun completionJson(value: OrezGenerationCompletion) = JSONObject()
        .put("profile_revision", value.profileRevision).put("formatted_input_sha256", value.formattedInputSha256)
        .put("termination", value.termination).put("prompt_tokens", value.promptTokens)
        .put("generated_tokens", value.generatedTokens).put("token_limit", value.tokenLimit)
        .put("native_lock_wait_us", value.nativeLockWaitUs).put("setup_us", value.setupUs)
        .put("prefill_us", value.prefillUs).put("decode_us", value.decodeUs)

    private fun hash(text: String) = hash(text.toByteArray(Charsets.UTF_8))
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
