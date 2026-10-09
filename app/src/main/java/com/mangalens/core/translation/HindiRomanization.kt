package com.mangalens.core.translation

import java.text.Normalizer
import java.util.Locale

/**
 * Readable Roman Hindi, without another model download. Common spoken spellings take
 * precedence over a bounded Devanagari phoneme/schwa fallback. This is transliteration,
 * not a new semantic translation model or a guarantee of literary localization quality.
 */
object HindiRomanization {
    fun isTarget(target: String): Boolean = target.trim().lowercase(Locale.ROOT).replace('_', '-') == "hi-latn"

    private val hindiWords = Regex("[\\u0900-\\u0963\\u0971-\\u097f]+")
    private val latinWords = Regex("[A-Za-z]+")
    private val commonSpellings = mapOf(
        "मैं" to "main", "में" to "mein", "हूँ" to "hoon", "हूं" to "hoon", "है" to "hai", "हैं" to "hain",
        "हो" to "ho", "हाँ" to "haan", "हां" to "haan", "नहीं" to "nahi", "नही" to "nahi",
        "तुम" to "tum", "तुम्हें" to "tumhein", "तुम्हे" to "tumhe", "तुम्हारा" to "tumhara", "तुम्हारी" to "tumhari", "तुम्हारे" to "tumhare",
        "आप" to "aap", "आपको" to "aapko", "आपका" to "aapka", "आपकी" to "aapki", "आपके" to "aapke",
        "मुझे" to "mujhe", "मुझसे" to "mujhse", "मेरा" to "mera", "मेरी" to "meri", "मेरे" to "mere",
        "तू" to "tu", "तुझे" to "tujhe", "तेरा" to "tera", "तेरी" to "teri", "तेरे" to "tere",
        "यह" to "yeh", "ये" to "ye", "वह" to "woh", "वो" to "woh", "वे" to "ve", "यहाँ" to "yahan", "वहाँ" to "wahan",
        "इस" to "is", "उस" to "us", "उसकी" to "uski", "उसका" to "uska", "उसके" to "uske", "इसकी" to "iski", "इसका" to "iska",
        "हम" to "hum", "हमें" to "humein", "हमारा" to "hamara", "हमारी" to "hamari", "हमारे" to "hamare",
        "क्या" to "kya", "क्यों" to "kyun", "कौन" to "kaun", "कैसे" to "kaise", "कहाँ" to "kahan", "कब" to "kab",
        "का" to "ka", "की" to "ki", "के" to "ke", "को" to "ko", "से" to "se", "ने" to "ne", "और" to "aur", "भी" to "bhi",
        "पर" to "par", "लिए" to "liye", "साथ" to "saath", "पास" to "paas", "अब" to "ab", "फिर" to "phir", "ही" to "hi",
        "ठीक" to "theek", "बहुत" to "bahut", "अच्छा" to "achha", "अच्छी" to "achhi", "अच्छे" to "achhe", "पता" to "pata",
        "हुआ" to "hua", "हुई" to "hui", "हुए" to "hue", "होना" to "hona", "होगा" to "hoga", "होगी" to "hogi", "होंगे" to "honge",
        "था" to "tha", "थी" to "thi", "थे" to "the", "रहा" to "raha", "रही" to "rahi", "रहे" to "rahe",
        "कर" to "kar", "करो" to "karo", "करना" to "karna", "करने" to "karne", "किया" to "kiya", "किए" to "kiye",
        "आ" to "aa", "आओ" to "aao", "आया" to "aaya", "आए" to "aaye", "गया" to "gaya", "गई" to "gayi", "गए" to "gaye",
        "जा" to "ja", "जाओ" to "jao", "जाना" to "jana", "जाने" to "jane", "जाऊंगा" to "jaaunga", "जाऊँगा" to "jaaunga",
        "रुको" to "ruko", "रुक" to "ruk", "देखो" to "dekho", "सुनो" to "suno", "बताओ" to "batao", "बचाओ" to "bachao",
        "चाहिए" to "chahiye", "चाहता" to "chahta", "चाहती" to "chahti", "चाहते" to "chahte", "चिंता" to "chinta",
        "नाम" to "naam", "घर" to "ghar", "दोस्त" to "dost", "पिटाई" to "pitaai", "गुस्सा" to "gussa", "जिंदा" to "zinda",
        "लड़की" to "ladki", "लड़का" to "ladka", "कमरा" to "kamra", "क्षत्रिय" to "kshatriya", "ज्ञान" to "gyaan",
        "नमस्ते" to "namaste", "धन्यवाद" to "dhanyavaad", "शुक्रिया" to "shukriya", "माफ़" to "maaf", "माफ" to "maaf",
        // Familiar loan/genre words keep their familiar Latin spelling.
        "लेवल" to "level", "स्किल" to "skill", "हंटर" to "hunter", "प्लेयर" to "player", "बॉस" to "boss", "टीम" to "team",
        "गेम" to "game", "फोन" to "phone", "ऑफिस" to "office", "बैंक" to "bank"
    )
    private val grammar = setOf("main", "mein", "hoon", "hai", "hain", "ho", "haan", "nahi", "nahin", "tum", "tumhein", "tumhe",
        "tumhara", "tumhari", "tumhare", "aap", "aapko", "mujhe", "mujhse", "mera", "meri", "mere", "tujhe", "tera", "teri", "tere",
        "yeh", "woh", "yahan", "wahan", "hum", "humein", "hamara", "uski", "uska", "uske", "kya", "kyun", "kaun", "kaise", "kahan", "kab",
        "ka", "ki", "ke", "ko", "se", "ne", "aur", "bhi", "par", "liye", "saath", "paas", "phir", "theek", "bahut", "achha",
        "pata", "hua", "hui", "hue", "hona", "hoga", "tha", "thi", "raha", "rahi", "rahe", "kar", "karo", "karna", "kiya",
        "aa", "aao", "aaya", "gaya", "gayi", "gaye", "ja", "jao", "jana", "ruko", "ruk", "dekho", "suno", "batao", "chahiye",
        "raho", "rahiye", "kijiye", "keejiye", "kariye", "jaiye", "aaiye", "bataiye", "lijiye", "leejiye", "dijiye", "deejiye",
        "ghar", "naam", "dost", "ladka", "ladki", "kamra", "chinta", "chahta", "chahti", "chahte", "zinda", "pitaai", "gussa", "namaste", "shukriya", "dhanyavaad")
    private val distinctShortWords = setOf("ruko", "bhaago", "bhago", "bachao", "namaste", "shukriya", "dhanyavaad", "haan", "nahi", "nahin", "mujhe", "kya", "kyun", "kaise", "jeet", "haar", "gussa", "pitaai", "ghar", "dost", "ladka", "ladki", "kamra", "chinta", "chahiye")

