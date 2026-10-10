package com.mangalens.orez

internal enum class OrezGenerationPhase {
    WAIT_OPERATION, VERIFY_MODEL, WAIT_MODEL_MUTEX, WAIT_MODEL_ADMISSION, VERIFY_BEFORE_LOAD,
    MODEL_LOAD, FORMAT_INPUT, WAIT_GENERATION_ADMISSION, VERIFY_BEFORE_GENERATE, NATIVE_GENERATE, COMPLETE
}

/** Each attempt owns its trace. Measured phases never contain prompts or generated dialogue. */
internal data class OrezGenerationAttemptEvidence(
    val profileRevision: String,
    val formattedInputSha256: String?,
    val phase: String,
    val outcome: String,
    val elapsedMs: Long,
    val phaseMs: Map<String, Long>,
    val completion: OrezGenerationCompletion?
)

/** Fixed protocol labels: a saved-bubble explanation cannot be reported as known-v2 localization. */
internal enum class OrezGenerationTraceProfile(val revision: String) {
    LOCALIZATION(OrezLocalizationProfile.REVISION), SAVED_BUBBLE(SavedBubbleOrezProfile.REVISION), BROWSER_AGENT(OrezBrowserAgentProfile.REVISION), IMAGE_OCR(OrezImageOcrProfile.REVISION)
}

internal class OrezGenerationTrace(private val clockMs: () -> Long,
    private val profile: OrezGenerationTraceProfile) {
    constructor(clockMs: () -> Long) : this(clockMs, OrezGenerationTraceProfile.LOCALIZATION)

    private val guard = Any()
    private val started = clockMs()
    private var phase = OrezGenerationPhase.WAIT_OPERATION
    private var phaseStarted = started
    private val durations = linkedMapOf<OrezGenerationPhase, Long>()
    private var inputHash: String? = null
    private var completion: OrezGenerationCompletion? = null

    fun mark(next: OrezGenerationPhase) = synchronized(guard) {
        val now = clockMs()
        durations[phase] = (durations[phase] ?: 0) + (now - phaseStarted).coerceAtLeast(0)
        phase = next
        phaseStarted = now
    }

    fun captureInput(formatted: String) = synchronized(guard) { inputHash = OrezLocalizationProfile.hash(formatted) }
    fun captureCompletion(value: OrezGenerationCompletion) = synchronized(guard) { completion = value }

    fun snapshot(outcome: String): OrezGenerationAttemptEvidence = synchronized(guard) {
        val now = clockMs()
        val measured = durations.mapKeys { it.key.name }.toMutableMap()
        measured[phase.name] = (measured[phase.name] ?: 0) + (now - phaseStarted).coerceAtLeast(0)
        OrezGenerationAttemptEvidence(profile.revision, inputHash, phase.name, outcome,
            (now - started).coerceAtLeast(0), measured.toMap(), completion)
    }
}
