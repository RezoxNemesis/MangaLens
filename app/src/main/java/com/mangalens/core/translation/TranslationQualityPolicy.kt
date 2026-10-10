package com.mangalens.core.translation

import com.mangalens.engine.OcrSourceQuality
import java.util.Locale

class TranslationQualityException : IllegalArgumentException(
    "Translation failed language/quality checks. Original text was preserved; retry this bubble or page."
)

/**
 * Deterministic guardrails for dialogue translation.
 *
 * The on-device translator/refiner can occasionally return source-language leakage,
 * runaway explanatory prose or empty output. Keep a strong fast draft available and
 * only accept refinement when it remains plausible for the requested target language.
 */
object TranslationQualityPolicy {
    fun choose(
        source: String,
        draft: String,
        refined: String,
        targetLanguage: String,
        hindiDraft: String? = null,
        style: TranslationStyleProfile? = null,
        capturedGlossary: Map<String, String> = emptyMap()
    ): String = chooseDraft(source, TranslationDraft(draft, hindiDraft), refined, targetLanguage, style, capturedGlossary).text

    fun chooseDraft(
        source: String,
        draft: TranslationDraft,
        refined: String,
        targetLanguage: String,
        style: TranslationStyleProfile? = null,
        capturedGlossary: Map<String, String> = emptyMap()
    ): TranslationDraft {
        val cleanDraft = clean(draft.text)
        val cleanRefined = clean(refined)
        val draftUsable = isAcceptable(source, "", cleanDraft, targetLanguage, hindiDraft = draft.hindiDraft)
        // A refiner/model output is independently checked. The draft's Hindi evidence
        // never grants a different English or Roman candidate permission to pass.
        val comparisonGlossary = capturedGlossary.takeIf { it.size <= 20 }?.toMap()
        val refinedUsable = isAcceptable(source, if (draftUsable) cleanDraft else "", cleanRefined, targetLanguage) &&
            comparisonGlossary != null && TranslationCandidateComparisonPolicy.assess(source, if (draftUsable) cleanDraft else "",
                cleanRefined, targetLanguage, comparisonGlossary) == TranslationCandidateComparison.COMPATIBLE
        val selected = when {
            refinedUsable -> cleanRefined
            draftUsable -> cleanDraft
            else -> throw TranslationQualityException()
        }
        val harmonized = harmonizeRegister(source, selected, targetLanguage, style)
        // A register edit is another candidate. Never let a later deterministic edit
        // bypass the independent checks that admitted the original selected text.
        val selectedHindiProof = draft.hindiDraft?.takeIf { draftUsable && selected == cleanDraft && HindiRomanization.isTarget(targetLanguage) }
        val harmonizedHindiProof = selectedHindiProof?.let {
            try { hindiDraftForRomanOutput(source, it, style) } catch (_: TranslationQualityException) { null }
        }
        val registerAware = harmonized.takeIf { isAcceptable(source, "", it, targetLanguage, hindiDraft = harmonizedHindiProof) &&
            comparisonGlossary != null && TranslationCandidateComparisonPolicy.assess(source, selected, it, targetLanguage,
                comparisonGlossary) == TranslationCandidateComparison.COMPATIBLE } ?: selected
        val result = restoreTerminalPunctuation(source, registerAware)
        val retainedProof = selectedHindiProof?.let {
            if (registerAware == harmonized) harmonizedHindiProof ?: it else it
        }?.takeIf { isUsable(source, result, targetLanguage, it) }
        return TranslationDraft(result, retainedProof)
    }

    /** Also check persisted drafts; an older bad result must not bypass today's gate. */
    fun isUsable(source: String, candidate: String, targetLanguage: String, hindiDraft: String? = null): Boolean =
        isAcceptable(source, "", clean(candidate), targetLanguage, hindiDraft = hindiDraft)