    fun isRomanHindi(text: String): Boolean {
        if (text.any { it in '\u0900'..'\u097f' }) return false
        val words = words(text)
        if (words.isEmpty()) return false
        val matches = words.count { it in grammar }
        return (matches >= 2 && matches.toFloat() / words.size >= .30f) || (words.size <= 2 && words.any { it in distinctShortWords })
    }

    internal fun words(text: String): List<String> = latinWords.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()
    internal fun isHindiGrammar(word: String): Boolean = word.lowercase(Locale.ROOT) in grammar

    fun render(hindi: String, source: String = ""): String {
        val normalized = Normalizer.normalize(hindi, Normalizer.Form.NFC)
        // Restore only unambiguous phonetic matches to source name spellings. No fuzzy
        // edit-distance guesses, invented names, or modifications of existing Latin spans.
        val names = (sourceNames(source) + Regex("\\b[A-Z]{2,}\\b").findAll(source).map { it.value }
            .filter { it.lowercase(Locale.ROOT) !in ordinaryEnglish }.toList())
            .groupBy(::phoneticKey).filterValues { it.distinct().size == 1 }.mapValues { it.value.first() }
        return hindiWords.replace(normalized) { match ->
            val generated = commonSpellings[match.value] ?: word(match.value)
            latinWords.replace(generated) { generatedWord -> names[phoneticKey(generatedWord.value)] ?: generatedWord.value }
        }.map { char -> when (char) { '।', '॥' -> '.'; in '\u0966'..'\u096f' -> '0' + (char - '\u0966'); else -> char } }.joinToString("")
    }

