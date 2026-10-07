package com.mangalens.orez

/** Model chat tokens are syntax owned by the runtime, never by source material. */
object OrezPromptBoundary {
    private val chatToken = Regex("<\\|[^\\r\\n]{1,100}?\\|>")
    fun data(value: String): String = chatToken.replace(value) { token ->
        token.value.replace("<", "‹").replace(">", "›")
    }
}
