package com.mangalens.ui.video

import com.mangalens.orez.OrezGenerationCompletion
import org.json.JSONObject

/** Exact copied native completion evidence. A missing field never implies successful completion. */
data class SubtitleRefinementCompletionEvidence(
    val profileRevision: String,
    val formattedInputSha256: String,
    val termination: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val tokenLimit: Int,
    val nativeLockWaitUs: Long,
    val setupUs: Long,
    val prefillUs: Long,
    val decodeUs: Long
) {
    internal fun generationCompletion() = OrezGenerationCompletion(profileRevision, formattedInputSha256,
        termination, promptTokens, generatedTokens, tokenLimit, nativeLockWaitUs, setupUs, prefillUs, decodeUs)
}

internal fun OrezGenerationCompletion.subtitleCompletion() = SubtitleRefinementCompletionEvidence(profileRevision,
    formattedInputSha256, termination, promptTokens, generatedTokens, tokenLimit, nativeLockWaitUs, setupUs, prefillUs, decodeUs)

internal object SubtitleRefinementCompletionCodec {
    fun encode(evidence: SubtitleRefinementCompletionEvidence): JSONObject = JSONObject()
        .put("profileRevision", evidence.profileRevision).put("formattedInputSha256", evidence.formattedInputSha256)
        .put("termination", evidence.termination).put("promptTokens", evidence.promptTokens)
        .put("generatedTokens", evidence.generatedTokens).put("tokenLimit", evidence.tokenLimit)
        .put("nativeLockWaitUs", evidence.nativeLockWaitUs).put("setupUs", evidence.setupUs)
        .put("prefillUs", evidence.prefillUs).put("decodeUs", evidence.decodeUs)

    fun decode(json: JSONObject) = SubtitleRefinementCompletionEvidence(json.getString("profileRevision"),
        json.getString("formattedInputSha256"), json.getString("termination"), json.getInt("promptTokens"),
        json.getInt("generatedTokens"), json.getInt("tokenLimit"), json.getLong("nativeLockWaitUs"),
        json.getLong("setupUs"), json.getLong("prefillUs"), json.getLong("decodeUs"))
}
