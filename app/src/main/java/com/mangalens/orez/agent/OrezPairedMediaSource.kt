package com.mangalens.orez.agent

import com.mangalens.ui.video.SubtitleMediaSource
import com.mangalens.ui.video.validate
import java.net.URI
import java.util.Locale

/** Private source authority comes from one captured UI resolution, never the prompt or native cache key alone. */
internal fun OrezMediaSelection.validateCapturedSource() {
    fun uri(value: String) = value.length in 1..16_000 && value.none(Char::isISOControl) &&
        runCatching { URI(value).scheme?.lowercase(Locale.ROOT) in setOf("http", "https", "content", "file", "android.resource") }.getOrDefault(false)
    fun headers(values: Map<String, String>) = values.size <= 32 && values.all { (key, value) ->
        key.length in 1..128 && value.length <= 8192 && key.none(Char::isISOControl) && value.none(Char::isISOControl)
    }
    require(uri(this.uri) && cacheKey.length in 1..16_000 && label.length <= 250 &&
        cacheKey.none(Char::isISOControl) && label.none(Char::isISOControl) && headers(this.headers)) { "The explicit playable source or headers are invalid." }
    resolutionId?.let { require(it.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "The captured source resolution identity is invalid." } }
    expectedDurationUs?.let { require(it in 1..21_600_000_000L) { "The captured source duration is invalid." } }
    providerCaptions?.let {
        require(resolutionId?.matches(Regex("[a-f0-9]{32}")) == true) { "Provider captions require one accepted video resolution." }
        it.validate()
    }
    videoFragments?.let { plan ->
        plan.validate()
        require(plan.sourceUrl == this.uri && resolutionId?.matches(Regex("[a-f0-9]{32}")) == true && expectedDurationUs != null &&
            kotlin.math.abs(plan.durationUs - expectedDurationUs) <= 1_500_000L) { "Video fragments must belong to the captured ready selection." }
    }
    audioFragments?.let { plan ->
        plan.validate()
        require(audio != null && plan.sourceUrl == audio.uri && resolutionId?.matches(Regex("[a-f0-9]{32}")) == true && expectedDurationUs != null &&
            kotlin.math.abs(plan.durationUs - expectedDurationUs) <= 1_500_000L) { "Audio fragments must belong to the captured selected audio track." }
    }
    audio?.let { track ->
        require(resolutionId != null && track.resolutionId == resolutionId && uri(track.uri) && headers(track.headers)) {
            "The audio track must belong to the same captured playable resolution."
        }
        require(expectedDurationUs != null) { "A split audio/video selection needs its resolver duration. Select a verified saved video if it is unavailable." }
    }
}

internal fun OrezMediaSelection.nativeSubtitleSource(): SubtitleMediaSource {
    validateCapturedSource()
    val track = audio
    return if (track == null) SubtitleMediaSource(uri, headers.toMap(), cacheKey, label, providerCaptions?.captureSnapshot(), resolutionId.takeIf { providerCaptions != null || videoFragments != null }, fragmentPlan = videoFragments?.captured())
    else SubtitleMediaSource(track.uri, track.headers.toMap(), "orez-audio-pair:$sourceId", label, providerCaptions?.captureSnapshot(), resolutionId.takeIf { providerCaptions != null || audioFragments != null }, fragmentPlan = audioFragments?.captured())
}

internal fun OrezMediaSelection.verifySubtitleTail(durationMs: Long, processedMs: Long) {
    val expected = expectedDurationUs?.let { (it + 999) / 1000 } ?: return
    require(durationMs > 0 && durationMs + 1500 >= expected && durationMs <= expected + 1500 && processedMs + 1500 >= expected) {
        "The verified audio does not cover the captured video duration. Select a complete saved video."
    }
}

internal fun OrezMediaSelection.hasExtendedScope() = resolutionId != null || audio != null || expectedDurationUs != null || providerCaptions != null || videoFragments != null || audioFragments != null

/** Captured inventory admission is distinct from a fetched and verified completed caption document. */
internal fun OrezMediaSelection.hasProviderCaptionCandidate(): Boolean = providerCaptions != null &&
    runCatching { validateCapturedSource(); true }.getOrDefault(false)

/** Admission for an exact finite descriptor is distinct from completed encoded-byte proof. */
internal fun OrezMediaSelection.hasFragmentSourceCandidate(): Boolean = (if (audio != null) audioFragments else videoFragments) != null &&
    runCatching { validateCapturedSource(); true }.getOrDefault(false)
