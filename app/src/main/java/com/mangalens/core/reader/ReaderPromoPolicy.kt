package com.mangalens.core.reader

import java.util.Locale

/**
 * Conservative classifier for chapter images that are clearly commercial or scan-group promotion/credit pages.
 * This never deletes a page. The reader may collapse a matched page and always lets the user
 * reveal it again, which keeps false positives reversible.
 */
object ReaderPromoPolicy {
    private val hardPhrases = listOf(
        "read ad free", "read without ads", "upgrade to our premium", "upgrade to premium",
        "join our discord", "scanlation credits", "scanlation team",
        "want to enjoy our website with no interruptions from ads",
        "support our scanlation"
    )
    private val promoMarkers = hardPhrases + listOf(
        "discord", "ad free", "without ads", "premium version", "translator", "proofreader",
        "typesetter", "raw provider", "scanlation", "asurascans", "asura scans", "demonicscans",
        "mangademon", "manga demon", "click here", "our website", "our site", "support us", "credits:",
        "girlfriendgpt", "girlfriend gpt", "veyragame", "start chatting", "chat now", "play at",
        "please consider donating", "consider donating", "support our work", "support our scanlation"
    )

    // A brand or generic action can occur in dialogue; do not discard the sentence after it.
    private val preserveSuffixMarkers = setOf("girlfriendgpt", "girlfriend gpt", "veyragame", "start chatting", "chat now", "play at",
        "asurascans", "asura scans", "demonicscans", "mangademon", "manga demon")

    const val MAX_TEXT_CHARS = 32_768
    private val commercialStatusLabel = Regex("^(?:[\\p{L}\\p{N}]{1,24}\\s+)?online\\s*now[.!]?$", RegexOption.IGNORE_CASE)
    private val announcementLabel = Regex("^announcement[!: ]*$", RegexOption.IGNORE_CASE)

    fun isLikelyPromo(text: String): Boolean {
        // Never classify a truncated prefix of a longer story as a whole promotional page.
        if (text.length > MAX_TEXT_CHARS) return false
        val value = text.lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (value.length < 12) return false

        var score = 0
        if ("announcement" in value || "announcement!" in value) score += 1
        if ("discord.gg" in value || "discord.com/invite" in value || "discord" in value) score += 2
        if ("ad free" in value || "without ads" in value || "premium version" in value) score += 3
        if ("translator" in value || "proofreader" in value || "typesetter" in value || "raw provider" in value) score += 2
        if ("scanlation" in value || "scans.com" in value || "scan.com" in value) score += 2
        if ("asurascans" in value || "asura scans" in value || "demonicscans" in value || "mangademon" in value) score += 2
        if ("click here" in value && ("read" in value || "join" in value)) score += 1

        val commercialService = listOf("girlfriendgpt", "girlfriend gpt", "veyragame").any(value::contains) &&
            listOf("start chatting", "chat now", "chat with", "play at", "play now", "try now").any(value::contains)
        val scanService = listOf("demonicscans", "demonic scans", "asurascans", "asura scans", "mangademon", "manga demon").any(value::contains) &&
            listOf("consider donating", "support our work", "support us", "visit our", "read at", "read on").any(value::contains)
        val websiteMove = "temporary domain" in value &&
            listOf("website", "our site", "visit", "https://", "http://").any(value::contains)
        if (score < 4 && !websiteMove && !commercialService && !scanService && hardPhrases.none(value::contains)) return false

        // OCR often combines the last story panel and a scan-group footer in one image.
        // Decide using the remaining dialogue, not the mere presence of a footer phrase.
        val storyText = if (commercialService) text.lineSequence().filterNot { line ->
            val label = line.trim()
            commercialStatusLabel.matches(label) || announcementLabel.matches(label)
        }.joinToString("\n") else text
        val storyLetters = storyText.lowercase(Locale.ROOT)
            .split(Regex("""[\r\n!?。！？]+|\.(?:\s+|$)"""))
            .sumOf { part ->
                val marker = promoMarkers.map { it to part.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
                val story = if (marker == null) part else part.substring(0, marker.second) +
                    if (marker.first in preserveSuffixMarkers) part.substring(marker.second + marker.first.length) else ""
                story.replace(Regex("""\b(?:chapter|episode|page)\s*[\d.:-]+\b"""), "")
                    .count(Char::isLetter)
            }
        val totalLetters = value.count(Char::isLetter)
        return !(storyLetters >= 48 && storyLetters >= totalLetters * .30)
    }
}

/** Display hint from already accepted Native source lettering; never a source/read credential. */
internal object ReaderSavedPromoPolicy {
    fun classify(sourceTexts: List<String>, acceptedPageComplete: Boolean): Boolean {
        if (!acceptedPageComplete || sourceTexts.isEmpty() || sourceTexts.size > 512) return false
        var size = sourceTexts.size - 1
        for (text in sourceTexts) {
            if (text.length > ReaderPromoPolicy.MAX_TEXT_CHARS - size) return false
            size += text.length
        }
        return ReaderPromoPolicy.isLikelyPromo(sourceTexts.joinToString("\n"))
    }
}