    private val ordinaryEnglish = setOf("i", "you", "he", "she", "we", "they", "the", "a", "an", "this", "that", "it", "what", "why", "how",
        "do", "don't", "can", "will", "was", "were", "am", "are", "is", "be", "beaten", "up", "down", "get", "got", "wait", "go", "run",
        "please", "tell", "say", "stop", "help", "thank", "thanks", "sorry", "hello", "goodbye", "now", "then", "today", "tomorrow", "yesterday",
        "after", "before", "when", "where", "yes", "no", "fine", "okay", "ok", "my", "our", "their", "your", "his", "her", "its", "all", "some",
        "any", "each", "every", "one", "two", "three", "new", "old", "good", "bad", "more", "less", "even", "really", "never", "always", "stay",
        "come", "leave", "give", "take", "look", "listen", "read", "write", "kill", "die", "dead", "alive", "life", "time", "day", "night",
        "main", "fire", "power", "sword", "seriously", "injured", "wounded", "broken", "worried", "hurt", "angry", "first", "last", "danger", "risk", "accident",
        "experience", "experiencing", "pain", "going", "home", "with", "without", "about", "feel", "feeling", "think", "thought",
        "worry", "don", "does", "did", "not", "me", "him", "them", "for", "from", "to", "of", "in", "on", "at", "by", "as")
    internal fun sourceNames(source: String): List<String> = Regex("\\b[A-Z][a-z][A-Za-z]*\\b").findAll(source).map { it.value }
        .filter { it.lowercase(Locale.ROOT) !in ordinaryEnglish }.toList()
    internal fun retainedLatinWords(source: String): Set<String> {
        val loanWords = setOf("level", "rank", "skill", "hunter", "boss", "mana", "guild", "team", "player", "game", "phone", "office", "bank")
        return sourceNames(source).map { it.lowercase(Locale.ROOT) }.toSet() + words(source).filter { it in loanWords }
    }

    private fun phoneticKey(text: String): String = text.lowercase(Locale.ROOT)
        .replace("aa", "a").replace("ee", "i").replace("oo", "u").replace('w', 'v')

    private data class Sound(val stem: String, var vowel: String, val inherent: Boolean, var nasal: String = "")
    private val consonants = mapOf('क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng", 'च' to "ch", 'छ' to "chh",
        'ज' to "j", 'झ' to "jh", 'ञ' to "ny", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'क़' to "q", 'ख़' to "kh", 'ग़' to "gh", 'ज़' to "z", 'ड़' to "d", 'ढ़' to "dh", 'फ़' to "f", 'य़' to "y", 'ळ' to "l")
    private val vowels = mapOf('अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ऌ' to "li", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e", 'ऎ' to "e", 'ऒ' to "o")
    private val matras = mapOf('ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri", 'ॄ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e", 'ॆ' to "e", 'ॊ' to "o")
    private val nukta = mapOf('क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "d", 'ढ' to "dh", 'फ' to "f", 'य' to "y")

    private fun word(value: String): String {
        val sounds = mutableListOf<Sound>()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                value.startsWith("ज्ञ", index) -> { sounds += Sound("gy", "a", true); index += 3; continue }
                char in consonants -> {
                    val marked = value.getOrNull(index + 1) == '़'
                    sounds += Sound(if (marked) nukta[char] ?: consonants.getValue(char) else consonants.getValue(char), "a", true)
                    if (marked) index++
                }
                char in vowels -> sounds += Sound("", vowels.getValue(char), false)
                char in matras -> sounds.lastOrNull()?.let { sound ->
                    sounds[sounds.lastIndex] = sound.copy(vowel = matras.getValue(char), inherent = false)
                }
                char == '्' -> sounds.lastOrNull()?.vowel = ""
                char == 'ं' || char == 'ँ' -> sounds.lastOrNull()?.nasal = if (value.getOrNull(index + 1)?.let { it in "पफबभम" } == true) "m" else "n"
                char == 'ः' -> sounds += Sound("h", "", false)
                char in setOf('़', '\u0951', '\u0952', '\u0953', '\u0954') -> Unit
                else -> sounds += Sound(char.toString(), "", false) // Quality gate rejects unsupported script, preserving source.
            }
            index++
        }
        sounds.lastOrNull()?.takeIf { it.inherent && it.nasal.isEmpty() }?.vowel = ""
        for (position in sounds.lastIndex - 1 downTo 1) {
            val sound = sounds[position]
            if (sound.inherent && sound.vowel == "a" && sound.nasal.isEmpty() &&
                sounds[position - 1].vowel.isNotEmpty() && sounds[position + 1].vowel.isNotEmpty()) sound.vowel = ""
        }
        return sounds.joinToString("") { it.stem + it.vowel + it.nasal }
    }
}
