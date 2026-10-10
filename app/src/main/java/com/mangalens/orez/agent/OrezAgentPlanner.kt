package com.mangalens.orez.agent

import com.mangalens.core.model.ContentType
import com.mangalens.core.router.UrlEngineRouter
import com.mangalens.orez.OrezRoute
import java.util.Locale

class OrezAgentPlanner(
    private val urlRouter: UrlEngineRouter = UrlEngineRouter()
) {
    fun plan(input: String, context: OrezAgentContext): OrezTaskPlan? {
        val clean = input.trim()
        if (clean.isBlank() || com.mangalens.orez.OrezAppKnowledge.isHelpRequest(clean)) return null
        val libraryRequest = OrezLibraryRequest.parseExplicit(clean)
        if (libraryRequest != null && context.origin == OrezTrustOrigin.USER && context.explicitUserRequest) {
            val scope = context.libraryScope?.validate() ?: return null
            if (scope.request != libraryRequest || scope.selectedChapterId != context.activeChapterId.takeIf { libraryRequest.operation.selected }) return null
            return OrezTaskPlan(objective=clean,steps=listOf(OrezPlanStep(0,OrezToolRegistry().call(libraryRequest.operation.tool,
                libraryRequest.arguments(scope.selectedChapterId)))))
        }
        val research = com.mangalens.orez.research.OrezResearchRequest.parseExplicit(clean)
        if (research != null && context.origin == OrezTrustOrigin.USER && context.explicitUserRequest) {
            return OrezTaskPlan(objective = clean, steps = listOf(OrezPlanStep(0,
                OrezToolRegistry().call("research_web", research.arguments()))))
        }
        if (context.origin == OrezTrustOrigin.USER && context.explicitUserRequest) {
            val scope = context.chapterAcquisition
            val next = scope?.next
            if (OrezNextChapterRequest.isRequested(clean) && next != null && next.chapterId == context.activeChapterId)
                return OrezTaskPlan(objective = clean, steps = listOf(OrezPlanStep(0, OrezToolRegistry().call(
                    "save_next_chapter", mapOf("chapterId" to next.chapterId)))))
            val literal = OrezNextChapterRequest.directUrl(clean)
            if (literal != null && scope?.next == null && scope?.targetUrl == literal)
                return OrezTaskPlan(objective = clean, steps = listOf(OrezPlanStep(0, OrezToolRegistry().call(
                    "save_chapter_url", mapOf("value" to literal)))))
        }
        val lower = URL_REGEX.replace(clean.lowercase(Locale.ROOT), " ").trim()
        val explicitUrls = URL_REGEX.findAll(clean).map { it.value.trimEnd('.', ',', ')', ']', '!', '?') }
            .distinct().take(OrezDurablePlanRules.MAX_STEPS + 1).toList()
        val explicitUrl = explicitUrls.firstOrNull()
        val url = explicitUrl ?: context.activeUrl?.takeIf {
            isAny(lower, "download this", "download the current", "save this video", "save media")
        }

        val memoryQuery = Regex("""^(?:please\s+)?search\s+(?:saved\s+(?:dialogue|text)|chapter\s+memory)\s+for\s+["“](.{1,256})["”](?:\s+in\s+(?:this|current)\s+chapter)?[.!]?$""", RegexOption.IGNORE_CASE)
            .matchEntire(clean)?.groupValues?.get(1)?.trim()
        if (memoryQuery != null && context.origin == OrezTrustOrigin.USER && context.explicitUserRequest &&
            context.hasActiveChapter && context.activeChapterId != null) {
            return OrezTaskPlan(objective = clean, steps = listOf(OrezPlanStep(0, OrezToolRegistry().call(
                "search_saved_memory", mapOf("chapterId" to context.activeChapterId, "query" to memoryQuery)))))
        }

        if (OrezSubtitleRequest.isRequested(clean) && !OrezSubtitleRequest.isDownloadChain(clean)) {
            val selection = context.selectedMedia ?: return null
            val registry = OrezToolRegistry()
            return OrezTaskPlan(objective = clean, steps = listOf(
                OrezPlanStep(0, registry.call("inspect_selected_media", mapOf("sourceId" to selection.sourceId))),
                subtitleStep(1, 0, OrezSubtitleRequest.target(clean, context.subtitleOptions.targetLanguage), selection.providerCaptions != null)
            ))
        }

        val actionable = OrezModelPlanDecoder.isActionRequest(clean) || lower in setOf("download manager", "downloads screen")
        if (actionable && wantsTranslation(lower) &&
            isAny(lower, "this chapter", "current chapter", "whole chapter", "entire chapter") && context.hasActiveChapter &&
            context.activeChapterId != null) {
            val registry = OrezToolRegistry()
            val target = withTarget(registry.call("translate_saved_chapter",
                mapOf("targetLanguage" to context.translationOptions.targetLanguage)), lower)
            return OrezTaskPlan(objective = clean, steps = listOf(
                OrezPlanStep(0, registry.call("inspect_saved_chapter", mapOf("chapterId" to context.activeChapterId))),
                OrezPlanStep(1, target, dependsOn = setOf(0), references = mapOf(
                    "chapterId" to OrezOutputReference(0, OrezOutputField.CHAPTER_ID),
                    "sourceFingerprint" to OrezOutputReference(0, OrezOutputField.SOURCE_FINGERPRINT)))
            ))
        }
        val direct = if (actionable) when {
            isAny(lower, "open downloads", "show downloads", "download manager", "downloads screen") ->
                tool(
                    name = "open_downloads",
                    capability = OrezCapability.DOWNLOADS,
                    risk = OrezToolRisk.READ_ONLY,
                    summary = "Open Download Room",
                    route = OrezRoute.DOWNLOADS,
                    value = context.activeUrl.orEmpty()
                )

            isAny(lower, "open library", "show library", "open my library", "show my library") ->
                tool(
                    name = "open_library",
                    capability = OrezCapability.LIBRARY,
                    risk = OrezToolRisk.READ_ONLY,
                    summary = "Open MangaLens Library",
                    route = OrezRoute.LIBRARY
                )

            isAny(lower, "open settings", "show settings", "mangalens settings") ->
                tool(
                    name = "open_settings",
                    capability = OrezCapability.SETTINGS,
                    risk = OrezToolRisk.READ_ONLY,
                    summary = "Open MangaLens Settings",
                    route = OrezRoute.SETTINGS
                )

            wantsTranslation(lower) &&
                isAny(lower, "this chapter", "current chapter", "whole chapter", "entire chapter") &&
                context.hasActiveChapter ->
                tool(
                    name = "translate_active_chapter",
                    capability = OrezCapability.TRANSLATION,
                    risk = OrezToolRisk.LOCAL_MUTATION,
                    summary = "Translate the active chapter",
                    route = OrezRoute.TRANSLATE_ACTIVE_CHAPTER
                )

            else -> null
        } else null
        if (direct != null) return task(clean, listOf(withTarget(direct, lower)))

        if (url == null) return null
        if (!actionable && clean != explicitUrl) return null

        val contentType = urlRouter.classifyUrl(url)
        // URL hosts, paths and query parameters describe content, not user intent.
        // A manga hostname must not override "Play"; ?download=1 is not a command.
        val intentText = lower
        val download = Regex("""^(?:(?:please|can you|could you)\s+)?(?:download|save)\b""", RegexOption.IGNORE_CASE).containsMatchIn(intentText)
        val translate = wantsTranslation(intentText)
        val play = isAny(intentText, "play", "watch", "stream", "open video")
        val read = isAny(intentText, "read", "chapter", "reader", "manga", "comic")

        val call = when {
            download -> tool(
                name = "enqueue_download",
                capability = OrezCapability.DOWNLOADS,
                risk = OrezToolRisk.NETWORK_READ,
                summary = "Queue the download at best available quality",
                route = OrezRoute.DOWNLOADS,
                value = url
            )

            translate && contentType == ContentType.IMAGE_CHAPTER -> tool(
                name = "translate_manga_url",
                capability = OrezCapability.TRANSLATION,
                risk = OrezToolRisk.NETWORK_READ,
                summary = "Open and translate the manga chapter",
                route = OrezRoute.TRANSLATE_MANGA,
                value = url
            )

            translate && contentType == ContentType.VIDEO_STREAM -> tool(
                name = "translate_video_url",
                capability = OrezCapability.TRANSLATION,
                risk = OrezToolRisk.NETWORK_READ,
                summary = "Open the video with translation enabled",
                route = OrezRoute.TRANSLATE_VIDEO,
                value = url
            )

            translate -> tool(
                name = "translate_web_url",
                capability = OrezCapability.TRANSLATION,
                risk = OrezToolRisk.NETWORK_READ,
                summary = "Open the page with web translation enabled",
                route = OrezRoute.TRANSLATE_WEB,
                value = url
            )

            !play && (read || contentType == ContentType.IMAGE_CHAPTER) -> tool(
                name = "open_reader_url",
                capability = OrezCapability.READER,
                risk = OrezToolRisk.NETWORK_READ,
                summary = "Open the URL in Reader",
                route = OrezRoute.MANGA_READER,
                value = url
            )

            contentType == ContentType.VIDEO_STREAM || play -> tool(
                name = "open_video_url",
                capability = OrezCapability.MEDIA,
                risk = OrezToolRisk.NETWORK_READ,
                summary = "Open the URL in the native video flow",
                route = OrezRoute.VIDEO_PLAYER,
                value = url
            )

            else -> tool(
                name = "open_web_url",
                capability = OrezCapability.WEB,
                risk = OrezToolRisk.NETWORK_READ,
                summary = "Open the URL in MangaLens Web",
                route = OrezRoute.WEB_VIEW,
                value = url
            )
        }

        if (download) {
            val qualities = Regex("""\b(480p|720p|1080p|1440p|2160p|4k)\b""").findAll(lower)
                .map { when (it.value) {
                    "480p" -> "P480"; "720p" -> "P720"; "1080p" -> "P1080"
                    "1440p" -> "P1440"; else -> "P2160"
                } }.distinct().toList()
            // Conflicting per-item qualities need clarification, never silently choose one.
            val quality = if (qualities.size > 1) "AMBIGUOUS" else qualities.firstOrNull() ?: "BEST"
            val urls = explicitUrls.ifEmpty { listOf(url) }
            if (OrezSubtitleRequest.isDownloadChain(clean)) {
                // One exact native transfer is the only source of this typed dependency.
                if (urls.size != 1) return null
                return OrezTaskPlan(objective = clean, steps = listOf(
                    OrezPlanStep(0, call.copy(arguments = mapOf("value" to urls.single(), "quality" to quality))),
                    OrezPlanStep(1, OrezToolRegistry().call("inspect_downloaded_media", emptyMap()), dependsOn = setOf(0),
                        references = mapOf("downloadId" to OrezOutputReference(0, OrezOutputField.DOWNLOAD_ID))),
                    subtitleStep(2, 1, OrezSubtitleRequest.target(clean, context.subtitleOptions.targetLanguage))
                ))
            }
            return task(clean, urls.map { value -> call.copy(
                summary = "Download and verify media (${if (quality == "BEST") "best available" else quality.removePrefix("P") + "p"})",
                arguments = mapOf("value" to value, "quality" to quality)
            ) })
        }
        return task(clean, listOf(withTarget(call, lower)))
    }

    private fun subtitleStep(index: Int, sourceIndex: Int, target: String, providerCaptions: Boolean = false) = OrezPlanStep(index,
        OrezToolRegistry().call("generate_subtitles", mapOf("targetLanguage" to "en")).let {
            it.copy(arguments = mapOf("targetLanguage" to target))
        }, dependsOn = setOf(sourceIndex), references = mapOf(
            "sourceId" to OrezOutputReference(sourceIndex, OrezOutputField.MEDIA_SOURCE_ID),
            "sourceFingerprint" to OrezOutputReference(sourceIndex, OrezOutputField.SOURCE_FINGERPRINT),
            "speechModelSha256" to OrezOutputReference(sourceIndex, OrezOutputField.SPEECH_MODEL_SHA256)) +
            if (providerCaptions) mapOf("captionInventorySha256" to OrezOutputReference(sourceIndex, OrezOutputField.CAPTION_INVENTORY_SHA256)) else emptyMap())

    private fun withTarget(call: OrezToolCall, input: String): OrezToolCall {
        if (call.capability != OrezCapability.TRANSLATION) return call
        val languages = listOf("hi-latn" to listOf("hinglish", "roman hindi", "hindi latin", "romanized hindi", "romanised hindi", "hi-latn"),
            "hi" to listOf("hindi", "हिंदी", "हिन्दी"),
            "en" to listOf("english"), "ja" to listOf("japanese"), "ko" to listOf("korean"),
            "zh" to listOf("chinese"), "fr" to listOf("french"), "es" to listOf("spanish"), "de" to listOf("german"))
        val target = languages.firstOrNull { (_, names) -> names.any { name ->
            Regex("(?:to|into|in|में)\\s+" + Regex.escape(name) + "(?:\\b|$)").containsMatchIn(input) ||
                input.contains(name + " mein")
        } }?.first ?: return call
        return call.copy(arguments = call.arguments + ("targetLanguage" to target))
    }

    private fun task(objective: String, calls: List<OrezToolCall>): OrezTaskPlan =
        OrezTaskPlan(
            objective = objective,
            steps = calls.mapIndexed { index, call -> OrezPlanStep(index = index, call = call) }
        )

    private fun tool(
        name: String,
        capability: OrezCapability,
        risk: OrezToolRisk,
        summary: String,
        route: OrezRoute,
        value: String = ""
    ): OrezToolCall = OrezToolCall(
        name = name,
        capability = capability,
        risk = risk,
        summary = summary,
        route = route,
        arguments = if (value.isBlank()) emptyMap() else mapOf("value" to value)
    )

    private fun wantsTranslation(text: String): Boolean =
        isAny(text, "translate", "translation", "hindi mein", "anuvad", "अनुवाद", "subtitles", "subtitle translate")

    private fun isAny(text: String, vararg needles: String): Boolean =
        needles.any(text::contains)

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    }
}

