package com.mangalens.orez.agent

import com.mangalens.orez.OrezRoute
import java.util.UUID
import java.util.Locale
import java.security.MessageDigest
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.core.translation.TranslationRefinementRequest
import com.mangalens.ui.video.SubtitleOutputMode
import com.mangalens.ui.video.SubtitlePipeline
import com.mangalens.ui.video.SubtitleRefinementPin

enum class OrezTrustOrigin {
    USER,
    APP_STATE,
    WEB_CONTENT,
    IMPORTED_CONTENT
}

enum class OrezToolRisk {
    READ_ONLY,
    LOCAL_MUTATION,
    NETWORK_READ,
    NETWORK_MUTATION,
    ACCOUNT_MUTATION,
    DESTRUCTIVE
}

enum class OrezCapability {
    READER,
    VISION,
    TRANSLATION,
    MEDIA,
    DOWNLOADS,
    WEB,
    LIBRARY,
    SETTINGS,
    RESEARCH
}

enum class OrezTaskStatus {
    PLANNED,
    WAITING_APPROVAL,
    RUNNING,
    WAITING,
    DISPATCHED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class OrezStepStatus {
    PENDING,
    RUNNING,
    DISPATCHED,
    COMPLETED,
    FAILED,
    BLOCKED
}

data class OrezAgentContext(
    val hasActiveChapter: Boolean = false,
    val hasLibrary: Boolean = false,
    val activeUrl: String? = null,
    val origin: OrezTrustOrigin = OrezTrustOrigin.USER,
    val explicitUserRequest: Boolean = true,
    val activeChapterId: String? = null,
    val translationOptions: OrezTranslationOptions = OrezTranslationOptions(),
    val selectedMedia: OrezMediaSelection? = null,
    val subtitleOptions: OrezSubtitleOptions = OrezSubtitleOptions()
)

/** Trusted UI selection, captured once. Tools receive only its opaque identity. */
data class OrezAudioSelection(val uri: String, val resolutionId: String, val headers: Map<String, String> = emptyMap())

data class OrezMediaSelection(
    val uri: String,
    val cacheKey: String = uri,
    val label: String = "Video",
    val headers: Map<String, String> = emptyMap(),
    val resolutionId: String? = null,
    val audio: OrezAudioSelection? = null,
    val expectedDurationUs: Long? = null
) {
    val sourceId: String get() {
        val digest = MessageDigest.getInstance("SHA-256")
        val videoFields = listOf(uri, cacheKey) + headers.toSortedMap().flatMap { listOf(it.key, it.value) }
        val fields = if (resolutionId == null && audio == null && expectedDurationUs == null) videoFields else
            listOf("orez-selected-media-v2", uri, cacheKey, headers.size.toString()) +
                headers.toSortedMap().flatMap { listOf(it.key, it.value) } +
                listOf(resolutionId.orEmpty(), expectedDurationUs?.toString().orEmpty(), if (audio == null) "no-audio" else "audio") +
                (audio?.let { listOf(it.uri, it.resolutionId, it.headers.size.toString()) +
                    it.headers.toSortedMap().flatMap { entry -> listOf(entry.key, entry.value) } }.orEmpty())
        // Length framing prevents header/source delimiters from aliasing a selection.
        for (value in fields) {
            digest.update(value.toByteArray(Charsets.UTF_8).size.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte()); digest.update(value.toByteArray(Charsets.UTF_8))
        }
        return "selected-" + digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }.take(32)
    }
    fun captured() = copy(headers = headers.toMap(), audio = audio?.copy(headers = audio.headers.toMap()))
}

