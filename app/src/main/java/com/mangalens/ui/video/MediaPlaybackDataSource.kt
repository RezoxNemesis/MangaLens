package com.mangalens.ui.video

import android.webkit.CookieManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Network interceptors run again on redirects, unlike DataSpec-only header resolution. */
@UnstableApi
object MediaPlaybackDataSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    fun factory(
        context: MediaRequestContext,
        baseClient: OkHttpClient = client,
        cookiesForUrl: (String) -> String? = { url -> CookieManager.getInstance().getCookie(url) }
    ): OkHttpDataSource.Factory = OkHttpDataSource.Factory(scopedClient(context, baseClient, cookiesForUrl))

    internal fun scopedClient(context: MediaRequestContext, baseClient: OkHttpClient = client,
        cookiesForUrl: (String) -> String? = { url -> CookieManager.getInstance().getCookie(url) }
    ): OkHttpClient {
        return baseClient.newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val cookie = runCatching { cookiesForUrl(url) }.getOrNull()
            val builder = request.newBuilder()
            // Remove carried headers before every connection, including redirected connections.
            listOf("Cookie", "Cookie2", "Authorization", "Proxy-Authorization", "Referer", "Origin")
                .forEach(builder::removeHeader)
            context.headersFor(url, cookie).forEach { (name, value) -> builder.header(name, value) }
            chain.proceed(builder.build())
        }.build()
    }
}
