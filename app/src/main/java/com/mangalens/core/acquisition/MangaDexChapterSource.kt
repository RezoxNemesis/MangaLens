package com.mangalens.core.acquisition

import com.mangalens.core.router.UrlEngineRouter
import org.json.JSONObject
import java.net.URI

/** Public official MangaDex@Home manifest; no DOM/captcha images are chapter pages. */
object MangaDexChapterSource {
    fun endpoint(source: String): String? {
        val uri = runCatching { URI(source) }.getOrNull() ?: return null
        if (uri.host !in setOf("mangadex.org", "www.mangadex.org") || !UrlEngineRouter.isSafeWebUrl(source)) return null
        val chapter = Regex("^/chapter/([a-fA-F0-9-]{36})(?:/.*)?$").find(uri.path)?.groupValues?.get(1) ?: return null
        if (runCatching { java.util.UUID.fromString(chapter) }.isFailure) return null
        return "https://api.mangadex.org/at-home/server/$chapter"
    }
    fun parse(json: String): SourceContent? {
        if (json.length > 1_500_000) return null
        val root = JSONObject(json)
        if (root.optString("result") != "ok") return null
        val base = root.optString("baseUrl").trimEnd('/')
        if (!UrlEngineRouter.isSafeWebUrl(base) || !base.startsWith("https://")) return null
        val chapter = root.optJSONObject("chapter") ?: return null
        val hash = chapter.optString("hash")
        if (!hash.matches(Regex("[a-fA-F0-9]{16,64}"))) return null
        val files = chapter.optJSONArray("data") ?: return null
        val pages = (0 until files.length()).mapNotNull { index ->
            val name = files.optString(index)
            if (name.matches(Regex("[a-zA-Z0-9_.-]+")) && name != "." && name != "..") "$base/data/$hash/$name" else null
        }.distinct()
        return SourceContent("MangaDex chapter", pages, emptyList()).takeIf { pages.isNotEmpty() }
    }
}