/** Complete captured native policy; a later Settings/model change cannot alter a queued request. */
data class OrezSubtitleOptions(
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "en",
    val style: String = "whisper-english",
    val windowSeconds: Int = 8,
    val overlapSeconds: Int = 1,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    val outputMode: SubtitleOutputMode = SubtitleOutputMode.TRANSLATED,
    val pipeline: SubtitlePipeline = SubtitlePipeline.WHISPER_ENGLISH,
    val customStyle: String = "",
    val localRefinement: Boolean = false,
    val translationPolicy: String = "whisper-english-v1",
    val capturedStyle: TranslationStyleProfile? = null,
    val refinementPin: SubtitleRefinementPin? = null
) {
    fun normalized() = copy(sourceLanguage = sourceLanguage.trim().lowercase(Locale.ROOT),
        targetLanguage = targetLanguage.trim().lowercase(Locale.ROOT).replace('_', '-'), style = style.trim().lowercase(Locale.ROOT),
        customStyle = customStyle.trim(), capturedStyle = capturedStyle ?: if (pipeline == SubtitlePipeline.SOURCE_TRANSLATION)
            if (style.trim().equals("custom", true)) TranslationStyleProfile.custom(customStyle) else TranslationStyleProfile.fromId(style)
        else null)
}

/** Captured at the user request; changing Settings later cannot change queued work. */
data class OrezTranslationOptions(
    val targetLanguage: String = "hi",
    val styleId: String = "natural",
    val customStyle: String = "",
    val ocrScript: String = "AUTO",
    val highAccuracy: Boolean = true,
    val preserveStyle: Boolean = true,
    val localRefinement: Boolean = false,
    val refinementRequest: TranslationRefinementRequest? = null
) {
    fun normalized() = copy(targetLanguage = targetLanguage.trim().lowercase(Locale.ROOT), styleId = styleId.trim().lowercase(Locale.ROOT),
        customStyle = if (styleId.trim().equals("custom", ignoreCase = true)) customStyle.trim() else "", ocrScript = ocrScript.trim().uppercase(Locale.ROOT))
}

/** Only the trusted runtime creates this scope. Models and chapter text cannot grant authority. */
data class OrezTaskAuthorization(
    val origin: OrezTrustOrigin,
    val explicitUserRequest: Boolean,
    val chapterIds: Set<String> = emptySet(),
    val urls: Set<String> = emptySet(),
    val translation: OrezTranslationOptions? = null,
    val selectedMedia: OrezMediaSelection? = null,
    val subtitle: OrezSubtitleOptions? = null
) {
    fun context() = OrezAgentContext(origin = origin, explicitUserRequest = explicitUserRequest)
}

enum class OrezOutputKind { DOWNLOAD_RECEIPT, SAVED_CHAPTER, CHAPTER_TRANSLATION, MEDIA_SOURCE, SUBTITLE_TRACK }
enum class OrezPendingControl { PAUSE, CANCEL }
enum class OrezOutputField(val key: String) {
    CHAPTER_ID("chapterId"), SOURCE_FINGERPRINT("sourceFingerprint"), DOWNLOAD_ID("downloadId"),
    MEDIA_SOURCE_ID("sourceId"), SPEECH_MODEL_SHA256("speechModelSha256")
}
data class OrezOutputReference(val stepIndex: Int, val field: OrezOutputField)

data class OrezToolCall(
    val name: String,
    val capability: OrezCapability,
    val risk: OrezToolRisk,
    val summary: String,
    val arguments: Map<String, String> = emptyMap(),
    val route: OrezRoute? = null
)

data class OrezPlanStep(
    val index: Int,
    val call: OrezToolCall,
    val status: OrezStepStatus = OrezStepStatus.PENDING,
    val outputs: Map<String, String> = emptyMap(),
    val dependsOn: Set<Int> = emptySet(),
    val references: Map<String, OrezOutputReference> = emptyMap(),
    val outputKind: OrezOutputKind? = null
)

data class OrezTaskPlan(
    val id: String = UUID.randomUUID().toString(),
    val objective: String,
    val steps: List<OrezPlanStep>,
    val status: OrezTaskStatus = OrezTaskStatus.PLANNED,
    val createdAt: Long = System.currentTimeMillis(),
    val authorization: OrezTaskAuthorization? = null,
    val executionEpoch: Long = 0,
    val pausedByUser: Boolean = false,
    val resuming: Boolean = false,
    val pendingControl: OrezPendingControl? = null
)

data class OrezAgentDecision(
    val plan: OrezTaskPlan? = null,
    val immediateRoute: OrezRoute? = null,
    val routeValue: String = "",
    val message: String = "",
    val continueToBrain: Boolean = false,
    val requiresApproval: Boolean = false
)

