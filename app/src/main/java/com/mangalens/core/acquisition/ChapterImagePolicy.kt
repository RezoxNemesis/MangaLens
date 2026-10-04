package com.mangalens.core.acquisition

import com.mangalens.core.router.UrlEngineRouter
import java.net.URI

/** Only explicit non-content markers are rejected; source access checks remain in Web. */
object ChapterImagePolicy {
    private val nonPage = Regex("(?i)(?:^|[/_.\\s-])(captcha|recaptcha|hcaptcha|turnstile|spinner|placeholder|loading|logo|avatar|banner|advertisement)(?:$|[/_.\\s-])")
    fun accepts(url: String, description: String = ""): Boolean {
        if (!UrlEngineRouter.isSafeWebUrl(url)) return false
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return !nonPage.containsMatchIn(path) && !nonPage.containsMatchIn(description)
    }
}
