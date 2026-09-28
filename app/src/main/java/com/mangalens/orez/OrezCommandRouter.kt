package com.mangalens.orez

import com.mangalens.core.model.ContentType
import com.mangalens.core.router.UrlEngineRouter

enum class OrezRoute { CHAT, MANGA_READER, VIDEO_PLAYER, WEB_VIEW }

data class OrezCommandResult(val route: OrezRoute, val originalInput: String, val contentType: ContentType? = null)

class OrezCommandRouter(private val urlRouter: UrlEngineRouter = UrlEngineRouter()) {
    fun route(input: String): OrezCommandResult {
        val value = input.trim()
        if (!value.startsWith("http://") && !value.startsWith("https://")) return OrezCommandResult(OrezRoute.CHAT, value)
        return when (val type = urlRouter.classifyUrl(value)) {
            ContentType.IMAGE_CHAPTER -> OrezCommandResult(OrezRoute.MANGA_READER, value, type)
            ContentType.VIDEO_STREAM -> OrezCommandResult(OrezRoute.VIDEO_PLAYER, value, type)
            ContentType.GENERIC_WEB -> OrezCommandResult(OrezRoute.WEB_VIEW, value, type)
        }
    }
}