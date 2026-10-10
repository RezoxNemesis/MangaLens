package com.mangalens.orez

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

internal enum class OrezEvaluationTask { CHAT, LOCALIZATION, TOOL_PLANNING, SAVED_BUBBLE }
internal enum class OrezEvaluationOutcome { PASS, FAIL, CANCELLED }
internal enum class OrezEvaluationState { INVALID, PENDING, OBSERVED_FAILURE, QUALIFIED_FOR_SCOPE }
internal enum class OrezNativeSettingsObservation { COMPLETE, NOT_OBSERVED }

/** Exact binding names; display names, URLs and model/page instructions grant no evaluation scope. */
internal enum class OrezEvaluationField {
    PUBLISHER_REVISION, LICENSE_SHA256, PUBLISHER_METADATA_SHA256,
    RUNTIME_SOURCE_REVISION, RUNTIME_LICENSE_SHA256, NATIVE_BINARY_SHA256, TENSOR_RECEIPT_SHA256,
    APP_SOURCE_REVISION, BUILD_INPUT_SHA256, APK_SHA256, APP_VERSION_CODE, GOVERNANCE_SCHEMA_VERSION,
    TASK_KIND, INPUT_PROFILE, PROFILE_SHA256, PROTOCOL_SHA256, RUBRIC_SHA256,
    CORPUS_ID, CORPUS_REVISION, CORPUS_SHA256, CORPUS_LICENSE_SHA256, HELD_OUT_SHA256, SOURCE_CASES_SHA256, PROMPT_CASES_SHA256,
    NATIVE_ABI, DEVICE_CLASS, DEVICE_KIND, SDK_LEVEL, MEASUREMENT_CAPABILITIES, DEVICE_CAPABILITIES_SHA256, TOTAL_RAM_BYTES, FREE_STORAGE_BYTES,
    TARGET_LANGUAGE, STYLE, SETTINGS_SHA256, BUDGET_MS
}

internal class OrezEvaluationBinding(val model: OrezModelPin, fields: Map<OrezEvaluationField, String>) {
    private val values = fields.toMap()
    operator fun get(field: OrezEvaluationField): String = values[field].orEmpty()
    internal fun complete(): Boolean = values.keys == OrezEvaluationField.entries.toSet()
    internal fun with(field: OrezEvaluationField, value: String) = OrezEvaluationBinding(model, values + (field to value))
    internal fun withModel(pin: OrezModelPin) = OrezEvaluationBinding(pin, values)
    override fun equals(other: Any?): Boolean = other is OrezEvaluationBinding && model == other.model && values == other.values
    override fun hashCode(): Int = 31 * model.hashCode() + values.hashCode()
}

internal data class OrezEvaluationProfile(
    val id: String,
    val inputProfileRevision: String,
    val runtimeSourceRevision: String,
    val minimumApp: OrezMinimumApp,
    val governanceSchemaVersion: Int,
    val task: OrezEvaluationTask
)

internal data class OrezEvaluationCase(
    val id: String,
    val sourceSha256: String,
    val formattedInputSha256: String,
    val outcome: OrezEvaluationOutcome,
    val termination: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val tokenLimit: Int,
    val contextTokens: Int,
    val threads: Int,
    val batch: Int,
    val microBatch: Int,
    val sampler: String,
    val elapsedMs: Long,
    val peakResidentBytes: Long,
    val observation: OrezNativeSettingsObservation
)

internal data class OrezEvaluationExpectedCase(val id: String, val sourceSha256: String, val formattedInputSha256: String)
internal class OrezEvaluationExpectation(val binding: OrezEvaluationBinding, cases: List<OrezEvaluationExpectedCase>) {
    val cases: List<OrezEvaluationExpectedCase> = Collections.unmodifiableList(cases.take(OrezModelEvaluationValidator.MAX_CASES + 1))
    fun copy(binding: OrezEvaluationBinding = this.binding, cases: List<OrezEvaluationExpectedCase> = this.cases) = OrezEvaluationExpectation(binding, cases)
}

