package com.mangalens.orez

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** New source controls authored UNCOMPILED/UNRUN; all measurements/registrations are synthetic. */
class OrezEvaluationPackageTest {
    private val fixture by lazy { Fixture() }
    private fun packet() = OrezEvaluationPackage(fixture.receipt, fixture.bodies)
    private fun encoded(value: OrezEvaluationPackage = packet()): ByteArray = ByteArrayOutputStream().also { OrezEvaluationPackageCodec.write(value, it) }.toByteArray()
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IOException) {} }
    @Test fun wireRoundTripRetainsExactReceiptAndEveryActualArtifact() {
        val decoded = OrezEvaluationPackageCodec.read(ByteArrayInputStream(encoded()))
        assertEquals(OrezEvaluationCanonical.receiptSha256(fixture.receipt), OrezEvaluationCanonical.receiptSha256(decoded.receipt))
        fixture.bodies.forEach { (key, raw) -> assertArrayEquals(raw, decoded.bodies().getValue(key)) }
        assertEquals(OrezEvaluationState.PENDING, decoded.status().state)
    }
    @Test fun decodedReportCannotSupplyItsOwnReviewedOrPassingApproval() {
        val decoded = OrezEvaluationPackageCodec.read(ByteArrayInputStream(encoded()))
        assertEquals(OrezEvaluationState.PENDING, decoded.status().state)
        assertTrue(decoded.status().reason.contains("no reviewed-result"))
    }
    @Test fun exactBinaryWireRejectsTrailingFieldsAndTruncation() {
        val raw = OrezEvaluationReceiptCodec.encode(fixture.receipt)
        rejects { OrezEvaluationReceiptCodec.decode(raw + byteArrayOf(0)) }
        rejects { OrezEvaluationReceiptCodec.decode(raw.copyOf(raw.size - 1)) }
    }
    @Test fun schemaAndStringBoundsAreRejectedBeforeUnboundedAllocation() {
        val raw = OrezEvaluationReceiptCodec.encode(fixture.receipt)
        raw[7] = 2; rejects { OrezEvaluationReceiptCodec.decode(raw) }
        val oversized = OrezEvaluationReceiptCodec.encode(fixture.receipt)
        oversized[8] = 0x7f; rejects { OrezEvaluationReceiptCodec.decode(oversized) }
    }
    @Test fun invalidUtf8AndUnknownEnumsDoNotGetCoerced() {
        val raw = OrezEvaluationReceiptCodec.encode(fixture.receipt)
        raw[12] = 0xff.toByte(); rejects { OrezEvaluationReceiptCodec.decode(raw) }
        val unknown = OrezEvaluationReceiptCodec.encode(fixture.receipt).toString(Charsets.ISO_8859_1)
            .replace("LOCALIZATION", "UNKNOWN_TASK_").toByteArray(Charsets.ISO_8859_1)
        rejects { OrezEvaluationReceiptCodec.decode(unknown) }
    }
    @Test fun callerAndReturnedBodyMutationsDoNotChangeImportedEvidence() {
        val original = fixture.bodies.mapValues { it.value.copyOf() }.toMutableMap()
        val value = OrezEvaluationPackage(fixture.receipt, original)
        original.values.first()[0] = 0
        value.bodies().values.first()[0] = 1
        assertEquals(OrezEvaluationState.PENDING, value.status().state)
    }
    @Test fun corruptPresentArtifactCannotBeSavedAsMissingEvidence() {
        val raw = ByteArrayOutputStream()
        ZipOutputStream(raw).use { zip ->
            zip.putNextEntry(ZipEntry("receipt.bin")); zip.write(OrezEvaluationReceiptCodec.encode(fixture.receipt)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("artifacts/Y2FzZS0x/OUTPUT.bin")); zip.write("corrupt".toByteArray()); zip.closeEntry()
        }
        rejects { OrezEvaluationPackageCodec.read(ByteArrayInputStream(raw.toByteArray())) }
    }
    @Test fun unexpectedZipEntryCannotBecomeAFileOrToolInstruction() {
        val raw = ByteArrayOutputStream()
        ZipOutputStream(raw).use { zip ->
            zip.putNextEntry(ZipEntry("receipt.bin")); zip.write(OrezEvaluationReceiptCodec.encode(fixture.receipt)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("../../orez_models/model.gguf")); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
        rejects { OrezEvaluationPackageCodec.read(ByteArrayInputStream(raw.toByteArray())) }
    }
    @Test fun privateQuarantineExportChecksHashAndCannotActivateModels() {
        val root = Files.createTempDirectory("orez-lab-test").toFile()
        try {
            val store = OrezTrainingLabStore(java.io.File(root, "lab")); val entry = store.importPackage(ByteArrayInputStream(encoded()))
            assertEquals(1, store.list().size); assertFalse(java.io.File(root, "orez_models").exists())
            assertEquals(OrezEvaluationState.PENDING, OrezEvaluationPackageCodec.read(ByteArrayInputStream(store.export(entry.digest))).status().state)
            java.io.File(root, "lab/${entry.digest}.zip").writeBytes(byteArrayOf(1))
            rejects { store.export(entry.digest) }
            val corrupt = store.list().single(); assertNull(corrupt.model)
            store.delete(corrupt.digest); assertTrue(store.list().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun storageCapRejectsNinthDistinctPackagePreservingExistingEight() {
        val root = Files.createTempDirectory("orez-lab-test").toFile()
        try {
            val store = OrezTrainingLabStore(root)
            repeat(8) { n ->
                val receipt = fixture.receipt.copy(binding = fixture.binding.with(OrezEvaluationField.APP_SOURCE_REVISION, "a".repeat(38) + "%02x".format(n)))
                store.importPackage(ByteArrayInputStream(encoded(OrezEvaluationPackage(receipt, fixture.bodies))))
            }
            val ninth = fixture.receipt.copy(binding = fixture.binding.with(OrezEvaluationField.APP_SOURCE_REVISION, "b".repeat(40)))
            rejects { store.importPackage(ByteArrayInputStream(encoded(OrezEvaluationPackage(ninth, fixture.bodies)))) }
            assertEquals(8, store.list().size)
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationBeforePublicationLeavesNoNewPrivatePackage() {
        val root = Files.createTempDirectory("orez-lab-test").toFile()
        try {
            val store = OrezTrainingLabStore(root)
            try { store.importPackage(ByteArrayInputStream(encoded())) { throw java.util.concurrent.CancellationException() }; fail("Expected cancellation") }
            catch (_: java.util.concurrent.CancellationException) {}
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun deleteRemovesOnlyExactHashAddressedPackage() {
        val root = Files.createTempDirectory("orez-lab-test").toFile()
        try {
            val store = OrezTrainingLabStore(root); val entry = store.importPackage(ByteArrayInputStream(encoded()))
            rejects { store.delete("../orez_models") }; assertEquals(1, store.list().size)
            store.delete(entry.digest); assertTrue(store.list().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun completeZipEnvelopeAndExportPreserveOriginalByteIdentity() {
        val raw = encoded(); assertEquals(fixture.bodies.size + 1, OrezLabZipEnvelope.validate(raw).size)
        val root = Files.createTempDirectory("orez-lab-original-test").toFile()
        try {
            val store = OrezTrainingLabStore(root); val entry = store.importPackage(ByteArrayInputStream(raw))
            assertEquals(OrezEvaluationCanonical.sha256(raw), entry.digest); assertArrayEquals(raw, store.export(entry.digest))
        } finally { root.deleteRecursively() }
    }
    @Test fun truncatedCentralDirectoryAndTrailingForeignBytesCannotBeRetained() {
        val raw = encoded()
        rejects { OrezLabZipEnvelope.validate(raw.copyOf(raw.size - 10)) }
        rejects { OrezLabZipEnvelope.validate(raw + byteArrayOf(1, 2, 3)) }
    }
    @Test fun centralNameMustEqualActualLocalName() {
        val raw = encoded()
        val end = raw.size - 22
        fun u16(at: Int) = (raw[at].toInt() and 255) or ((raw[at + 1].toInt() and 255) shl 8)
        val central = u16(end + 16) or (u16(end + 18) shl 16)
        raw[central + 46] = 'x'.code.toByte()
        rejects { OrezLabZipEnvelope.validate(raw) }
    }
    @Test fun centralCrcMustEqualActualDataDescriptor() {
        val raw = encoded(); val end = raw.size - 22
        fun u16(at: Int) = (raw[at].toInt() and 255) or ((raw[at + 1].toInt() and 255) shl 8)
        val central = u16(end + 16) or (u16(end + 18) shl 16)
        raw[central + 16] = (raw[central + 16].toInt() xor 1).toByte()
        rejects { OrezLabZipEnvelope.validate(raw) }
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
