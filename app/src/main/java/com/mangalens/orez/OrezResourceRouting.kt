package com.mangalens.orez

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Resource preference for new requests. It does not change a captured model or a native profile. */
enum class OrezResourceMode(val label: String) { FAST("Fast"), BALANCED("Balanced"), MAXIMUM("Maximum") }

/** Trusted application call-site hints; model output and source content cannot select these. */
enum class OrezModelTask {
    CHAT, REWRITE, REASONING, LOCALIZATION, CHAPTER_REASONING, LIBRARY_REASONING, EVIDENCE_SYNTHESIS,
    TOOL_PLANNING, BROWSER_GOAL, SAVED_BUBBLE;

    internal val contextual: Boolean get() = this !in setOf(CHAT, REWRITE)
}

internal enum class OrezRoutingPressure { NORMAL, ELEVATED, CRITICAL }

/** Captured before model verification/admission, and inherited across the request's fallback attempts. */
internal class OrezRequestResources(
    val mode: OrezResourceMode,
    val task: OrezModelTask,
    val pressure: OrezRoutingPressure
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OrezRequestResources>
    fun forTask(task: OrezModelTask) = OrezRequestResources(mode, task, pressure)
}

internal object OrezResourceRouting {
    fun parseMode(value: String?): OrezResourceMode = OrezResourceMode.entries.firstOrNull { it.name == value }
        ?: OrezResourceMode.BALANCED

    /**
     * Adequacy here is a provisional task policy, not an empirical quality qualification.
     * Every candidate must already be process-verified. Actual memory/native gates remain mandatory.
     */
    fun order(pin: OrezModelPin?, selectedOrder: List<OrezModelCandidate>,
        available: List<OrezModelCandidate>, request: OrezRequestResources): List<OrezModelCandidate> {
        if (pin != null) return OrezPinnedModelPolicy.select(pin, available)
        val verified = OrezPinnedModelPolicy.select(null, available)
        val selected = OrezPinnedModelPolicy.select(null, selectedOrder).filter { it in verified }
        // Elevated/critical observations select lighter candidates only for new unpinned requests.
        // The final native governor still refuses critical execution, including after waiting.
        val eligible = if (request.mode == OrezResourceMode.BALANCED) selected else verified
        if (request.pressure != OrezRoutingPressure.NORMAL || request.mode == OrezResourceMode.FAST ||
            (request.mode == OrezResourceMode.MAXIMUM && !request.task.contextual))
            return eligible.sortedBy { it.pin.bytes }
        if (request.mode == OrezResourceMode.BALANCED) return selected
        // Maximum uses only the existing production Core descriptor, never an unqualified Max.
        return verified.sortedWith(compareBy<OrezModelCandidate> {
            if (it.pin.modelId == OrezModelCatalog.core.id && it.pin.sha256 == OrezModelCatalog.core.sha256 &&
                it.pin.bytes == OrezModelCatalog.core.bytes) 0 else 1
        }.thenBy { it.pin.bytes })
    }
}

internal enum class OrezLoadedModelKind { IDLE, VERIFIED_PIN, UNMAPPED }

/** Observation of the physical shared lease, not selected tier, readiness or model quality. */
internal data class OrezLoadedModelObservation(
    val kind: OrezLoadedModelKind,
    val sharedPath: String?,
    val pin: OrezModelPin? = null
)

internal object OrezLoadedModelPolicy {
    fun observe(path: String?, verifiedPaths: List<Pair<String, OrezModelPin>>): OrezLoadedModelObservation {
        if (path == null) return OrezLoadedModelObservation(OrezLoadedModelKind.IDLE, null)
        val pins = verifiedPaths.filter { it.first == path && OrezPinnedModelPolicy.valid(it.second) }
            .map { it.second }.distinct()
        return if (pins.size == 1) OrezLoadedModelObservation(OrezLoadedModelKind.VERIFIED_PIN, path, pins.single())
        else OrezLoadedModelObservation(OrezLoadedModelKind.UNMAPPED, path)
    }
    fun stillCurrent(observation: OrezLoadedModelObservation, currentPath: String?): Boolean =
        observation.sharedPath == currentPath
}
