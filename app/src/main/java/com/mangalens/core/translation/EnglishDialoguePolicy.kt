package com.mangalens.core.translation

import java.util.Locale

/** A source-language hint for dialogue; Latin lettering alone does not establish English. */
object EnglishDialoguePolicy {
    private val clause = Regex(
        """\b(?:i|you|we|they|he|she|it)\s+(?:am|are|is|was|were|can|could|will|would|have|has|had|do|did|know|think|want|need|see|feel|remember)\b|\b(?:am|are|is|was|were|can|could|will|would|do|did)\s+(?:i|you|we|they|he|she|it)\b"""
    )
    private val contraction = Regex(
        """\b(?:i'm|i'll|i've|i'd|you're|you'll|you've|you'd|we're|we'll|we've|they're|they'll|they've|he's|she's|it's|that's|don't|doesn't|didn't|can't|couldn't|won't|wouldn't|isn't|aren't|wasn't|weren't|let's)\b"""
    )
    private val idiom = Regex(
        """\b(?:get out|go away|let me|leave me|beaten up|give up|no way|what the|come on|of course|thank you|all right|oh my|how dare|shut up)\b"""
    )
    private val imperatives = setOf("get", "leave", "go", "come", "give", "take", "look", "listen", "stay", "wait", "stop", "please", "help", "run", "open", "close", "protect", "keep", "tell", "bring", "move")
    private val complements = setOf("the", "a", "an", "me", "my", "us", "our", "it", "your", "his", "her", "them", "their", "and", "for", "to", "at", "of", "some", "this", "that", "these", "those", "home", "here", "there", "away", "back", "now", "ahead", "down", "up", "inside", "outside", "on", "off", "out", "quiet")
    private val standalone = setOf("help", "stop", "wait", "sorry", "thanks", "hello", "goodbye")

    fun shouldHintEnglish(text: String): Boolean {
        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty() || letters.count { it in 'A'..'Z' || it in 'a'..'z' } < letters.length * .85) return false
        val value = text.lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("""\s+"""), " ")
        val words = Regex("""[a-z]+(?:'[a-z]+)?""").findAll(value).map { it.value }.toList()
        if (words.isEmpty()) return false
        if (words.size == 1) return words.single() in standalone || contraction.containsMatchIn(value)
        return clause.containsMatchIn(value) || contraction.containsMatchIn(value) ||
            idiom.containsMatchIn(value) ||
            (words.first() in imperatives && (words.first() == "please" || words.drop(1).any { it in complements }))
    }
}
