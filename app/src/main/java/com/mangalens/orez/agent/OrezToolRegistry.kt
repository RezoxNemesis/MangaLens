package com.mangalens.orez.agent

import com.mangalens.core.router.UrlEngineRouter
import com.mangalens.orez.OrezRoute

/** Trusted descriptors, independent of model-supplied risk/route metadata. */
class OrezToolRegistry {
    private data class Descriptor(val route: OrezRoute?, val capability: OrezCapability, val risk: OrezToolRisk, val requiresUrl: Boolean = false)
    private val tools = mapOf(
        "check_model_status" to Descriptor(null, OrezCapability.SETTINGS, OrezToolRisk.READ_ONLY),
        "inspect_app_diagnostics" to Descriptor(null, OrezCapability.SETTINGS, OrezToolRisk.READ_ONLY),
        "open_library" to Descriptor(OrezRoute.LIBRARY, OrezCapability.LIBRARY, OrezToolRisk.READ_ONLY),
        "open_settings" to Descriptor(OrezRoute.SETTINGS, OrezCapability.SETTINGS, OrezToolRisk.READ_ONLY),
        "open_downloads" to Descriptor(OrezRoute.DOWNLOADS, OrezCapability.DOWNLOADS, OrezToolRisk.READ_ONLY),
        "research_web" to Descriptor(null, OrezCapability.RESEARCH, OrezToolRisk.NETWORK_READ),
        "browser_observe" to Descriptor(null, OrezCapability.WEB, OrezToolRisk.READ_ONLY),
        "browser_extract" to Descriptor(null, OrezCapability.WEB, OrezToolRisk.READ_ONLY),
        "browser_navigate" to Descriptor(null, OrezCapability.WEB, OrezToolRisk.NETWORK_MUTATION),
        "browser_click" to Descriptor(null, OrezCapability.WEB, OrezToolRisk.NETWORK_MUTATION),
        "browser_fill" to Descriptor(null, OrezCapability.WEB, OrezToolRisk.NETWORK_MUTATION),
        "browser_scroll" to Descriptor(null, OrezCapability.WEB, OrezToolRisk.LOCAL_MUTATION),
        "search_library" to Descriptor(null, OrezCapability.LIBRARY, OrezToolRisk.READ_ONLY),
        "list_recent_chapters" to Descriptor(null, OrezCapability.LIBRARY, OrezToolRisk.READ_ONLY),
        "read_chapter_metadata" to Descriptor(null, OrezCapability.LIBRARY, OrezToolRisk.READ_ONLY),
        "set_chapter_bookmark" to Descriptor(null, OrezCapability.LIBRARY, OrezToolRisk.LOCAL_MUTATION),
        "read_series_memory" to Descriptor(null, OrezCapability.LIBRARY, OrezToolRisk.READ_ONLY),
        "set_user_series_term" to Descriptor(null, OrezCapability.LIBRARY, OrezToolRisk.LOCAL_MUTATION),
        "search_saved_memory" to Descriptor(null, OrezCapability.LIBRARY, OrezToolRisk.READ_ONLY),
        "save_next_chapter" to Descriptor(null, OrezCapability.READER, OrezToolRisk.NETWORK_READ),
        "save_chapter_url" to Descriptor(null, OrezCapability.READER, OrezToolRisk.NETWORK_READ),
        "inspect_saved_chapter" to Descriptor(null, OrezCapability.LIBRARY, OrezToolRisk.READ_ONLY),
        "translate_saved_chapter" to Descriptor(null, OrezCapability.TRANSLATION, OrezToolRisk.LOCAL_MUTATION),
        "inspect_selected_media" to Descriptor(null, OrezCapability.MEDIA, OrezToolRisk.READ_ONLY),
        "inspect_downloaded_media" to Descriptor(null, OrezCapability.MEDIA, OrezToolRisk.READ_ONLY),
        "generate_subtitles" to Descriptor(null, OrezCapability.TRANSLATION, OrezToolRisk.LOCAL_MUTATION),
        "translate_active_chapter" to Descriptor(OrezRoute.TRANSLATE_ACTIVE_CHAPTER, OrezCapability.TRANSLATION, OrezToolRisk.LOCAL_MUTATION),
        "open_download_flow" to Descriptor(OrezRoute.DOWNLOADS, OrezCapability.DOWNLOADS, OrezToolRisk.NETWORK_READ, true),
        "enqueue_download" to Descriptor(OrezRoute.DOWNLOADS, OrezCapability.DOWNLOADS, OrezToolRisk.NETWORK_READ, true),
        "translate_manga_url" to Descriptor(OrezRoute.TRANSLATE_MANGA, OrezCapability.TRANSLATION, OrezToolRisk.NETWORK_READ, true),
        "translate_video_url" to Descriptor(OrezRoute.TRANSLATE_VIDEO, OrezCapability.TRANSLATION, OrezToolRisk.NETWORK_READ, true),
        "translate_web_url" to Descriptor(OrezRoute.TRANSLATE_WEB, OrezCapability.TRANSLATION, OrezToolRisk.NETWORK_READ, true),
        "open_reader_url" to Descriptor(OrezRoute.MANGA_READER, OrezCapability.READER, OrezToolRisk.NETWORK_READ, true),
        "open_video_url" to Descriptor(OrezRoute.VIDEO_PLAYER, OrezCapability.MEDIA, OrezToolRisk.NETWORK_READ, true),
        "open_web_url" to Descriptor(OrezRoute.WEB_VIEW, OrezCapability.WEB, OrezToolRisk.NETWORK_READ, true)
    )

