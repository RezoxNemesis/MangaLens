package com.mangalens.core.events

import java.security.MessageDigest
import java.util.Locale

/** No source content or free-form payload crosses this process-local hint boundary. */
enum class AppEventType {
    CHAPTER_LOADED, OCR_LOW_CONFIDENCE, DOWNLOAD_FAILED, DOWNLOAD_COMPLETE,
    MODEL_READY, STREAM_EXPIRED, SUBTITLE_TRACK_CHANGED, MEMORY_PRESSURE
}

class EventIdentity private constructor(val digest: String) {
    override fun equals(other: Any?) = other is EventIdentity && digest == other.digest
    override fun hashCode() = digest.hashCode()
    override fun toString() = digest

    companion object {
        fun task(opaqueId: String) = hash("task", opaqueId)
        fun source(opaqueId: String) = hash("source", opaqueId)
        fun owner(opaqueId: String) = hash("owner", opaqueId)
        private fun hash(domain: String, opaqueId: String): EventIdentity {
            require(opaqueId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))) { "Invalid opaque event identity" }
            val bytes = MessageDigest.getInstance("SHA-256").digest("$domain\u0000$opaqueId".toByteArray(Charsets.UTF_8))
            return EventIdentity(bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) })
        }
    }
}

class ModelEventIdentity private constructor(val sha256: String) {
    override fun equals(other: Any?) = other is ModelEventIdentity && sha256 == other.sha256
    override fun hashCode() = sha256.hashCode()
    override fun toString() = sha256
    companion object {
        fun sha256(pin: String): ModelEventIdentity {
            require(pin.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid verified model hash" }
            return ModelEventIdentity(pin.lowercase(Locale.ROOT))
        }
    }
}

data class TaskEventIdentity(val task: EventIdentity, val source: EventIdentity,
    val owner: EventIdentity, val generation: Long) {
    init { require(generation >= 0) { "Invalid event execution generation" } }
}

enum class MemoryPressureLevel { MODERATE, CRITICAL }

sealed interface AppEvent {
    val type: AppEventType
    data class TaskHint(override val type: AppEventType, val identity: TaskEventIdentity) : AppEvent {
        init { require(type != AppEventType.MODEL_READY && type != AppEventType.MEMORY_PRESSURE) }
    }
    data class ModelReady(val model: ModelEventIdentity, val occurrence: Long = 0) : AppEvent {
        init { require(occurrence >= 0) }
        override val type = AppEventType.MODEL_READY
    }
    data class MemoryPressure(val level: MemoryPressureLevel, val occurrence: Long = 0) : AppEvent {
        init { require(occurrence >= 0) }
        override val type = AppEventType.MEMORY_PRESSURE
    }
}

sealed interface AppEventFilter { fun accepts(event: AppEvent): Boolean }

/** Exact captured ownership, with no identity or generation wildcards. */
class TaskEventFilter(val identity: TaskEventIdentity, kinds: Set<AppEventType>) : AppEventFilter {
    private val accepted = kinds.toSet()
    init {
        require(accepted.isNotEmpty() && accepted.none { it == AppEventType.MODEL_READY || it == AppEventType.MEMORY_PRESSURE })
    }
    override fun accepts(event: AppEvent) = event is AppEvent.TaskHint && event.identity == identity && event.type in accepted
}

class ModelEventFilter(private val capturedModel: ModelEventIdentity) : AppEventFilter {
    override fun accepts(event: AppEvent) = event is AppEvent.ModelReady && event.model == capturedModel
}

/** Shared pressure is explicit resource observation, never a native task receipt. */
data object MemoryEventFilter : AppEventFilter {
    override fun accepts(event: AppEvent) = event is AppEvent.MemoryPressure
}
