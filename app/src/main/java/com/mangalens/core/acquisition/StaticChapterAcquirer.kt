package com.mangalens.core.acquisition

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Fast, bounded HTML pass before creating an expensive rendered browser. */
class StaticChapterAcquirer {
    private val adapter: MangaSourceAdapter = GenericMangaSourceAdapter()
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder().removeHeader("Cookie")
            CookieManager.getInstance().getCookie(request.url.toString())?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            chain.proceed(builder.build())
        }.build()

    suspend fun discover(url: String): SourceContent? = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "MangaLens/1.4 Android").build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) return@use null
                            val body = it.body ?: return@use null
                            if (body.contentLength() > 1_500_000) return@use null
                            if (body.contentType()?.subtype?.contains("html") != true) return@use null
                            val output = java.io.ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(16_384)
                                while (true) {
                                    if (!continuation.isActive) return@use
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    if (output.size() + size > 1_500_000) return@runCatching null
                                    output.write(buffer, 0, size)
                                }
                            }
                            adapter.parse(output.toString("UTF-8"), it.request.url.toString())
                        }
                    }.getOrNull()
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}