internal enum class OrezEvaluationArtifactRole {
    PUBLISHER_METADATA, LICENSE, RUNTIME_LICENSE, TENSOR_RECEIPT, DEVICE_CAPABILITIES, PROTOCOL, RUBRIC,
    CORPUS_LICENSE, CORPUS_MANIFEST, HELD_OUT_MANIFEST, BUILD_INPUT_MANIFEST,
    SOURCE, PROMPT, OUTPUT, ERROR, JUDGMENT, MEASUREMENT
}
internal data class OrezEvaluationArtifactKey(val caseId: String, val role: OrezEvaluationArtifactRole)
internal data class OrezEvaluationArtifact(val key: OrezEvaluationArtifactKey, val bytes: Long, val sha256: String)
internal class OrezEvaluationReceipt(
    val schemaVersion: Int,
    val binding: OrezEvaluationBinding,
    val profile: OrezEvaluationProfile,
    cases: List<OrezEvaluationCase>,
    artifacts: List<OrezEvaluationArtifact>,
    val artifactRootSha256: String,
    val passedCases: Int,
    val failedCases: Int,
    val cancelledCases: Int,
    val smoke: Boolean = false
) {
    // Keep one over-limit sentinel so malformed oversized inventories remain Invalid, without
    // copying unbounded caller collections. Accepted inventories have no mutable caller aliases.
    val cases: List<OrezEvaluationCase> = Collections.unmodifiableList(cases.take(OrezModelEvaluationValidator.MAX_CASES + 1))
    val artifacts: List<OrezEvaluationArtifact> = Collections.unmodifiableList(artifacts.take(OrezModelEvaluationValidator.MAX_ARTIFACTS + 1))
    fun copy(schemaVersion: Int = this.schemaVersion, binding: OrezEvaluationBinding = this.binding,
        profile: OrezEvaluationProfile = this.profile, cases: List<OrezEvaluationCase> = this.cases,
        artifacts: List<OrezEvaluationArtifact> = this.artifacts, artifactRootSha256: String = this.artifactRootSha256,
        passedCases: Int = this.passedCases, failedCases: Int = this.failedCases, cancelledCases: Int = this.cancelledCases,
        smoke: Boolean = this.smoke) = OrezEvaluationReceipt(schemaVersion, binding, profile, cases, artifacts,
        artifactRootSha256, passedCases, failedCases, cancelledCases, smoke)
}

/** App-authored reviewed registrations only. The production default deliberately has no entries. */
internal class OrezEvaluationReviewRegistry(
    reviewedResults: Map<String, OrezEvaluationOutcome> = emptyMap(),
    passingApprovals: Set<String> = emptySet()
) {
    private val reviewed = reviewedResults.toMap()
    private val approvals = passingApprovals.toSet()
    internal fun reviewed(digest: String): OrezEvaluationOutcome? = reviewed[digest]
    internal fun approved(digest: String): Boolean = digest in approvals
}

/** A status projection, never a readiness, native-entry, measured-admission or activation token. */
internal data class OrezEvaluationValidation(val state: OrezEvaluationState, val reason: String)

