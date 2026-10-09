package com.mangalens.orez

import java.util.Locale

/** Keep encyclopedia background evidence separate from current-information verification. */
internal object OrezLiveAnswerPolicy {
    suspend fun answer(provider: String, snippets: List<String>, synthesize: suspend () -> String?): String =
        if (isEncyclopedia(provider)) fallback(provider, snippets)
        else synthesize() ?: fallback(provider, snippets)

    private fun isEncyclopedia(provider: String): Boolean = provider.trim().equals("wikipedia", ignoreCase = true)

    private fun attributed(provider: String, body: String): String = if (isEncyclopedia(provider)) {
        "Wikipedia encyclopedia background. I cannot verify current news, prices or schedules from these excerpts. " +
            "Wikipedia excerpts are CC BY-SA 4.0; check the linked original pages.\n\n" + body
    } else body

    fun fallback(provider: String, snippets: List<String>): String {
        val cleanedSnippets = snippets.asSequence()
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length >= 20 }
            .filterNot { value ->
                val lower = value.lowercase(Locale.ROOT)
                listOf(
                    "jump to content", "main menu", "navigation", "create account", "log in",
                    "sign in", "donate", "google images", "advertising business solutions",
                    "google account", "privacy terms", "continue on web"
                ).any(lower::contains)
            }
            .distinct()
            .take(3)
            .toList()
        if (cleanedSnippets.isEmpty()) {
            return attributed(provider, "I found sources, but their readable text was not clean enough to answer reliably. Try a more specific question.")
        }
        val sentences = cleanedSnippets.joinToString(" ")
            .split(Regex("""(?<=[.!?])\s+"""))
            .map { it.trim() }
            .filter { it.length >= 15 }
            .distinct()
            .take(5)
            .joinToString(" ")
            .take(1100)
        return attributed(provider, if (sentences.isNotBlank()) sentences
            else cleanedSnippets.first().take(900))
    }
}
