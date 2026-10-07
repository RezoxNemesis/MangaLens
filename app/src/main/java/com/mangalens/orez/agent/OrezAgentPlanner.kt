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
        if (clean.isBlank()) return null
        val lower = clean.lowercase(Locale.ROOT)
        val url = URL_REGEX.find(clean)?.value?.trimEnd('.', ',', ')', ']', '!', '?')

        val direct = when {
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
        }
        if (direct != null) return task(clean, listOf(withTarget(direct, lower)))

        if (url == null) return null

        val contentType = urlRouter.classifyUrl(url)
        val download = isAny(lower, "download", "save video", "save media", "download this", "offline copy")
        val translate = wantsTranslation(lower)
        val play = isAny(lower, "play", "watch", "stream", "open video")
        val read = isAny(lower, "read", "open chapter", "reader", "manga")

        val call = when {
            download -> tool(
                name = "open_download_flow",
                capability = OrezCapability.DOWNLOADS,
                risk = OrezToolRisk.NETWORK_READ,
                summary = "Prepare the URL in Download Room",
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

            read || (!play && contentType == ContentType.IMAGE_CHAPTER) -> tool(
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

        return task(clean, listOf(withTarget(call, lower)))
    }

    private fun withTarget(call: OrezToolCall, input: String): OrezToolCall {
        if (call.capability != OrezCapability.TRANSLATION) return call
        val languages = listOf("hi" to listOf("hindi", "हिंदी", "हिन्दी"),
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
