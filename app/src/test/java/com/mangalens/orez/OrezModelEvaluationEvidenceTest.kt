package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

/** All passing/failing registrations here are synthetic validator fixtures, never shipped approvals. */
class OrezModelEvaluationEvidenceTest {
    private val fixture by lazy { Fixture() }
    private fun validate(receipt: OrezEvaluationReceipt? = fixture.receipt,
        bodies: Map<OrezEvaluationArtifactKey, ByteArray> = fixture.bodies,
        expected: OrezEvaluationExpectation = fixture.expectation,
        reviewed: Map<String, OrezEvaluationOutcome> = emptyMap(), approvals: Set<String> = emptySet()) =
        OrezModelEvaluationValidator(OrezEvaluationReviewRegistry(reviewed, approvals)).validate(receipt, bodies, expected)
    private fun assertState(state: OrezEvaluationState, result: OrezEvaluationValidation) = assertEquals(result.reason, state, result.state)

    @Test fun absentReportIsPending() = assertState(OrezEvaluationState.PENDING, validate(null))
    @Test fun emptyShippedReviewRegistryCannotQualifyPlausiblePassingEvidence() = assertState(OrezEvaluationState.PENDING, validate())
    @Test fun completeReviewedPassingFixtureRequiresSeparateApproval() {
        val digest = OrezEvaluationCanonical.receiptSha256(fixture.receipt)
        assertState(OrezEvaluationState.PENDING, validate(reviewed = mapOf(digest to OrezEvaluationOutcome.PASS)))
        assertState(OrezEvaluationState.QUALIFIED_FOR_SCOPE, validate(reviewed = mapOf(digest to OrezEvaluationOutcome.PASS), approvals = setOf(digest)))
    }
    @Test fun approvalWithoutReviewedResultCannotGrantQualification() {
        assertState(OrezEvaluationState.PENDING, validate(approvals = setOf(OrezEvaluationCanonical.receiptSha256(fixture.receipt))))
    }
    @Test fun corruptUnregisteredBodyIsInvalidBeforeTrustChecks() {
        val changed = fixture.bodies.toMutableMap()
        changed[OrezEvaluationArtifactKey("case-1", OrezEvaluationArtifactRole.OUTPUT)] = "corrupt".toByteArray()
        assertState(OrezEvaluationState.INVALID, validate(bodies = changed))
    }
    @Test fun missingRequiredBodyIsPendingArtifacts() {
        val changed = fixture.bodies.toMutableMap()
        changed.remove(OrezEvaluationArtifactKey("case-1", OrezEvaluationArtifactRole.OUTPUT))
        assertState(OrezEvaluationState.PENDING, validate(bodies = changed))
    }
    @Test fun unrelatedExtraBodyIsInvalidEvenIfAllExpectedBodiesArePresent() {
        val changed = fixture.bodies + (OrezEvaluationArtifactKey("case-2", OrezEvaluationArtifactRole.OUTPUT) to byteArrayOf(1))
        assertState(OrezEvaluationState.INVALID, validate(bodies = changed))
    }
    @Test fun manifestRootCannotBeSwappedForAnotherValidHash() {
        assertState(OrezEvaluationState.INVALID, validate(fixture.receipt.copy(artifactRootSha256 = "f".repeat(64))))
    }
    @Test fun corpusAndHeldOutManifestCannotContainUnrelatedCasesEvenWithValidHashes() {
        for ((role, field) in listOf(OrezEvaluationArtifactRole.CORPUS_MANIFEST to OrezEvaluationField.CORPUS_SHA256,
            OrezEvaluationArtifactRole.HELD_OUT_MANIFEST to OrezEvaluationField.HELD_OUT_SHA256)) {
            val key = OrezEvaluationArtifactKey(OrezEvaluationCanonical.SCOPE_ID, role)
            val raw = "hash-valid unrelated inventory".toByteArray()
            val entries = fixture.receipt.artifacts.map { if (it.key == key) it.copy(bytes = raw.size.toLong(), sha256 = OrezEvaluationCanonical.sha256(raw)) else it }
            val r = fixture.receipt.copy(binding = fixture.binding.with(field, OrezEvaluationCanonical.sha256(raw)), artifacts = entries,
                artifactRootSha256 = OrezEvaluationCanonical.artifactRootSha256(entries))
            assertState(OrezEvaluationState.INVALID, validate(r, bodies = fixture.bodies + (key to raw)))
        }
    }
    @Test fun duplicateManifestRoleCannotCountAsCompleteInventory() {
        val entries = fixture.receipt.artifacts + fixture.receipt.artifacts.first()
        val r = fixture.receipt.copy(artifacts = entries, artifactRootSha256 = OrezEvaluationCanonical.artifactRootSha256(entries))
        assertState(OrezEvaluationState.INVALID, validate(r))
    }
    @Test fun hiddenFailureOrDroppedCaseCannotShrinkTheDenominator() {
        val expected = fixture.expectation.copy(cases = fixture.expectation.cases + OrezEvaluationExpectedCase("case-2", fixture.case.sourceSha256, fixture.case.formattedInputSha256))
        assertState(OrezEvaluationState.PENDING, validate(expected = expected))
        assertState(OrezEvaluationState.INVALID, validate(fixture.receipt.copy(passedCases = 2)))
    }
    @Test fun changedFullBuildInputsNeverReuseSameBaseGitQualification() {
        val expected = fixture.expectation.copy(binding = fixture.binding.with(OrezEvaluationField.BUILD_INPUT_SHA256, "f".repeat(64)))
        assertState(OrezEvaluationState.PENDING, validate(expected = expected))
    }
    @Test fun everyExactScopeBindingMustMatchRequestedEvaluation() {
        for (field in OrezEvaluationField.entries) {
            val original = fixture.binding[field]
            val changed = when (field) {
                OrezEvaluationField.APP_VERSION_CODE -> "12"
                OrezEvaluationField.GOVERNANCE_SCHEMA_VERSION -> "2"
                OrezEvaluationField.DEVICE_KIND -> "PHYSICAL"
                OrezEvaluationField.TASK_KIND -> "CHAT"
                OrezEvaluationField.TOTAL_RAM_BYTES -> "8000000001"
                OrezEvaluationField.FREE_STORAGE_BYTES -> "8000000001"
                OrezEvaluationField.BUDGET_MS -> "3001"
                else -> if (original.length == 64) "f".repeat(64) else if (original.length == 40) "f".repeat(40) else "different"
            }
            assertState(OrezEvaluationState.PENDING, validate(expected = fixture.expectation.copy(binding = fixture.binding.with(field, changed))))
        }
        val otherPin = fixture.binding.model.copy(bytes = fixture.binding.model.bytes + 1)
        assertState(OrezEvaluationState.PENDING, validate(expected = fixture.expectation.copy(binding = fixture.binding.withModel(otherPin))))
    }
    @Test fun emulatorEvidenceCannotQualifyPhysicalDeviceScope() {
        assertState(OrezEvaluationState.PENDING, validate(expected = fixture.expectation.copy(binding = fixture.binding.with(OrezEvaluationField.DEVICE_KIND, "PHYSICAL"))))
    }
    @Test fun smokeDoesNotQualifyEvenWithSyntheticReviewAndApproval() {
        val r = fixture.receipt.copy(smoke = true)
        val digest = OrezEvaluationCanonical.receiptSha256(r)
        assertState(OrezEvaluationState.PENDING, validate(r, reviewed = mapOf(digest to OrezEvaluationOutcome.PASS), approvals = setOf(digest)))
    }
    @Test fun completeRegisteredFailureCanBeObservedWithoutPassingApproval() {
        val f = Fixture(outcome = OrezEvaluationOutcome.FAIL)
        val digest = OrezEvaluationCanonical.receiptSha256(f.receipt)
        val validator = OrezModelEvaluationValidator(OrezEvaluationReviewRegistry(mapOf(digest to OrezEvaluationOutcome.FAIL), emptySet()))
        assertState(OrezEvaluationState.OBSERVED_FAILURE, validator.validate(f.receipt, f.bodies, f.expectation))
        assertState(OrezEvaluationState.PENDING, OrezModelEvaluationValidator().validate(f.receipt, f.bodies, f.expectation))
    }
    @Test fun earlyNativeFailureCanRecordNotObservedSettingsWithoutInventingContext() {
        val f = Fixture(outcome = OrezEvaluationOutcome.FAIL, observation = OrezNativeSettingsObservation.NOT_OBSERVED)
        val digest = OrezEvaluationCanonical.receiptSha256(f.receipt)
        assertEquals(0, f.case.contextTokens)
        assertEquals("NOT_OBSERVED", f.case.sampler)
        assertState(OrezEvaluationState.OBSERVED_FAILURE,
            OrezModelEvaluationValidator(OrezEvaluationReviewRegistry(mapOf(digest to OrezEvaluationOutcome.FAIL))).validate(f.receipt, f.bodies, f.expectation))
    }
    @Test fun passingCaseCannotBorrowNotObservedNativeSettings() {
        val f = Fixture(observation = OrezNativeSettingsObservation.NOT_OBSERVED)
        assertState(OrezEvaluationState.INVALID, OrezModelEvaluationValidator().validate(f.receipt, f.bodies, f.expectation))
    }
    @Test fun historicalNotDeclaredPackCanUseIndependentDeclaredCompatibleProfile() {
        assertEquals(OrezMinimumApp.NotDeclared, requireNotNull(OrezModelGovernance.find(fixture.binding.model)).packMinimumApp)
        val digest = OrezEvaluationCanonical.receiptSha256(fixture.receipt)
        assertState(OrezEvaluationState.QUALIFIED_FOR_SCOPE, validate(reviewed = mapOf(digest to OrezEvaluationOutcome.PASS), approvals = setOf(digest)))
    }
    @Test fun tooOldAppOrUndeclaredNewProfileCannotQualify() {
        val f = Fixture(minimumApp = OrezMinimumApp.Declared(12))
        assertState(OrezEvaluationState.PENDING, OrezModelEvaluationValidator().validate(f.receipt, f.bodies, f.expectation))
        val unknown = Fixture(minimumApp = OrezMinimumApp.NotDeclared)
        assertState(OrezEvaluationState.PENDING, OrezModelEvaluationValidator().validate(unknown.receipt, unknown.bodies, unknown.expectation))
    }
    @Test fun wrongPublisherLicenseOrRuntimeProofIsInvalid() {
        for (field in listOf(OrezEvaluationField.LICENSE_SHA256, OrezEvaluationField.PUBLISHER_REVISION, OrezEvaluationField.PUBLISHER_METADATA_SHA256)) {
            val r = fixture.receipt.copy(binding = fixture.binding.with(field, if (fixture.binding[field].length == 40) "f".repeat(40) else "f".repeat(64)))
            assertState(OrezEvaluationState.INVALID, validate(r))
        }
    }
    @Test fun forgedJudgmentOrMeasurementCannotDisagreeWithResultClaims() {
        for (role in listOf(OrezEvaluationArtifactRole.JUDGMENT, OrezEvaluationArtifactRole.MEASUREMENT)) {
            val key = OrezEvaluationArtifactKey("case-1", role)
            val changed = fixture.bodies + (key to "plausible but unrelated".toByteArray())
            val entries = fixture.receipt.artifacts.map { if (it.key == key) it.copy(bytes = changed.getValue(key).size.toLong(), sha256 = OrezEvaluationCanonical.sha256(changed.getValue(key))) else it }
            val r = fixture.receipt.copy(artifacts = entries, artifactRootSha256 = OrezEvaluationCanonical.artifactRootSha256(entries))
            assertState(OrezEvaluationState.INVALID, validate(r, bodies = changed))
        }
    }
    @Test fun successNeedsActualNativeCompletionAndUnclampedObservedSettings() {
        for (c in listOf(fixture.case.copy(termination = "TOKEN_LIMIT"), fixture.case.copy(tokenLimit = 512), fixture.case.copy(contextTokens = 8192), fixture.case.copy(elapsedMs = 3001), fixture.case.copy(peakResidentBytes = -1))) {
            val r = fixture.receipt.copy(cases = listOf(c))
            assertState(OrezEvaluationState.INVALID, validate(r))
        }
    }
    @Test fun v2ProfileCannotQualifyAnOtherwiseCoherent224TokenNativeResult() {
        val f = Fixture(tokenLimit = 224)
        assertState(OrezEvaluationState.INVALID, OrezModelEvaluationValidator().validate(f.receipt, f.bodies, f.expectation))
    }
    @Test fun reviewed320TokenFixtureCannotQualifyTheActual288TokenV2Profile() {
        val f = Fixture(tokenLimit = 320)
        val digest = OrezEvaluationCanonical.receiptSha256(f.receipt)
        val validator = OrezModelEvaluationValidator(OrezEvaluationReviewRegistry(mapOf(digest to OrezEvaluationOutcome.PASS), setOf(digest)))
        assertState(OrezEvaluationState.INVALID, validator.validate(f.receipt, f.bodies, f.expectation))
    }
    @Test fun contradictoryKnownV2SettingsAreInvalidBeforeMissingBodiesOtherScopeOrSmoke() {
        val f = Fixture(tokenLimit = 320)
        val validator = OrezModelEvaluationValidator()
        assertState(OrezEvaluationState.INVALID, validator.validate(f.receipt,
            f.bodies - OrezEvaluationArtifactKey("case-1", OrezEvaluationArtifactRole.OUTPUT), f.expectation))
        assertState(OrezEvaluationState.INVALID, validator.validate(f.receipt, f.bodies,
            f.expectation.copy(binding = f.binding.with(OrezEvaluationField.DEVICE_KIND, "PHYSICAL"))))
        assertState(OrezEvaluationState.INVALID, validator.validate(f.receipt.copy(smoke = true), f.bodies, f.expectation))
    }
    @Test fun malformedUtf8OutputIsInvalidWhileRealHindiTextRemainsAcceptableEvidence() {
        val key = OrezEvaluationArtifactKey("case-1", OrezEvaluationArtifactRole.OUTPUT)
        for ((raw, state) in listOf(byteArrayOf(0xc3.toByte(), 0x28) to OrezEvaluationState.INVALID,
            "वास्तविक हिन्दी पाठ".toByteArray(Charsets.UTF_8) to OrezEvaluationState.PENDING)) {
            val entries = fixture.receipt.artifacts.map { if (it.key == key) it.copy(bytes = raw.size.toLong(), sha256 = OrezEvaluationCanonical.sha256(raw)) else it }
            val r = fixture.receipt.copy(artifacts = entries, artifactRootSha256 = OrezEvaluationCanonical.artifactRootSha256(entries))
            assertState(state, validate(r, bodies = fixture.bodies + (key to raw)))
        }
    }
    @Test fun malformedBindingAndUnknownSchemaFailClosed() {
        assertState(OrezEvaluationState.INVALID, validate(fixture.receipt.copy(schemaVersion = 2)))
        assertState(OrezEvaluationState.INVALID, validate(fixture.receipt.copy(binding = fixture.binding.with(OrezEvaluationField.NATIVE_BINARY_SHA256, "not-a-sha"))))
    }
    @Test fun bodyAndInventoryCapsRejectWithoutQualification() {
        val key = OrezEvaluationArtifactKey("case-1", OrezEvaluationArtifactRole.OUTPUT)
        assertState(OrezEvaluationState.INVALID, validate(bodies = fixture.bodies + (key to ByteArray(OrezModelEvaluationValidator.MAX_BODY_BYTES + 1))))
        assertState(OrezEvaluationState.INVALID, validate(fixture.receipt.copy(cases = List(129) { fixture.case.copy(id = "case-$it") })))
        assertState(OrezEvaluationState.INVALID, validate(fixture.receipt.copy(artifacts = List(1025) { fixture.receipt.artifacts.first() })))
    }
    @Test fun declaredTotalVolumeCannotExceedBodyBudgetWhenBodiesAreAbsent() {
        val source = ByteArray(OrezModelEvaluationValidator.MAX_BODY_BYTES) { 's'.code.toByte() }
        val sourceSha = OrezEvaluationCanonical.sha256(source)
        val cases = List(17) { fixture.case.copy(id = "case-$it", sourceSha256 = sourceSha) }
        val bodies = fixture.bodies.filterKeys { it.caseId == OrezEvaluationCanonical.SCOPE_ID }.toMutableMap()
        for (case in cases) {
            bodies[OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.SOURCE)] = source
            bodies[OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.PROMPT)] = "exact captured native prompt".toByteArray()
            bodies[OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.OUTPUT)] = "actual fixture output".toByteArray()
            bodies[OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.JUDGMENT)] = OrezEvaluationCanonical.judgment(case)
            bodies[OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.MEASUREMENT)] = OrezEvaluationCanonical.measurement(case)
        }
        val corpus = OrezEvaluationCanonical.corpusManifest(cases, fixture.binding[OrezEvaluationField.CORPUS_ID],
            fixture.binding[OrezEvaluationField.CORPUS_REVISION], fixture.binding[OrezEvaluationField.CORPUS_LICENSE_SHA256])
        val heldOut = OrezEvaluationCanonical.heldOutManifest(cases, fixture.binding[OrezEvaluationField.RUBRIC_SHA256])
        bodies[OrezEvaluationArtifactKey(OrezEvaluationCanonical.SCOPE_ID, OrezEvaluationArtifactRole.CORPUS_MANIFEST)] = corpus
        bodies[OrezEvaluationArtifactKey(OrezEvaluationCanonical.SCOPE_ID, OrezEvaluationArtifactRole.HELD_OUT_MANIFEST)] = heldOut
        val binding = fixture.binding.with(OrezEvaluationField.SOURCE_CASES_SHA256, OrezEvaluationCanonical.sourceCasesSha256(cases))
            .with(OrezEvaluationField.PROMPT_CASES_SHA256, OrezEvaluationCanonical.promptCasesSha256(cases))
            .with(OrezEvaluationField.SETTINGS_SHA256, OrezEvaluationCanonical.settingsSha256(cases))
            .with(OrezEvaluationField.CORPUS_SHA256, OrezEvaluationCanonical.sha256(corpus))
            .with(OrezEvaluationField.HELD_OUT_SHA256, OrezEvaluationCanonical.sha256(heldOut))
        val entries = bodies.map { (key, bytes) -> OrezEvaluationArtifact(key, bytes.size.toLong(), OrezEvaluationCanonical.sha256(bytes)) }
        val r = fixture.receipt.copy(binding = binding, cases = cases, artifacts = entries,
            artifactRootSha256 = OrezEvaluationCanonical.artifactRootSha256(entries), passedCases = 17)
        val expected = OrezEvaluationExpectation(binding, cases.map { OrezEvaluationExpectedCase(it.id, it.sourceSha256, it.formattedInputSha256) })
        // All96 commitments/roles/cases/hashes are coherent; only the declared17MiB source volume
        // exceeds the first text contract's16MiB cap. Missing supplied bodies must not hide that.
        assertState(OrezEvaluationState.INVALID, validate(r, bodies = emptyMap(), expected = expected))
    }
    @Test fun suppliedBodiesAreCheckedEvenWhenAnotherRequiredBodyIsMissing() {
        val changed = fixture.bodies.toMutableMap()
        changed.remove(OrezEvaluationArtifactKey("case-1", OrezEvaluationArtifactRole.OUTPUT))
        changed[OrezEvaluationArtifactKey("case-1", OrezEvaluationArtifactRole.SOURCE)] = byteArrayOf(0)
        assertState(OrezEvaluationState.INVALID, validate(bodies = changed))
    }
    @Test fun reviewRegistrationSnapshotsDoNotTrustLaterCallerMutation() {
        val digest = OrezEvaluationCanonical.receiptSha256(fixture.receipt)
        val reviewed = mutableMapOf<String, OrezEvaluationOutcome>()
        val approvals = mutableSetOf<String>()
        val validator = OrezModelEvaluationValidator(OrezEvaluationReviewRegistry(reviewed, approvals))
        reviewed[digest] = OrezEvaluationOutcome.PASS; approvals += digest
        assertState(OrezEvaluationState.PENDING, validator.validate(fixture.receipt, fixture.bodies, fixture.expectation))
    }
    @Test fun acceptedReceiptAndExpectedInventoriesDoNotRetainMutableCallerLists() {
        val cases = fixture.receipt.cases.toMutableList()
        val artifacts = fixture.receipt.artifacts.toMutableList()
        val expectedCases = fixture.expectation.cases.toMutableList()
        val r = fixture.receipt.copy(cases = cases, artifacts = artifacts)
        val expected = fixture.expectation.copy(cases = expectedCases)
        cases.clear(); artifacts.clear(); expectedCases.clear()
        assertEquals(1, r.cases.size)
        assertEquals(fixture.receipt.artifacts.size, r.artifacts.size)
        assertEquals(1, expected.cases.size)
        assertState(OrezEvaluationState.PENDING, validate(r, expected = expected))
    }

    private class Fixture(outcome: OrezEvaluationOutcome = OrezEvaluationOutcome.PASS,
        minimumApp: OrezMinimumApp = OrezMinimumApp.Declared(11), tokenLimit: Int = OrezLocalizationProfile.MAX_TOKENS,
        observation: OrezNativeSettingsObservation = OrezNativeSettingsObservation.COMPLETE) {
        private val model = OrezModelCatalog.lite.let { OrezModelPin(it.id, it.sha256, it.bytes) }
        private val governance = requireNotNull(OrezModelGovernance.find(model))
        private fun text(s: String) = s.toByteArray(Charsets.UTF_8)
        private fun asset(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/orez-governance/$name")).use { it.readBytes() }
        val profile = OrezEvaluationProfile("fixture-v1", OrezLocalizationV2.REVISION,
            OrezModelGovernance.RUNTIME_REVISION, minimumApp, 1, OrezEvaluationTask.LOCALIZATION)
        private val complete = observation == OrezNativeSettingsObservation.COMPLETE
        val case = OrezEvaluationCase("case-1", OrezEvaluationCanonical.sha256(text("licensed source")),
            OrezEvaluationCanonical.sha256(text("exact captured native prompt")), outcome,
            if (complete) "EOG" else "UNAVAILABLE", if (complete) 12 else 0, if (complete) 3 else 0, tokenLimit,
            if (complete) 12 + tokenLimit + 64 else 0, if (complete) 2 else 0, if (complete) 256 else 0, if (complete) 128 else 0,
            if (complete) "GREEDY" else "NOT_OBSERVED", 120, if (complete) 100_000_000 else 0, observation)
        val bodies: Map<OrezEvaluationArtifactKey, ByteArray>
        val binding: OrezEvaluationBinding
        val receipt: OrezEvaluationReceipt
        val expectation: OrezEvaluationExpectation
        init {
            val global = linkedMapOf(
                OrezEvaluationArtifactRole.PUBLISHER_METADATA to asset("lite-publisher.json"),
                OrezEvaluationArtifactRole.LICENSE to asset("Apache-2.0.txt"),
                OrezEvaluationArtifactRole.RUNTIME_LICENSE to asset("llama.cpp-MIT.txt"),
                OrezEvaluationArtifactRole.TENSOR_RECEIPT to text("synthetic tensor receipt"),
                OrezEvaluationArtifactRole.DEVICE_CAPABILITIES to OrezEvaluationCanonical.deviceCapabilities(
                    "validator-fixture-emulator", "EMULATOR", "x86_64", 26, 8_000_000_000L, 8_000_000_000L, "FREE_STORAGE:LATENCY:RSS:TOTAL_RAM"),
                OrezEvaluationArtifactRole.PROTOCOL to text("synthetic all-cases-pass protocol"),
                OrezEvaluationArtifactRole.RUBRIC to text("synthetic reviewed rubric"),
                OrezEvaluationArtifactRole.CORPUS_LICENSE to text("synthetic corpus licensed provenance"),
                OrezEvaluationArtifactRole.CORPUS_MANIFEST to OrezEvaluationCanonical.corpusManifest(listOf(case), "validator-fixture", "v1", OrezEvaluationCanonical.sha256(text("synthetic corpus licensed provenance"))),
                OrezEvaluationArtifactRole.HELD_OUT_MANIFEST to OrezEvaluationCanonical.heldOutManifest(listOf(case), OrezEvaluationCanonical.sha256(text("synthetic reviewed rubric"))),
                OrezEvaluationArtifactRole.BUILD_INPUT_MANIFEST to text("synthetic complete build-input manifest")
            )
            bodies = global.mapKeys { OrezEvaluationArtifactKey(OrezEvaluationCanonical.SCOPE_ID, it.key) } + mapOf(
                OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.SOURCE) to text("licensed source"),
                OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.PROMPT) to text("exact captured native prompt"),
                OrezEvaluationArtifactKey(case.id, if (complete) OrezEvaluationArtifactRole.OUTPUT else OrezEvaluationArtifactRole.ERROR) to text(if (complete) "actual fixture output" else "native unavailable before settings observation"),
                OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.JUDGMENT) to OrezEvaluationCanonical.judgment(case),
                OrezEvaluationArtifactKey(case.id, OrezEvaluationArtifactRole.MEASUREMENT) to OrezEvaluationCanonical.measurement(case)
            )
            val fields = OrezEvaluationField.entries.associateWith { "a".repeat(64) }.toMutableMap()
            fields[OrezEvaluationField.PUBLISHER_REVISION] = governance.publisherRevision
            fields[OrezEvaluationField.RUNTIME_SOURCE_REVISION] = OrezModelGovernance.RUNTIME_REVISION
            fields[OrezEvaluationField.APP_SOURCE_REVISION] = "a".repeat(40)
            fields[OrezEvaluationField.GOVERNANCE_SCHEMA_VERSION] = "1"
            fields[OrezEvaluationField.APP_VERSION_CODE] = "11"
            fields[OrezEvaluationField.TASK_KIND] = "LOCALIZATION"
            fields[OrezEvaluationField.INPUT_PROFILE] = OrezLocalizationV2.REVISION
            fields[OrezEvaluationField.PROFILE_SHA256] = OrezEvaluationCanonical.profileSha256(profile)
            fields[OrezEvaluationField.NATIVE_ABI] = "x86_64"
            fields[OrezEvaluationField.DEVICE_CLASS] = "validator-fixture-emulator"
            fields[OrezEvaluationField.DEVICE_KIND] = "EMULATOR"
            fields[OrezEvaluationField.SDK_LEVEL] = "26"
            fields[OrezEvaluationField.MEASUREMENT_CAPABILITIES] = "FREE_STORAGE:LATENCY:RSS:TOTAL_RAM"
            fields[OrezEvaluationField.TOTAL_RAM_BYTES] = "8000000000"
            fields[OrezEvaluationField.FREE_STORAGE_BYTES] = "8000000000"
            fields[OrezEvaluationField.TARGET_LANGUAGE] = "hi"
            fields[OrezEvaluationField.STYLE] = "FAITHFUL"
            fields[OrezEvaluationField.CORPUS_ID] = "validator-fixture"
            fields[OrezEvaluationField.CORPUS_REVISION] = "v1"
            fields[OrezEvaluationField.BUDGET_MS] = "3000"
            fields[OrezEvaluationField.SETTINGS_SHA256] = OrezEvaluationCanonical.settingsSha256(listOf(case))
            fields[OrezEvaluationField.SOURCE_CASES_SHA256] = OrezEvaluationCanonical.sourceCasesSha256(listOf(case))
            fields[OrezEvaluationField.PROMPT_CASES_SHA256] = OrezEvaluationCanonical.promptCasesSha256(listOf(case))
            for ((role, field) in OrezEvaluationCanonical.globalBindings) fields[field] = OrezEvaluationCanonical.sha256(global.getValue(role))
            binding = OrezEvaluationBinding(model, fields)
            val artifacts = bodies.map { (key, raw) -> OrezEvaluationArtifact(key, raw.size.toLong(), OrezEvaluationCanonical.sha256(raw)) }
            receipt = OrezEvaluationReceipt(1, binding, profile, listOf(case), artifacts,
                OrezEvaluationCanonical.artifactRootSha256(artifacts), if (outcome == OrezEvaluationOutcome.PASS) 1 else 0,
                if (outcome == OrezEvaluationOutcome.FAIL) 1 else 0, if (outcome == OrezEvaluationOutcome.CANCELLED) 1 else 0)
            expectation = OrezEvaluationExpectation(binding, listOf(OrezEvaluationExpectedCase(case.id, case.sourceSha256, case.formattedInputSha256)))
        }
    }
}
