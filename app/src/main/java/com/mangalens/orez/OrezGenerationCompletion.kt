package com.mangalens.orez

/** Scalar evidence from the actual native result; it carries no dialogue or private source material. */
data class OrezGenerationCompletion(
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
    internal fun completedLocalization(prompt: String, capturedRevision: String = OrezLocalizationProfile.REVISION): Boolean =
        OrezLocalizationProfile.supported(capturedRevision) && profileRevision == capturedRevision &&
        formattedInputSha256 == OrezLocalizationProfile.hash(OrezLocalizationProfile.formattedPrompt(prompt, capturedRevision)) &&
        termination == "EOG" && promptTokens in 1..4096 && tokenLimit == OrezLocalizationProfile.MAX_TOKENS &&
        generatedTokens in 1..tokenLimit && nativeLockWaitUs >= 0 && setupUs >= 0 && prefillUs >= 0 && decodeUs >= 0
}
