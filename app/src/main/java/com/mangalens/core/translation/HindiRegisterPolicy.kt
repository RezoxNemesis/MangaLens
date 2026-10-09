package com.mangalens.core.translation

/** Complete, known address forms only. Unknown finite/gender/mixed-subject clauses stay intact. */
internal object HindiRegisterPolicy {
    fun normalize(text: String, roman: Boolean, hostile: Boolean): String = clauses.findAll(text).joinToString("") { part ->
        val clause = part.value
        if (clause.first() in "।.!?;:\n") clause else normalizeClause(clause, roman, hostile)
    }

    private fun normalizeClause(clause: String, roman: Boolean, hostile: Boolean): String {
        val subject = if (roman) "aap" else "आप"
        val politeSubject = word(subject, roman)
        val subjects = politeSubject.findAll(clause).toList()
        val finite = (if (roman) romanFinite else hindiFinite).findAll(clause).toList()
        val imperative = (if (roman) romanImperatives else hindiImperatives).keys.any { word(it, roman).containsMatchIn(clause) }
        val ambiguous = (if (roman) romanMixedSubjects else hindiMixedSubjects).containsMatchIn(clause)
        var out = clause
        if (subjects.isNotEmpty()) {
            if (subjects.size != 1 || ambiguous) return clause
            val copula = if (roman) "hain" else "हैं"
            val replacement = if (roman) { if (hostile) "tu" else "tum" } else { if (hostile) "तू" else "तुम" }
            when {
                !hostile && finite.size == 1 && finite.single().value.equals(copula, ignoreCase = roman) &&
                    finite.single().range.last == clause.trimEnd().lastIndex -> {
                    out = politeSubject.replace(out, replacement)
                    out = word(copula, roman).replace(out, if (roman) "ho" else "हो")
                }
                (imperative && finite.isEmpty()) || clause.trim().equals(subject, ignoreCase = roman) ->
                    out = politeSubject.replace(out, replacement)
                else -> return clause
            }
        }
        val possessives = if (roman) {
            if (hostile) mapOf("aapko" to "tujhe", "aapka" to "tera", "aapki" to "teri", "aapke" to "tere",
                "tumhein" to "tujhe", "tumhe" to "tujhe", "tumhara" to "tera", "tumhari" to "teri", "tumhare" to "tere")
            else mapOf("aapko" to "tumhein", "aapka" to "tumhara", "aapki" to "tumhari", "aapke" to "tumhare")
        } else {
            if (hostile) mapOf("आपको" to "तुझे", "आपका" to "तेरा", "आपकी" to "तेरी", "आपके" to "तेरे",
                "तुम्हें" to "तुझे", "तुम्हारा" to "तेरा", "तुम्हारी" to "तेरी", "तुम्हारे" to "तेरे")
            else mapOf("आपको" to "तुम्हें", "आपका" to "तुम्हारा", "आपकी" to "तुम्हारी", "आपके" to "तुम्हारे")
        }
        possessives.forEach { (from, to) -> out = word(from, roman).replace(out, to) }
        // A known hostile imperative also changes an existing plain "tum" subject
        // to "tu"; otherwise changing only karo→kar would create another mismatch.
        if (hostile && imperative && finite.isEmpty() && !ambiguous)
            out = word(if (roman) "tum" else "तुम", roman).replace(out, if (roman) "tu" else "तू")
        val imperativeMap = if (roman) romanImperatives else hindiImperatives
        imperativeMap.forEach { (from, forms) -> out = word(from, roman).replace(out, if (hostile) forms.second else forms.first) }
        return out
    }

    private fun word(value: String, roman: Boolean) = Regex(
        "(?<![\\p{L}\\p{M}\\p{N}])${Regex.escape(value)}(?![\\p{L}\\p{M}\\p{N}])",
        if (roman) setOf(RegexOption.IGNORE_CASE) else emptySet()
    )
    private val clauses = Regex("[^।.!?;:\n]+|[।.!?;:\n]+")
    private val hindiFinite = Regex("(?<![\\p{L}\\p{M}])(?:हैं|है|हो|हूँ|थे|था|थीं|थी)(?![\\p{L}\\p{M}])")
    private val romanFinite = Regex("\\b(?:hain|hai|ho|hoon|the|tha|thin|theen|thi)\\b", RegexOption.IGNORE_CASE)
    private val hindiMixedSubjects = Regex("(?<![\\p{L}\\p{M}])(?:और|वे|हम|जो|जिस|जिन|कि|वह|ये)(?![\\p{L}\\p{M}])")
    private val romanMixedSubjects = Regex("\\b(?:aur|ve|woh|vo|ham|hum|jo|jis|jin|ki|vah|ye)\\b", RegexOption.IGNORE_CASE)
    private val hindiImperatives = mapOf("बताइए" to ("बताओ" to "बता"), "जाइए" to ("जाओ" to "जा"),
        "आइए" to ("आओ" to "आ"), "रहिए" to ("रहो" to "रह"), "लीजिए" to ("लो" to "ले"),
        "दीजिए" to ("दो" to "दे"), "कीजिए" to ("करो" to "कर"), "करिए" to ("करो" to "कर"))
    private val romanImperatives = mapOf("kijiye" to ("karo" to "kar"), "keejiye" to ("karo" to "kar"),
        "kariye" to ("karo" to "kar"), "jaiye" to ("jao" to "ja"), "aaiye" to ("aao" to "aa"),
        "bataiye" to ("batao" to "bata"), "rahiye" to ("raho" to "rah"), "lijiye" to ("lo" to "le"),
        "leejiye" to ("lo" to "le"), "dijiye" to ("do" to "de"), "deejiye" to ("do" to "de"))
}