    fun validate(call: OrezToolCall) {
        val descriptor = requireNotNull(tools[call.name]) { "Unknown Orez tool" }
        require(call.route == descriptor.route && call.capability == descriptor.capability && call.risk == descriptor.risk) {
            "Tool metadata does not match the trusted registry"
        }
        if (call.name in setOf("check_model_status", "inspect_app_diagnostics")) {
            require(call.arguments.isEmpty()) { "App inspection accepts no paths, settings or model-supplied arguments." }
            return
        }
        if (call.name in com.mangalens.ui.web.BrowserDomPolicy.names) {
            com.mangalens.ui.web.BrowserDomPolicy.validateArguments(call.name, call.arguments)
            return
        }
        if (call.name == "research_web") {
            require(call.arguments.keys == setOf("query", "limit", "freshness")) { "Research needs one captured question." }
            com.mangalens.orez.research.OrezResearchRequest(call.arguments.getValue("query"),
                com.mangalens.orez.research.ResearchFreshnessRequest.valueOf(call.arguments.getValue("freshness")),
                call.arguments.getValue("limit").toInt()).validate()
            return
        }
        if (call.name in setOf("save_next_chapter", "save_chapter_url")) {
            if (call.name == "save_next_chapter") require(call.arguments.keys == setOf("chapterId") &&
                call.arguments.getValue("chapterId").matches(Regex("[a-f0-9]{32}"))) { "Next chapter requires one captured saved source identity." }
            else {
                require(call.arguments.keys == setOf("value")) { "Chapter acquisition requires one literal user URL." }
                OrezNextChapterPolicy.publicUrl(call.arguments.getValue("value"))
            }
            return
        }
        if (call.name in OrezLibraryRequest.tools) {
            OrezLibraryArgumentPolicy.validate(call.name,call.arguments)
            return
        }
        val nativeChapter = call.name in setOf("inspect_saved_chapter", "translate_saved_chapter")
        val nativeMedia = call.name in setOf("inspect_selected_media", "inspect_downloaded_media", "generate_subtitles")
        val keys = if (call.name == "search_saved_memory") setOf("chapterId", "query", "limit") else if (nativeChapter) setOf("chapterId", "sourceFingerprint", "targetLanguage")
            else if (nativeMedia) setOf("sourceId", "downloadId", "sourceFingerprint", "speechModelSha256", "captionInventorySha256", "targetLanguage")
            else setOf("value", "targetLanguage", "quality")
        require(call.arguments.keys.all { it in keys }) { "Unknown tool argument" }
        call.arguments["chapterId"]?.let { require(it.matches(Regex("[a-f0-9]{32}"))) { "Invalid saved chapter identity" } }
        call.arguments["sourceFingerprint"]?.let { require(it.matches(Regex("[a-f0-9]{64}"))) { "Invalid chapter source receipt" } }
        call.arguments["speechModelSha256"]?.let { require(it.matches(Regex("[a-f0-9]{64}")) || it.isEmpty() && call.arguments["captionInventorySha256"]?.matches(Regex("[a-f0-9]{64}")) == true) { "Installed speech-model evidence or captured provider inventory is missing." } }
        call.arguments["captionInventorySha256"]?.let { require(it.matches(Regex("[a-f0-9]{64}"))) { "Invalid captured caption inventory." } }
        call.arguments["sourceId"]?.let { require(it.matches(Regex("(?:selected-[a-f0-9]{32}|download-[A-Za-z0-9-]{1,140})"))) { "Invalid captured media identity." } }
        call.arguments["downloadId"]?.let { require(it.matches(Regex("[A-Za-z0-9-]{1,140}"))) { "Invalid owned download identity." } }
        if (call.name == "search_saved_memory") {
            require(call.arguments.keys in setOf(setOf("chapterId", "query"), setOf("chapterId", "query", "limit"))) { "Memory search requires one selected chapter and literal query." }
            require(call.arguments.getValue("query").let { it.isNotBlank() && it.length <= 256 && '\u0000' !in it }) { "Memory query must contain 1–256 characters." }
            call.arguments["limit"]?.let { require(it.toIntOrNull()?.let { value -> value in 1..8 } == true) { "Memory search returns up to 8 matches." } }
        }

        if (call.name == "inspect_saved_chapter") {
            require(call.arguments.keys == setOf("chapterId")) { "Select one saved chapter to inspect." }
        }
        if (call.name == "inspect_selected_media") require(call.arguments.keys == setOf("sourceId")) { "Select one playable source to inspect." }
        if (call.name == "inspect_downloaded_media") require(call.arguments.keys.all { it == "downloadId" }) { "Downloaded media requires the exact typed native download." }
        call.arguments["quality"]?.let {
            require(it != "AMBIGUOUS") { "Choose one quality ceiling for the batch, or send separate requests." }
            require(call.name == "enqueue_download" && it in setOf("BEST", "P480", "P720", "P1080", "P1440", "P2160")) {
                "Unsupported download quality"
            }
        }
        val url = call.arguments["value"].orEmpty()
        require(!descriptor.requiresUrl || url.isNotBlank()) { "This tool requires a URL" }
        require(url.isBlank() || (url.length <= 8192 && UrlEngineRouter.isSafeWebUrl(url))) { "Invalid or unsafe URL" }
        call.arguments["targetLanguage"]?.let {
            if (call.name == "generate_subtitles") require(it in setOf("en", "hi", "hi-latn")) { "This native subtitle provider supports English, Hindi and Hinglish." }
            require(descriptor.capability == OrezCapability.TRANSLATION && it in setOf("hi", "hi-latn", "en", "ja", "ko", "zh", "fr", "es", "de")) {
                "Unsupported translation target"
            }
        }
    }

