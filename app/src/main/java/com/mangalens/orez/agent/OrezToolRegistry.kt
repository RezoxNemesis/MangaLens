package com.mangalens.orez.agent

import com.mangalens.core.router.UrlEngineRouter
import com.mangalens.orez.OrezRoute

/** Trusted descriptors, independent of model-supplied risk/route metadata. */
class OrezToolRegistry {
    private data class Descriptor(val route: OrezRoute?, val capability: OrezCapability, val risk: OrezToolRisk, val requiresUrl: Boolean = false)
    private val tools = mapOf(
        "open_library" to Descriptor(OrezRoute.LIBRARY, OrezCapability.LIBRARY, OrezToolRisk.READ_ONLY),
        "open_settings" to Descriptor(OrezRoute.SETTINGS, OrezCapability.SETTINGS, OrezToolRisk.READ_ONLY),
        "open_downloads" to Descriptor(OrezRoute.DOWNLOADS, OrezCapability.DOWNLOADS, OrezToolRisk.READ_ONLY),
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
        val nativeChapter = call.name in setOf("inspect_saved_chapter", "translate_saved_chapter")
        val nativeMedia = call.name in setOf("inspect_selected_media", "inspect_downloaded_media", "generate_subtitles")
        val keys = if (nativeChapter) setOf("chapterId", "sourceFingerprint", "targetLanguage")
            else if (nativeMedia) setOf("sourceId", "downloadId", "sourceFingerprint", "speechModelSha256", "targetLanguage")
            else setOf("value", "targetLanguage", "quality")
        require(call.arguments.keys.all { it in keys }) { "Unknown tool argument" }
        call.arguments["chapterId"]?.let { require(it.matches(Regex("[a-f0-9]{32}"))) { "Invalid saved chapter identity" } }
        call.arguments["sourceFingerprint"]?.let { require(it.matches(Regex("[a-f0-9]{64}"))) { "Invalid chapter source receipt" } }
        call.arguments["speechModelSha256"]?.let { require(it.matches(Regex("[a-f0-9]{64}"))) { "Installed speech-model evidence is missing." } }
        call.arguments["sourceId"]?.let { require(it.matches(Regex("(?:selected-[a-f0-9]{32}|download-[A-Za-z0-9-]{1,140})"))) { "Invalid captured media identity." } }
        call.arguments["downloadId"]?.let { require(it.matches(Regex("[A-Za-z0-9-]{1,140}"))) { "Invalid owned download identity." } }
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

    // Native workflows are built from captured app scope, never invented by a model response.
    fun catalog(): String = tools.entries.filter { it.value.route != null }.joinToString("\n") { (name, descriptor) ->
        "$name: ${descriptor.capability.name}; " + (if (descriptor.requiresUrl) "value=HTTP(S) URL" else "no URL required") +
            (if (name == "enqueue_download") "; optional quality=BEST/P480/P720/P1080/P1440/P2160" else "")
    }
}

