package com.mangalens.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI

class BrowserHeadersInterceptor(
    private val userAgent: String = DEFAULT_USER_AGENT
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val referer = runCatching {
            val uri = URI(request.url.toString())
            if (uri.host.isNullOrBlank()) null
            else uri.scheme + "://" + uri.host + "/"
        }.getOrNull()

        val builder = request.newBuilder()
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("DNT", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")

        if (referer != null) builder.header("Referer", referer)
        return chain.proceed(builder.build())
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 MangaLens/12"
    }
}

object MangaLensHttpClientFactory {
    fun create(base: okhttp3.OkHttpClient? = null): okhttp3.OkHttpClient =
        (base ?: okhttp3.OkHttpClient.Builder().build())
            .newBuilder()
            .addInterceptor(BrowserHeadersInterceptor())
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
}

/*
 * Anti-bot / Cloudflare challenges are not bypassed here.
 * TLS verification remains enabled and protected pages use the existing
 * user verification surface.
 */