    fun call(name: String, arguments: Map<String, String>): OrezToolCall {
        val descriptor = requireNotNull(tools[name]) { "Unknown Orez tool" }
        return OrezToolCall(name, descriptor.capability, descriptor.risk,
            name.replace('_', ' '), arguments, descriptor.route).also(::validate)
    }

    /** Foreground-only catalog; these entries are deliberately absent from the durable/chat catalog. */
    internal fun browserCatalog(): String = tools.entries.filter { it.key in com.mangalens.ui.web.BrowserDomPolicy.names }
        .joinToString("\n") { (name, descriptor) ->
            val arguments = when (name) {
                "browser_navigate" -> "value=HTTP(S) address supplied by the user goal"
                "browser_click" -> "elementId=observed link/button identity"
                "browser_fill" -> "elementId=observed safe field identity; text=literal text from the user goal"
                "browser_scroll" -> "delta=nonzero integer string between -2048 and 2048"
                else -> "optional query=literal text up to 256 characters"
            }
            "$name: ${descriptor.capability.name}; ${descriptor.risk.name}; $arguments"
        }

    // Native workflows are built from captured app scope, never invented by a model response.
    fun catalog(): String = tools.entries.filter { it.value.route != null }.joinToString("\n") { (name, descriptor) ->
        "$name: ${descriptor.capability.name}; " + (if (descriptor.requiresUrl) "value=HTTP(S) URL" else "no URL required") +
            (if (name == "enqueue_download") "; optional quality=BEST/P480/P720/P1080/P1440/P2160" else "")
    }
}