/** Canonical schema1 commitments. Strings are length-prefixed UTF-8; maps/cases have frozen order. */
internal object OrezEvaluationCanonical {
    const val SCOPE_ID = "@scope"
    val globalBindings: Map<OrezEvaluationArtifactRole, OrezEvaluationField> = Collections.unmodifiableMap(mapOf(
        OrezEvaluationArtifactRole.PUBLISHER_METADATA to OrezEvaluationField.PUBLISHER_METADATA_SHA256,
        OrezEvaluationArtifactRole.LICENSE to OrezEvaluationField.LICENSE_SHA256,
        OrezEvaluationArtifactRole.RUNTIME_LICENSE to OrezEvaluationField.RUNTIME_LICENSE_SHA256,
        OrezEvaluationArtifactRole.TENSOR_RECEIPT to OrezEvaluationField.TENSOR_RECEIPT_SHA256,
        OrezEvaluationArtifactRole.DEVICE_CAPABILITIES to OrezEvaluationField.DEVICE_CAPABILITIES_SHA256,
        OrezEvaluationArtifactRole.PROTOCOL to OrezEvaluationField.PROTOCOL_SHA256,
        OrezEvaluationArtifactRole.RUBRIC to OrezEvaluationField.RUBRIC_SHA256,
        OrezEvaluationArtifactRole.CORPUS_LICENSE to OrezEvaluationField.CORPUS_LICENSE_SHA256,
        OrezEvaluationArtifactRole.CORPUS_MANIFEST to OrezEvaluationField.CORPUS_SHA256,
        OrezEvaluationArtifactRole.HELD_OUT_MANIFEST to OrezEvaluationField.HELD_OUT_SHA256,
        OrezEvaluationArtifactRole.BUILD_INPUT_MANIFEST to OrezEvaluationField.BUILD_INPUT_SHA256))

