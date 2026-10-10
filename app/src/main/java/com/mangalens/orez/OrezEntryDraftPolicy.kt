package com.mangalens.orez

import com.mangalens.ui.web.BrowserResearchInput

/** Opening a draft never sends a message or authorizes a tool. */
internal object OrezEntryDraftPolicy {
    private fun valid(value: String): Boolean = value.length <= 1024 && value.none { it.isISOControl() && it != '\n' && it != '\t' }
    fun initial(research: String, request: String): String {
        if (!valid(research) || !valid(request) || (research.isNotBlank() && request.isNotBlank())) return ""
        return if (request.isNotBlank()) request.trim() else BrowserResearchInput.command(research).orEmpty()
    }
}
