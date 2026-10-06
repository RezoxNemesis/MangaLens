package com.mangalens.core.reader

/**
 * Conservative classifier for chapter images that are clearly scan-group promotion/credit pages.
 * This never deletes a page. The reader may collapse a matched page and always lets the user
 * reveal it again, which keeps false positives reversible.
 */
object ReaderPromoPolicy {
    fun isLikelyPromo(text: String): Boolean {
        val value = text.lowercase()
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (value.length < 12) return false

        val hardPhrases = listOf(
            "read ad free",
            "read without ads",
            "upgrade to our premium",
            "upgrade to premium",
            "join our discord",
            "scanlation credits",
            "scanlation team",
            "temporary domain",
            "want to enjoy our website with no interruptions from ads"
        )
        if (hardPhrases.any(value::contains)) return true

        var score = 0
        if ("announcement" in value || "announcement!" in value) score += 1
        if ("discord.gg" in value || "discord.com/invite" in value || "discord" in value) score += 2
        if ("ad free" in value || "without ads" in value || "premium version" in value) score += 3
        if ("translator" in value || "proofreader" in value || "typesetter" in value || "raw provider" in value) score += 2
        if ("scanlation" in value || "scans.com" in value || "scan.com" in value) score += 2
        if ("asurascans" in value || "asura scans" in value || "demonicscans" in value || "mangademon" in value) score += 2
        if ("click here" in value && ("read" in value || "join" in value)) score += 1

        return score >= 4
    }
}