    fun sha256(raw: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(raw)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { raw ->
        DataOutputStream(raw).use { it.block() }; raw.toByteArray()
    }
    private fun DataOutputStream.text(value: String) {
        val raw = value.toByteArray(Charsets.UTF_8); writeInt(raw.size); write(raw)
    }
    private fun DataOutputStream.binding(value: OrezEvaluationBinding) {
        text(value.model.modelId); text(value.model.sha256); writeLong(value.model.bytes)
        for (field in OrezEvaluationField.entries) { text(field.name); text(value[field]) }
    }
    private fun DataOutputStream.profile(value: OrezEvaluationProfile) {
        text(value.id); text(value.inputProfileRevision); text(value.runtimeSourceRevision)
        when (val minimum = value.minimumApp) {
            OrezMinimumApp.NotDeclared -> writeInt(0)
            is OrezMinimumApp.Declared -> { writeInt(1); writeInt(minimum.minimumVersionCode) }
        }
        writeInt(value.governanceSchemaVersion); text(value.task.name)
    }
    private fun DataOutputStream.settings(value: OrezEvaluationCase) {
        text(value.id); text(value.observation.name); writeInt(value.promptTokens); writeInt(value.generatedTokens); writeInt(value.tokenLimit)
        writeInt(value.contextTokens); writeInt(value.threads); writeInt(value.batch); writeInt(value.microBatch); text(value.sampler)
    }
    fun judgment(value: OrezEvaluationCase): ByteArray = bytes { text("judgment-v1"); text(value.id); text(value.outcome.name) }
    fun measurement(value: OrezEvaluationCase): ByteArray = bytes {
        text("measurement-v1"); settings(value); text(value.termination); writeLong(value.elapsedMs); writeLong(value.peakResidentBytes)
    }
    fun profileSha256(value: OrezEvaluationProfile): String = sha256(bytes { text("profile-v1"); profile(value) })
    fun settingsSha256(cases: List<OrezEvaluationCase>): String = sha256(bytes {
        text("observed-settings-v1"); writeInt(cases.size); cases.sortedBy { it.id }.forEach { settings(it) }
    })
    fun sourceCasesSha256(cases: List<OrezEvaluationCase>): String = sha256(bytes {
        text("source-cases-v1"); writeInt(cases.size); cases.sortedBy { it.id }.forEach { text(it.id); text(it.sourceSha256) }
    })
    fun promptCasesSha256(cases: List<OrezEvaluationCase>): String = sha256(bytes {
        text("prompt-cases-v1"); writeInt(cases.size); cases.sortedBy { it.id }.forEach { text(it.id); text(it.formattedInputSha256) }
    })
    fun corpusManifest(cases: List<OrezEvaluationCase>, corpusId: String, corpusRevision: String, licenseSha256: String): ByteArray = bytes {
        text("licensed-corpus-manifest-v1"); text(corpusId); text(corpusRevision); text(licenseSha256)
        writeInt(cases.size); cases.sortedBy { it.id }.forEach { text(it.id); text(it.sourceSha256) }
    }
    fun heldOutManifest(cases: List<OrezEvaluationCase>, rubricSha256: String): ByteArray = bytes {
        text("held-out-manifest-v1"); text(rubricSha256); writeInt(cases.size)
        cases.sortedBy { it.id }.forEach { text(it.id); text(it.sourceSha256); text(it.formattedInputSha256) }
    }
    fun deviceCapabilities(deviceClass: String, kind: String, abi: String, sdk: Int, totalRam: Long,
        freeStorage: Long, capabilities: String): ByteArray = bytes {
        text("observed-device-capabilities-v1"); text(deviceClass); text(kind); text(abi); writeInt(sdk)
        writeLong(totalRam); writeLong(freeStorage); text(capabilities)
    }
    internal fun manifest(artifacts: List<OrezEvaluationArtifact>): ByteArray = bytes {
        text("artifact-manifest-v1"); writeInt(artifacts.size)
        artifacts.sortedWith(compareBy<OrezEvaluationArtifact> { it.key.caseId }.thenBy { it.key.role.name }).forEach {
            text(it.key.caseId); text(it.key.role.name); writeLong(it.bytes); text(it.sha256)
        }
    }
    fun artifactRootSha256(artifacts: List<OrezEvaluationArtifact>): String = sha256(manifest(artifacts))
    internal fun receipt(value: OrezEvaluationReceipt): ByteArray = bytes {
        text("evaluation-receipt-v1"); writeInt(value.schemaVersion); binding(value.binding); profile(value.profile)
        writeBoolean(value.smoke); writeInt(value.passedCases); writeInt(value.failedCases); writeInt(value.cancelledCases)
        writeInt(value.cases.size)
        value.cases.sortedBy { it.id }.forEach { text(it.id); text(it.sourceSha256); text(it.formattedInputSha256); text(it.outcome.name); write(measurement(it)) }
        text(value.artifactRootSha256)
    }
    fun receiptSha256(value: OrezEvaluationReceipt): String = sha256(receipt(value))
}

/**
 * Pure bounded text-evaluation evidence validation. IO loaders run off Main and supply actual
 * bytes; this validator makes no file, URL, native or tool call. No report import/UI or evaluator
 * is invented here. Missing evidence/trust remains pending, including all production defaults.
 */
internal class OrezModelEvaluationValidator(private val reviews: OrezEvaluationReviewRegistry = OrezEvaluationReviewRegistry()) {
    companion object {
        const val MAX_CASES = 128
        const val MAX_ARTIFACTS = 1024
        const val MAX_BODY_BYTES = 1024 * 1024
        const val MAX_TOTAL_BODY_BYTES = 16 * 1024 * 1024
        const val MAX_RECEIPT_BYTES = 256 * 1024
        private val hash = Regex("[a-f0-9]{64}")
        private val revision = Regex("[a-f0-9]{40}")
        private val id = Regex("[A-Za-z0-9._:-]{1,128}")
        private val hashes = OrezEvaluationField.entries.filter { it.name.endsWith("SHA256") }.toSet()
        private val revisions = setOf(OrezEvaluationField.PUBLISHER_REVISION, OrezEvaluationField.RUNTIME_SOURCE_REVISION, OrezEvaluationField.APP_SOURCE_REVISION)
        private val numbers = setOf(OrezEvaluationField.APP_VERSION_CODE, OrezEvaluationField.GOVERNANCE_SCHEMA_VERSION,
            OrezEvaluationField.SDK_LEVEL, OrezEvaluationField.TOTAL_RAM_BYTES, OrezEvaluationField.FREE_STORAGE_BYTES, OrezEvaluationField.BUDGET_MS)
    }
    private fun invalid(reason: String) = OrezEvaluationValidation(OrezEvaluationState.INVALID, reason)
    private fun pending(reason: String) = OrezEvaluationValidation(OrezEvaluationState.PENDING, reason)

