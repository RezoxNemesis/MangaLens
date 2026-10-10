package com.mangalens.orez.agent

/** Literal user request only. Saved metadata/model/page text never supplies this authority. */
enum class OrezLibraryOperation(val tool: String, val selected: Boolean, val mutation: Boolean) {
    SEARCH("search_library", false, false), RECENT("list_recent_chapters", false, false),
    READ_CHAPTER("read_chapter_metadata", true, false), BOOKMARK("set_chapter_bookmark", true, true),
    READ_SERIES("read_series_memory", true, false), SET_TERM("set_user_series_term", true, true)
}
data class OrezLibraryRequest(val operation: OrezLibraryOperation, val query: String = "",
    val bookmarked: Boolean? = null, val source: String = "", val preferred: String = "",
    val target: String = "", val limit: Int = 24) {
    fun validate(): OrezLibraryRequest {
        require(limit in 1..24)
        require(listOf(query, source, preferred, target).all { value -> value.none(Char::isISOControl) })
        when (operation) {
            OrezLibraryOperation.SEARCH -> require(query.isNotBlank() && query.length <= 256 && bookmarked == null && source.isEmpty() && preferred.isEmpty() && target.isEmpty())
            OrezLibraryOperation.BOOKMARK -> require(bookmarked != null && query.isEmpty() && source.isEmpty() && preferred.isEmpty() && target.isEmpty())
            OrezLibraryOperation.SET_TERM -> require(source.isNotBlank() && source.length <= 256 && preferred.isNotBlank() && preferred.length <= 256 &&
                target in TARGETS && bookmarked == null && query.isEmpty())
            else -> require(query.isEmpty() && bookmarked == null && source.isEmpty() && preferred.isEmpty() && target.isEmpty())
        }
        return this
    }
    fun arguments(chapterId: String?): Map<String, String> {
        validate()
        if (operation.selected) require(chapterId?.matches(Regex("[a-f0-9]{32}")) == true)
        return buildMap {
            if (operation.selected) put("chapterId", requireNotNull(chapterId))
            when (operation) {
                OrezLibraryOperation.SEARCH -> { put("query", query); put("limit", limit.toString()) }
                OrezLibraryOperation.RECENT -> put("limit", limit.toString())
                OrezLibraryOperation.BOOKMARK -> put("bookmarked", bookmarked.toString())
                OrezLibraryOperation.SET_TERM -> { put("source", source); put("preferred", preferred); put("targetLanguage", target) }
                else -> Unit
            }
        }
    }
    companion object {
        val TARGETS: Set<String> = java.util.Collections.unmodifiableSet(setOf("en", "hi", "hi-latn", "ja", "ko", "zh", "fr", "es", "de"))
        val tools: Set<String> = java.util.Collections.unmodifiableSet(OrezLibraryOperation.entries.map { it.tool }.toSet())
        /** Whole-request matching cannot extract a command from advice, a quotation or a URL. */
        fun parseExplicit(input: String): OrezLibraryRequest? {
            val text = input.trim()
            if (text.length !in 1..1024 || text.any(Char::isISOControl)) return null
            val prefix = "(?:please\\s+)?"
            Regex("^${prefix}search\\s+(?:my\\s+)?library\\s+for\\s+[\"“]([^\"“”]{1,256})[\"”][.!]?$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { if (it.groupValues[1].isBlank()) return null; return OrezLibraryRequest(OrezLibraryOperation.SEARCH, query = it.groupValues[1]).validate() }
            Regex("^${prefix}(?:list|show)\\s+(?:my\\s+)?recent\\s+chapters(?:\\s+limit\\s+([0-9]{1,2}))?[.!]?$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let {
                    val limit = it.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 24
                    if (limit !in 1..24) return null
                    return OrezLibraryRequest(OrezLibraryOperation.RECENT, limit = limit).validate()
                }
            Regex("^${prefix}(?:read|inspect)\\s+(?:this|current|selected)\\s+saved\\s+chapter\\s+metadata[.!]?$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { return OrezLibraryRequest(OrezLibraryOperation.READ_CHAPTER) }
            Regex("^${prefix}(bookmark|unbookmark)\\s+(?:this|current|selected)\\s+chapter[.!]?$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { return OrezLibraryRequest(OrezLibraryOperation.BOOKMARK, bookmarked = it.groupValues[1].equals("bookmark", true)) }
            Regex("^${prefix}(?:get|read|show)\\s+(?:this|current|selected)\\s+(?:chapter['’]s\\s+)?series\\s+(?:memory|glossary)[.!]?$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { return OrezLibraryRequest(OrezLibraryOperation.READ_SERIES) }
            Regex("^${prefix}set\\s+series\\s+term\\s+[\"“]([^\"“”]{1,256})[\"”]\\s+to\\s+[\"“]([^\"“”]{1,256})[\"”]\\s+in\\s+(en|hi-latn|hi|ja|ko|zh|fr|es|de)[.!]?$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { if (it.groupValues[1].isBlank() || it.groupValues[2].isBlank()) return null; return OrezLibraryRequest(OrezLibraryOperation.SET_TERM, source = it.groupValues[1], preferred = it.groupValues[2], target = it.groupValues[3].lowercase(java.util.Locale.ROOT)).validate() }
            return null
        }
        fun captured(objective: String, tool: String, arguments: Map<String, String>, chapterId: String?): OrezLibraryRequest {
            val request = requireNotNull(parseExplicit(objective)) { "Use one explicit Library request." }
            require(tool == request.operation.tool && arguments == request.arguments(chapterId)) { "Library tool differs from the captured literal request." }
            return request
        }
    }
}
