package com.mangalens.orez.agent

internal object OrezLibraryArgumentPolicy {
    fun validate(tool: String, arguments: Map<String,String>) {
        val operation = OrezLibraryOperation.entries.single { it.tool == tool }
        val chapter = arguments["chapterId"]
        val request = when (operation) {
            OrezLibraryOperation.SEARCH -> OrezLibraryRequest(operation,query=arguments.getValue("query"),limit=arguments.getValue("limit").toInt())
            OrezLibraryOperation.RECENT -> OrezLibraryRequest(operation,limit=arguments.getValue("limit").toInt())
            OrezLibraryOperation.BOOKMARK -> OrezLibraryRequest(operation,bookmarked=arguments.getValue("bookmarked").let { require(it in setOf("true","false")); it == "true" })
            OrezLibraryOperation.SET_TERM -> OrezLibraryRequest(operation,source=arguments.getValue("source"),preferred=arguments.getValue("preferred"),target=arguments.getValue("targetLanguage"))
            else -> OrezLibraryRequest(operation)
        }.validate()
        require(request.arguments(chapter) == arguments) { "Unexpected or noncanonical Library tool arguments." }
    }
}
