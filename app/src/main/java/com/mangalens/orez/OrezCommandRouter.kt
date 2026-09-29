package com.mangalens.orez

import com.mangalens.core.model.ContentType
import com.mangalens.core.router.UrlEngineRouter

enum class OrezRoute {
    CHAT, MANGA_READER, VIDEO_PLAYER, WEB_VIEW,
    TRANSLATE_MANGA, TRANSLATE_VIDEO, TRANSLATE_WEB
}

data class OrezCommandResult(
    val route: OrezRoute,
    val originalInput: String,
    val contentType: ContentType? = null
)

class OrezCommandRouter(private val urlRouter: UrlEngineRouter = UrlEngineRouter()) {
    fun route(input: String): OrezCommandResult {
        val value = input.trim()
        val url = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
            .find(value)?.value?.trimEnd('.', ',', ')', ']', '!', '?')
        val lower = value.lowercase()
        val translate = listOf("translate", "translation", "hindi mein", "anuvad", "subtitles", "subtitle translate")
            .any { lower.contains(it) }

        if (url != null) {
            val type = urlRouter.classifyUrl(url)
            val route = when {
                translate && type == ContentType.IMAGE_CHAPTER -> OrezRoute.TRANSLATE_MANGA
                translate && type == ContentType.VIDEO_STREAM -> OrezRoute.TRANSLATE_VIDEO
                translate && type == ContentType.GENERIC_WEB -> OrezRoute.TRANSLATE_WEB
                type == ContentType.IMAGE_CHAPTER -> OrezRoute.MANGA_READER
                type == ContentType.VIDEO_STREAM -> OrezRoute.VIDEO_PLAYER
                else -> OrezRoute.WEB_VIEW
            }
            return OrezCommandResult(route, url, type)
        }
        return OrezCommandResult(OrezRoute.CHAT, value)
    }
}
