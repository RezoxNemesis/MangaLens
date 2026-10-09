package com.mangalens.ui.video

import android.webkit.CookieManager
import com.mangalens.download.ProviderCaptionDiscovery
import com.mangalens.download.ProviderCaptionInventory
import com.mangalens.download.ProviderCaptionTrack
import com.mangalens.download.ProviderCaptionUrlPolicy
import com.mangalens.download.MediaResolutionRunner
import com.mangalens.download.MediaResolutionSession
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal class ProviderCaptionUnavailable : IOException("Original provider captions are unavailable. Original-speech recognition remains available with an installed Whisper model.")
internal data class FetchedProviderCaptions(val track: ProviderCaptionTrack, val document: ProviderCaptionDocument)

/** Queue time, scoped cookie lookup, HTTP, parsing, and cleanup share one tracked deadline. */
internal class ProviderCaptionFetcher(
    private val calls: Call.Factory = client,
    private val cookieForUrl: (String) -> String? = { CookieManager.getInstance().getCookie(it) },
    private val timeoutMs: Long = 20_000L
) {
    init { require(timeoutMs in 1L..20_000L) }
    suspend fun fetch(inventory: ProviderCaptionInventory, sourceLanguage: String,
        saved: ProviderCaptionReceipt? = null): FetchedProviderCaptions = MediaResolutionRunner.run(timeoutMs) { session ->
        blocking(inventory.captureSnapshot(), sourceLanguage, saved, session)
    }
    private fun blocking(inventory: ProviderCaptionInventory, sourceLanguage: String, saved: ProviderCaptionReceipt?,
        session: MediaResolutionSession): FetchedProviderCaptions {
        session.checkActive()
        inventory.validate()
        val candidates = if (saved == null) ProviderCaptionDiscovery.candidates(inventory, sourceLanguage).take(3)
            else listOfNotNull(saved.selectedTrack(inventory))
        for (track in candidates) {
            try {
                var url = track.url
                for (hop in 0..3) {
                    session.checkActive()
                    require(ProviderCaptionUrlPolicy.accepts(inventory.sourcePageUrl, inventory.videoId, url, track.language))
                    val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                        .header("User-Agent", USER_AGENT).header("Accept", "text/vtt, application/x-subrip, application/json, text/plain")
                    cookieForUrl(url)?.takeIf { it.length <= 16_384 && it.none(Char::isISOControl) }?.let { request.header("Cookie", it) }
                    session.checkActive()
                    val call = calls.newCall(request.build())
                    session.onCancel(call::cancel).use {
                    session.checkActive()
                    call.execute().use { response ->
                        session.checkActive()
                        require(response.request.url.toString() == url) { "Unscoped caption redirects are unsupported." }
                        if (response.code in setOf(301,302,303,307,308)) {
                            require(hop < 3)
                            url = requireNotNull(url.toHttpUrlOrNull()?.resolve(requireNotNull(response.header("Location")))).toString()
                        } else {
                            if (response.code in setOf(404, 410)) throw IOException("The captured provider caption format is unavailable.")
                            if (!response.isSuccessful) throw ProviderCaptionUnavailable()
                            val body = response.body ?: throw IOException("The captured provider caption format has no body.")
                            require(body.contentLength() <= ImportedCaptionFile.MAX_BYTES)
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            body.byteStream().use { input -> while (true) {
                                session.checkActive()
                                val count = input.read(buffer); if (count < 0) break
                                if (count == 0) continue
                                require(bytes.size() + count <= ImportedCaptionFile.MAX_BYTES)
                                bytes.write(buffer, 0, count)
                            } }
                            session.checkActive()
                            return FetchedProviderCaptions(track, ProviderCaptionParser.parse(bytes.toByteArray(), track.format, inventory.expectedDurationMs))
                        }
                    }
                    }
                }
            } catch (denied: ProviderCaptionUnavailable) { session.checkActive(); throw denied }
            catch (_: IOException) { session.checkActive() }
            catch (_: IllegalArgumentException) { session.checkActive() }
        }
        throw ProviderCaptionUnavailable()
    }
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 MangaLens/13"
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }
}