    fun validate(receipt: OrezEvaluationReceipt?, bodies: Map<OrezEvaluationArtifactKey, ByteArray>,
        expected: OrezEvaluationExpectation): OrezEvaluationValidation {
        if (receipt == null) return pending("Report missing")
        // Check cheap declared caps before allocating copies, canonical buffers or hashing bodies.
        if (receipt.schemaVersion != 1 || receipt.cases.size !in 1..MAX_CASES ||
            receipt.artifacts.size !in 1..MAX_ARTIFACTS || bodies.size > MAX_ARTIFACTS) return invalid("Unsupported schema or inventory bounds")
        val cases = receipt.cases.toList()
        val artifacts = receipt.artifacts.toList()
        val captured = receipt.copy(cases = cases, artifacts = artifacts)
        val binding = captured.binding
        if (!binding.complete() || !OrezPinnedModelPolicy.valid(binding.model)) return invalid("Incomplete model or scope binding")
        for (field in OrezEvaluationField.entries) {
            val value = binding[field]
            val valid = when (field) {
                in hashes -> value.matches(hash)
                in revisions -> value.matches(revision)
                in numbers -> value.length in 1..20 && value.all { it in '0'..'9' } && value.toLongOrNull()?.let { it >= 0 } == true
                else -> value.matches(id)
            }
            if (!valid) return invalid("Malformed ${field.name} binding")
        }
        val appVersion = binding[OrezEvaluationField.APP_VERSION_CODE].toLong()
        val schema = binding[OrezEvaluationField.GOVERNANCE_SCHEMA_VERSION].toLong()
        val budget = binding[OrezEvaluationField.BUDGET_MS].toLong()
        val sdk = binding[OrezEvaluationField.SDK_LEVEL].toLong()
        if (appVersion !in 1..Int.MAX_VALUE.toLong() || schema !in 1..Int.MAX_VALUE.toLong() || budget !in 1..120_000 ||
            sdk !in 26..Int.MAX_VALUE.toLong() ||
            binding[OrezEvaluationField.TOTAL_RAM_BYTES].toLong() < 1 ||
            binding[OrezEvaluationField.NATIVE_ABI] !in setOf("arm64-v8a", "x86_64") ||
            binding[OrezEvaluationField.DEVICE_KIND] !in setOf("EMULATOR", "PHYSICAL") ||
            OrezEvaluationTask.entries.none { it.name == binding[OrezEvaluationField.TASK_KIND] }) return invalid("Contradictory app/runtime/device bounds")
        val profile = captured.profile
        if (!profile.id.matches(id) || !profile.inputProfileRevision.matches(id) || !profile.runtimeSourceRevision.matches(revision) ||
            profile.governanceSchemaVersion < 1 || (profile.minimumApp is OrezMinimumApp.Declared && profile.minimumApp.minimumVersionCode < 1) ||
            OrezEvaluationCanonical.profileSha256(profile) != binding[OrezEvaluationField.PROFILE_SHA256] ||
            profile.inputProfileRevision != binding[OrezEvaluationField.INPUT_PROFILE] || profile.runtimeSourceRevision != binding[OrezEvaluationField.RUNTIME_SOURCE_REVISION] ||
            profile.task.name != binding[OrezEvaluationField.TASK_KIND] || profile.governanceSchemaVersion.toLong() != schema) return invalid("Contradictory profile binding")
        if (!captured.artifactRootSha256.matches(hash) || cases.map { it.id }.distinct().size != cases.size ||
            cases.any { !it.id.matches(id) || !it.sourceSha256.matches(hash) || !it.formattedInputSha256.matches(hash) ||
                !it.termination.matches(id) || !it.sampler.matches(id) || it.promptTokens !in 0..4096 || it.generatedTokens !in 0..512 ||
                it.tokenLimit !in 0..320 || it.generatedTokens > it.tokenLimit || it.contextTokens !in 0..8192 ||
                it.threads !in 0..2 || it.elapsedMs < 0 || it.peakResidentBytes < 0 ||
                (it.observation == OrezNativeSettingsObservation.COMPLETE && (it.tokenLimit < 96 ||
                    it.contextTokens != it.promptTokens + it.tokenLimit + 64 || it.threads < 1 || it.batch != 256 || it.microBatch != 128 || it.sampler != "GREEDY")) ||
                (it.observation == OrezNativeSettingsObservation.NOT_OBSERVED && (it.generatedTokens != 0 || it.contextTokens != 0 || it.threads != 0 ||
                    it.batch != 0 || it.microBatch != 0 || it.sampler != "NOT_OBSERVED" || it.peakResidentBytes != 0L)) ||
                (it.outcome == OrezEvaluationOutcome.PASS && (it.observation != OrezNativeSettingsObservation.COMPLETE || it.termination != "EOG" || it.promptTokens < 1 || it.generatedTokens < 1 || it.elapsedMs > budget ||
                    it.peakResidentBytes < 1 || it.peakResidentBytes > binding[OrezEvaluationField.TOTAL_RAM_BYTES].toLong())) }) return invalid("Malformed case or observed native settings")
        if (profile.task == OrezEvaluationTask.LOCALIZATION && profile.inputProfileRevision == OrezLocalizationV2.REVISION &&
            profile.runtimeSourceRevision == OrezModelGovernance.RUNTIME_REVISION && cases.any { it.tokenLimit > 0 && it.tokenLimit != OrezLocalizationV2.MAX_TOKENS }) {
            return invalid("Observed token limit contradicts the captured v2 native profile")
        }
        if (captured.passedCases != cases.count { it.outcome == OrezEvaluationOutcome.PASS } ||
            captured.failedCases != cases.count { it.outcome == OrezEvaluationOutcome.FAIL } ||
            captured.cancelledCases != cases.count { it.outcome == OrezEvaluationOutcome.CANCELLED } ||
            captured.passedCases + captured.failedCases + captured.cancelledCases != cases.size ||
            binding[OrezEvaluationField.SOURCE_CASES_SHA256] != OrezEvaluationCanonical.sourceCasesSha256(cases) ||
            binding[OrezEvaluationField.PROMPT_CASES_SHA256] != OrezEvaluationCanonical.promptCasesSha256(cases) ||
            binding[OrezEvaluationField.SETTINGS_SHA256] != OrezEvaluationCanonical.settingsSha256(cases)) return invalid("Case counts or captured input/settings inventory disagree")
        if (artifacts.map { it.key }.distinct().size != artifacts.size || artifacts.sumOf { it.bytes.coerceIn(0, MAX_BODY_BYTES.toLong()) } > MAX_TOTAL_BODY_BYTES || artifacts.any {
                (it.key.caseId != OrezEvaluationCanonical.SCOPE_ID && !it.key.caseId.matches(id)) ||
                    it.bytes !in 0..MAX_BODY_BYTES.toLong() || !it.sha256.matches(hash)
            }) return invalid("Malformed or duplicate artifact commitment")
        val byKey = artifacts.associateBy { it.key }
        val required = mutableSetOf<OrezEvaluationArtifactKey>()
        for ((role, field) in OrezEvaluationCanonical.globalBindings) {
            val key = OrezEvaluationArtifactKey(OrezEvaluationCanonical.SCOPE_ID, role); required += key
            if (byKey[key]?.sha256 != binding[field]) return invalid("Missing or contradictory scoped ${role.name} commitment")
        }
        for (case in cases) {
            val roles = listOf(OrezEvaluationArtifactRole.SOURCE, OrezEvaluationArtifactRole.PROMPT,
                OrezEvaluationArtifactRole.JUDGMENT, OrezEvaluationArtifactRole.MEASUREMENT)
            roles.forEach { required += OrezEvaluationArtifactKey(case.id, it) }
            val output = OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.OUTPUT)
            val error = OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.ERROR)
            if ((output in byKey) == (error in byKey) || (case.outcome == OrezEvaluationOutcome.PASS && output !in byKey)) return invalid("Case must have one output or terminal error")
            required += if (output in byKey) output else error
            if (byKey[OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.SOURCE)]?.sha256 != case.sourceSha256 ||
                byKey[OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.PROMPT)]?.sha256 != case.formattedInputSha256) return invalid("Source/prompt commitment disagrees with captured case")
        }
        if (byKey.keys != required || OrezEvaluationCanonical.manifest(artifacts).size > MAX_RECEIPT_BYTES ||
            OrezEvaluationCanonical.receipt(captured).size > MAX_RECEIPT_BYTES ||
            OrezEvaluationCanonical.artifactRootSha256(artifacts) != captured.artifactRootSha256) return invalid("Artifact inventory/root disagrees")
        if (bodies.keys.any { it !in required }) return invalid("Uncommitted artifact body")
        var bytes = 0L
        val copied = linkedMapOf<OrezEvaluationArtifactKey, ByteArray>()
        for ((key, raw) in bodies) {
            bytes += raw.size.toLong()
            if (raw.size > MAX_BODY_BYTES || bytes > MAX_TOTAL_BODY_BYTES) return invalid("Artifact byte budget exceeded")
            val snapshot = raw.copyOf()
            val commitment = byKey.getValue(key)
            if (snapshot.size.toLong() != commitment.bytes || OrezEvaluationCanonical.sha256(snapshot) != commitment.sha256) return invalid("Present artifact body is corrupt")
            if (key.role in setOf(OrezEvaluationArtifactRole.SOURCE, OrezEvaluationArtifactRole.PROMPT, OrezEvaluationArtifactRole.OUTPUT, OrezEvaluationArtifactRole.ERROR)) {
                try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(snapshot))
                } catch (_: CharacterCodingException) { return invalid("Text artifact is not valid UTF-8") }
            }
            if (snapshot.isEmpty() && key.role !in setOf(OrezEvaluationArtifactRole.SOURCE, OrezEvaluationArtifactRole.OUTPUT)) return invalid("Required evidence artifact is empty")
            copied[key] = snapshot
        }
        // Inspect all present bodies before reporting a different missing body, so corruption never
        // becomes a harmless pending/imported/scope status. No Boolean grants artifact verification.
        for (case in cases) {
            for ((role, canonical) in listOf(OrezEvaluationArtifactRole.JUDGMENT to OrezEvaluationCanonical.judgment(case),
                OrezEvaluationArtifactRole.MEASUREMENT to OrezEvaluationCanonical.measurement(case))) {
                copied[OrezEvaluationArtifactKey(case.id, role)]?.let { if (!it.contentEquals(canonical)) return invalid("Judgment/measurement contradicts result claims") }
            }
            copied[OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.OUTPUT)]?.let {
                if (case.outcome == OrezEvaluationOutcome.PASS && it.isEmpty()) return invalid("Passing output is empty")
            }
        }
        copied[OrezEvaluationArtifactKey(OrezEvaluationCanonical.SCOPE_ID, OrezEvaluationArtifactRole.CORPUS_MANIFEST)]?.let {
            if (!it.contentEquals(OrezEvaluationCanonical.corpusManifest(cases, binding[OrezEvaluationField.CORPUS_ID],
                    binding[OrezEvaluationField.CORPUS_REVISION], binding[OrezEvaluationField.CORPUS_LICENSE_SHA256]))) return invalid("Corpus manifest inventory contradicts cases")
        }
        copied[OrezEvaluationArtifactKey(OrezEvaluationCanonical.SCOPE_ID, OrezEvaluationArtifactRole.HELD_OUT_MANIFEST)]?.let {
            if (!it.contentEquals(OrezEvaluationCanonical.heldOutManifest(cases, binding[OrezEvaluationField.RUBRIC_SHA256]))) return invalid("Held-out manifest inventory contradicts cases")
        }
        copied[OrezEvaluationArtifactKey(OrezEvaluationCanonical.SCOPE_ID, OrezEvaluationArtifactRole.DEVICE_CAPABILITIES)]?.let {
            if (!it.contentEquals(OrezEvaluationCanonical.deviceCapabilities(binding[OrezEvaluationField.DEVICE_CLASS],
                    binding[OrezEvaluationField.DEVICE_KIND], binding[OrezEvaluationField.NATIVE_ABI], sdk.toInt(),
                    binding[OrezEvaluationField.TOTAL_RAM_BYTES].toLong(), binding[OrezEvaluationField.FREE_STORAGE_BYTES].toLong(),
                    binding[OrezEvaluationField.MEASUREMENT_CAPABILITIES]))) return invalid("Observed device receipt contradicts scoped capability claims")
        }
        val governance = OrezModelGovernance.find(binding.model)
        if (governance != null && (binding[OrezEvaluationField.PUBLISHER_REVISION] != governance.publisherRevision ||
                binding[OrezEvaluationField.LICENSE_SHA256] != governance.licenseSha256 ||
                binding[OrezEvaluationField.PUBLISHER_METADATA_SHA256] != governance.publisherMetadataSha256)) return invalid("Publisher/license identity contradicts exact model pin")
        if (binding[OrezEvaluationField.RUNTIME_SOURCE_REVISION] == OrezModelGovernance.RUNTIME_REVISION &&
            binding[OrezEvaluationField.RUNTIME_LICENSE_SHA256] != OrezModelGovernance.RUNTIME_LICENSE_SHA256) return invalid("Runtime license contradicts pinned runtime source")
        if (copied.keys != required) return pending("Required artifact bodies missing")
        if (governance == null) return pending("No governance metadata for this exact model")
        if (binding != expected.binding || expected.cases.size !in 1..MAX_CASES || expected.cases.distinctBy { it.id }.size != expected.cases.size ||
            cases.map { OrezEvaluationExpectedCase(it.id, it.sourceSha256, it.formattedInputSha256) }.toSet() != expected.cases.toSet()) return pending("Evidence does not cover the requested exact scope/case inventory")
        if (captured.smoke) return pending("Smoke evidence cannot qualify a model")
        val minimum = profile.minimumApp as? OrezMinimumApp.Declared ?: return pending("Empirical profile minimum app compatibility is not declared")
        if (schema != OrezModelGovernance.SCHEMA_VERSION.toLong() || appVersion < minimum.minimumVersionCode ||
            profile.runtimeSourceRevision != OrezModelGovernance.RUNTIME_REVISION ||
            profile.task != OrezEvaluationTask.LOCALIZATION || profile.inputProfileRevision != OrezLocalizationV2.REVISION) return pending("Empirical input/runtime/app profile is unsupported")
        if (binding[OrezEvaluationField.MEASUREMENT_CAPABILITIES] != "FREE_STORAGE:LATENCY:RSS:TOTAL_RAM") return pending("Empirical measurement capability profile is unsupported")
        val digest = OrezEvaluationCanonical.receiptSha256(captured)
        val outcome = if (captured.failedCases == 0 && captured.cancelledCases == 0) OrezEvaluationOutcome.PASS else OrezEvaluationOutcome.FAIL
        val review = reviews.reviewed(digest) ?: return pending("Artifact-verified evidence has no reviewed-result registration")
        if (review != outcome) return invalid("Trusted registration contradicts complete case outcome")
        if (outcome != OrezEvaluationOutcome.PASS) return OrezEvaluationValidation(OrezEvaluationState.OBSERVED_FAILURE, "Reviewed complete failure for this exact scope")
        if (!reviews.approved(digest)) return pending("Passing evidence has no separate qualification approval")
        return OrezEvaluationValidation(OrezEvaluationState.QUALIFIED_FOR_SCOPE, "Reviewed passing evidence approved only for this exact scope")
    }
}
