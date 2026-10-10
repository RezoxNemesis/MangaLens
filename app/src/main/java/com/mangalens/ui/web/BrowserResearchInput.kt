package com.mangalens.ui.web

import com.mangalens.orez.research.OrezResearchRequest

/** Only a user-authored question enters ordinary Orez input. Prefilling never submits a task. */
internal object BrowserResearchInput {
    fun command(question: String): String? {
        val input = question.trim()
        if (input.any { it in "\"“”\r\n" }) return null
        val command = "Research \"$input\""
        return OrezResearchRequest.parseExplicit(command)?.let { command }
    }
}