    /** Preserve known Latin names/genre terms in the Hindi intermediate, never English clauses. */
    internal fun hindiDraftForRomanOutput(source: String, draft: String, style: TranslationStyleProfile? = null): String {
        val candidate = checkedHindiEvidence(source, draft)
        return restoreTerminalPunctuation(source, harmonizeRegister(source, candidate, "hi", style))
    }

    private fun checkedHindiEvidence(source: String, draft: String): String {
        val candidate = clean(draft)
        if (!isAcceptable(source, "", candidate, "hi", HindiRomanization.retainedLatinWords(source))) throw TranslationQualityException()
        return restoreTerminalPunctuation(source, candidate)
    }

    private fun languageTag(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-')

    private fun clean(value: String): String =
        value.replace(Regex("[\\t\\r ]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun isAcceptable(
        source: String,
        draft: String,
        candidate: String,
        targetLanguage: String,
        retainedLatin: Set<String> = emptySet(),
        hindiDraft: String? = null
    ): Boolean {
        if (candidate.isBlank() || OcrSourceQuality.needsPixelRetry(source) ||
            !TranslationMeaningPolicy.isCompatible(source, candidate, targetLanguage)) return false
        val verifiedHindiRendering = if (hindiDraft == null) false else {
            if (!HindiRomanization.isTarget(targetLanguage) || hindiDraft.length !in 1..MAX_HINDI_DRAFT_CHARS ||
                source.length !in 1..MAX_HINDI_DRAFT_CHARS || candidate.length !in 1..MAX_HINDI_DRAFT_CHARS ||
                hindiDraft.none { it in '\u0900'..'\u097f' && it.isLetter() }) return false
            val checkedHindi = try { checkedHindiEvidence(source, hindiDraft) }
                catch (_: TranslationQualityException) { return false }
            val exactRendering = restoreTerminalPunctuation(source, clean(HindiRomanization.render(checkedHindi, source)))
            if (candidate != exactRendering) return false
            true
        }
        // Exact validated Hindi remains the grammar evidence for its Roman output.
        // Roman spelling alone cannot reliably infer Hindi gender/case; an unproven
        // refiner candidate must pass independently rather than borrow that proof.
        if (!verifiedHindiRendering && !TranslationFluencyPolicy.isPlausible(source, candidate, targetLanguage)) return false
        if (HindiRomanization.isTarget(targetLanguage) && candidate.none(Char::isLetter)) {
            // Ellipses, punctuation and numbers are valid dialogue; preserve their
            // actual value instead of manufacturing a Hindi word to satisfy a ratio.
            return source.none(Char::isLetter) && candidate == clean(HindiRomanization.render(source))
        }

        val normalizedSource = normalize(source)
        val normalizedDraft = normalize(draft)
        val normalizedCandidate = normalize(candidate)
        val allowedLatin = retainedLatin + TranslationMeaningPolicy.protectedLatinNameWords(source)
        val protectedWords = if (HindiRomanization.isTarget(targetLanguage)) HindiRomanization.retainedLatinWords(source) + allowedLatin else allowedLatin
        val candidateLatinWords = HindiRomanization.words(candidate)
        val retainedIdentity = normalizedCandidate == normalizedSource && candidateLatinWords.size in 1..2 &&
            candidateLatinWords.all { it in protectedWords }
        if (
            normalizedCandidate == normalizedSource &&
            normalizedDraft.isNotBlank() &&
            normalizedDraft != normalizedSource
        ) return false

        val baseline = maxOf(source.length, draft.length, 6)
        if (candidate.length > baseline * 4 + 48) return false

        val words = normalizedCandidate.split(' ').filter { it.isNotBlank() }
        if (words.size >= 6) {
            val mostFrequent = words.groupingBy { it }.eachCount().maxOfOrNull { it.value } ?: 0
            if (mostFrequent >= 5 && mostFrequent * 2 >= words.size) return false
        }

        // A standalone established name/genre term can remain Latin in the Hindi
        // intermediate. This exception never admits an unprotected English clause.
        if (allowedLatin.isNotEmpty() && retainedIdentity && candidate.filter(Char::isLetter).all { it.code in 0x0041..0x024F }) return true
        val scriptCandidate = if (allowedLatin.isEmpty()) candidate else Regex("[A-Za-z]+").replace(candidate) {
            if (it.value.lowercase(Locale.ROOT) in allowedLatin) "" else it.value
        }
        if (!targetScriptLooksPlausible(scriptCandidate, targetLanguage)) return false
        if (HindiRomanization.isTarget(targetLanguage)) {
            val sourceLetters = source.filter(Char::isLetter)
            val sourceHasHindi = sourceLetters.isNotEmpty() && sourceLetters.count { it in '\u0900'..'\u097f' }.toFloat() / sourceLetters.length >= .30f
            val romanGrammarText = Regex("[A-Za-z]+").replace(candidate) {
                if (it.value.lowercase(Locale.ROOT) in protectedWords) "" else it.value
            }
            val candidateWords = HindiRomanization.words(romanGrammarText)
            val shortChangedHindi = normalizedCandidate != normalizedSource && candidateWords.size <= 2 &&
                candidateWords.any(HindiRomanization::isHindiGrammar)
            // Hindi input is evidence for its direct transliteration, not permission
            // for a refiner to return an arbitrary English sentence in Latin script.
            val directHindiRendering = sourceHasHindi && normalizedCandidate == normalize(HindiRomanization.render(source, source))
            if (!HindiRomanization.isRomanHindi(romanGrammarText) && !shortChangedHindi && !directHindiRendering && !retainedIdentity && !verifiedHindiRendering) return false
            if (!sourceHasHindi && normalizedCandidate == normalizedSource && !HindiRomanization.isRomanHindi(source) && !retainedIdentity) return false
            // Preserve names and genre terms, while rejecting a copied English clause
            // lightly decorated with Hindi particles. A script check alone cannot do this.
            if (!sourceHasHindi && !HindiRomanization.isRomanHindi(source)) {
                val sourceEnglishWords = latinWords(source).filterNot(HindiRomanization::isHindiGrammar)
                val sourceEnglish = sourceEnglishWords.toSet()
                val candidateWords = latinWords(candidate)
                val copiedPhrases = sourceEnglishWords.zipWithNext().filterNot { (first, second) ->
                    first in protectedWords && second in protectedWords
                }.toSet()
                if (candidateWords.zipWithNext().any { it in copiedPhrases }) return false
                val copied = candidateWords.count { it in sourceEnglish && !HindiRomanization.isHindiGrammar(it) }
                if (copied >= 3 && copied * 2 > candidateWords.size) return false
            }
            return true
        }
        val target = languageTag(targetLanguage)
        if (target in setOf("hi", "mr", "ne")) {
            // A Hindi paragraph can meet the script ratio yet retain a whole English
            // clause such as "BEATEN UP". Reject copied multiword spans, while permitting
            // a single name/honorific. Explicit glossary support belongs in the caller.
            val sourceWords = latinWords(source)
            val copiedPhrases = sourceWords.zipWithNext().filterNot { (first, second) -> first in allowedLatin && second in allowedLatin }.toSet()
            val candidateRuns = Regex("[A-Za-z]{2,}(?:[\\s'-]+[A-Za-z]{2,})+").findAll(candidate)
            if (candidateRuns.any { run -> latinWords(run.value).zipWithNext().any { it in copiedPhrases } }) return false
            val sourceLetters = source.filter(Char::isLetter)
            val sourceLatin = sourceLetters.count { it.code in 0x0041..0x024F }
            if (sourceLetters.isNotEmpty() && sourceLatin.toFloat() / sourceLetters.length >= .65f) {
                val candidateLetters = scriptCandidate.filter(Char::isLetter)
                if (candidateLetters.length >= 4) {
                    val devanagari = candidateLetters.count { it in '\u0900'..'\u097f' }
                    val latin = candidateLetters.count { it.code in 0x0041..0x024F }
                    if (devanagari.toFloat() / candidateLetters.length < .55f || latin.toFloat() / candidateLetters.length > .40f) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun latinWords(value: String): List<String> =
        Regex("[A-Za-z]{2,}").findAll(value).map { it.value.lowercase(Locale.ROOT) }.toList()

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun targetScriptLooksPlausible(text: String, targetLanguage: String): Boolean {
        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty()) return text.any { !it.isWhitespace() }
        if (HindiRomanization.isTarget(targetLanguage)) {
            return letters.all { it.code in 0x0041..0x024F }
        }
        val language = languageTag(targetLanguage)
        val matching = when (language) {
            "hi", "mr", "ne" -> letters.count { it in '\u0900'..'\u097f' }
            "ja" -> letters.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
            "ko" -> letters.count { it in '\uac00'..'\ud7af' }
            "zh" -> letters.count { it in '\u4e00'..'\u9fff' }
            "en", "es", "fr", "de", "it", "pt", "id", "vi", "tr" ->
                letters.count { it.code in 0x0041..0x024F }
            else -> return true
        }
        return matching.toFloat() / letters.length >= .42f
    }

    /**
     * ML Kit often makes English→Hindi dialogue artificially respectful ("आप/कीजिए")
     * even when the source uses neutral or hostile "you". Manga dialogue should not invent
     * hierarchy that is absent from the source. Keep explicit honorific/formal cues untouched,
     * otherwise normalize only a small, high-confidence set of second-person forms.
     */
    private fun harmonizeRegister(source: String, translation: String, targetLanguage: String, style: TranslationStyleProfile? = null): String {
        if (languageTag(targetLanguage) != "hi") return translation
        // Without an explicit register choice preserve the validated model grammar.
        // Formal/custom/faithful own their register; only the following explicit
        // dialogue profiles permit conservative normalization of known address forms.
        if (style?.id !in setOf("natural", "casual", "webtoon")) return translation
        val sourceLower = source.lowercase(Locale.ROOT)
        val addressesSomeone = Regex("""\b(you|your|you're|you've|you'll|don't|do not|can you|will you)\b""").containsMatchIn(sourceLower)
        if (!addressesSomeone) return translation

        val explicitFormal = Regex(
            """\b(sir|madam|ma'am|lord|lady|master|mistress|your highness|your majesty|professor|doctor|sensei|senpai)\b"""
        ).containsMatchIn(sourceLower)
        if (explicitFormal) return translation

        val hostile = Regex(
            """\b(son of a|bastard|idiot|moron|fool|damn|hell|shut up|pissed|asshole|jerk|trash|scum|beat(?:en)? up)\b"""
        ).containsMatchIn(sourceLower)

        val harmonized = HindiRegisterPolicy.normalize(translation, HindiRomanization.isTarget(targetLanguage), hostile)
        // Address words may also be explicit names. A register edit never grants
        // permission to lose a protected source identity or numeric/polarity cue.
        return harmonized.takeIf { TranslationMeaningPolicy.isCompatible(source, it, targetLanguage) } ?: translation
    }

    private fun restoreTerminalPunctuation(source: String, translation: String): String {
        // Only the terminal mark is evidence. A question inside a sentence must not
        // turn the final translated statement into a question.
        val sourceMark = source.trim().lastOrNull()?.takeIf { it in "?!…！？。" } ?: return translation
        if (translation.isBlank() || translation.last() in "?!…！？。") return translation
        val mark = when (sourceMark) {
            '？' -> '?'
            '！' -> '!'
            '。' -> '.'
            else -> sourceMark
        }
        return translation.trimEnd('.', '।') + mark
    }

    private const val MAX_HINDI_DRAFT_CHARS = 8000
}
