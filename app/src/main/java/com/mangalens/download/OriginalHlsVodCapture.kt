package com.mangalens.download

import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.Locale

/** One existing resolution session captures both selected tracks; partial pairs never escape. */
internal object OriginalHlsVodCapture {
    fun enrich(media: ResolvedMediaLink, session: MediaResolutionSession,
        browserCookie: (String) -> String? = { null }): ResolvedMediaLink {
        val video = media.videoHlsSource
        val audio = media.audioHlsSource
        if (video == null && audio == null) return media
        session.checkActive()
        try {
            require(video != null && audio != null && media.audioUrl != null && media.videoFragments == null && media.audioFragments == null)
            video.validate(); audio.validate()
            require(video.sourceUrl == media.url && audio.sourceUrl == media.audioUrl && video.mediaMime == media.mimeType && audio.mediaMime == media.audioMimeType)
            val selected = requireNotNull(media.originalSelection)
            require(video.formatId == selected.videoFormatId && audio.formatId == selected.audioFormatId)
            val duration = requireNotNull(media.expectedDurationUs).also { require(it in 1..OriginalFragmentPlan.MAX_DURATION_US) }
            val originalVideo = capture(video, media.sourcePageUrl, media.headers, duration, session, browserCookie)
            session.checkActive()
            val originalAudio = capture(audio, media.sourcePageUrl, media.audioHeaders, duration, session, browserCookie)
            session.checkActive()
            return media.copy(videoFragments = originalVideo, audioFragments = originalAudio, videoHlsSource = null, audioHlsSource = null)
                .also { requireBoundMediaSource(it.url, it); session.checkActive() }
        } catch (failure: Exception) {
            session.checkActive()
            if (failure is InterruptedException || failure is java.util.concurrent.CancellationException || failure is OriginalHlsSelectedSourceException) throw failure
            val kind = MediaSourceFailure.from(failure)
            if (kind.kind == MediaSourceFailureKind.TRANSPORT_RESTART_REQUIRED ||
                failure is java.io.IOException && failure !is java.nio.charset.CharacterCodingException)
                throw OriginalHlsSelectedSourceException(kind, failure)
            throw OriginalHlsSelectedSourceException.unsupported(failure)
        }
    }

    private fun capture(source: CapturedHlsTrackSource, page: String?, headers: Map<String, String>, duration: Long,
        session: MediaResolutionSession, browserCookie: (String) -> String?): OriginalFragmentPlan {
        val client = OriginalHlsPublicTransport.client(source.sourceUrl, page, headers, browserCookie)
        val call = client.newCall(Request.Builder().url(source.sourceUrl).header("Accept-Encoding", "identity").build())
        call.timeout().deadlineNanoTime(session.deadlineNanos)
        val cancellation = session.onCancel(call::cancel)
        try {
            call.execute().use { response ->
                session.checkActive()
                if (response.code != 200) throw java.io.IOException("HTTP error ${response.code}")
                require(response.header("Content-Range") == null)
                val encoding = response.header("Content-Encoding").orEmpty()
                require(encoding.isBlank() || encoding.equals("identity", true))
                val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                require(type in OriginalHlsVodPolicy.CONTENT_TYPES)
                val body = requireNotNull(response.body)
                val announced = body.contentLength()
                require(announced <= OriginalHlsVodPolicy.MAX_PLAYLIST_BYTES)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                val input = body.byteStream() // Response owns the sole network close.
                while (true) {
                    session.checkActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, OriginalHlsVodPolicy.MAX_PLAYLIST_BYTES + 1 - output.size()))
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    require(output.size() <= OriginalHlsVodPolicy.MAX_PLAYLIST_BYTES)
                }
                require(announced < 0 || announced == output.size().toLong())
                session.checkActive()
                return OriginalHlsVodPlaylist.capture(source, response.request.url.toString(), type, output.toByteArray(), duration, session::checkActive)
            }
        } finally { cancellation.close() }
    }
}
