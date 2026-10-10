package com.mangalens.orez

/** The reviewed current capture remains v2. Decode/replay never selects it implicitly. */
internal object OrezLocalizationProfile {
    const val REVISION = OrezLocalizationV2.REVISION
    const val MAX_TOKENS = OrezLocalizationV2.MAX_TOKENS
    const val SYSTEM = OrezLocalizationV2.SYSTEM

    fun supported(revision: String?): Boolean = revision == REVISION
    fun formattedPrompt(prompt: String): String = OrezLocalizationV2.formattedPrompt(prompt)
    fun formattedPrompt(prompt: String, revision: String): String {
        require(supported(revision)) { "The captured localization input version is unsupported." }
        return OrezLocalizationV2.formattedPrompt(prompt)
    }
    fun hash(value: String): String = OrezLocalizationV2.hash(value)
}
