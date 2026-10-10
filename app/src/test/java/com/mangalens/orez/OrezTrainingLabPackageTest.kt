package com.mangalens.orez

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Authored UNCOMPILED/UNRUN: no real model, approval or device fixture is represented. */
class OrezTrainingLabPackageTest {
    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value).replace("\\/", "/")
        else -> value.toString()
    }
    private fun directory(path: String = "model.safetensors"): JSONObject {
        val files = JSONArray().put(JSONObject().put("path", path).put("sha256", "a".repeat(64)).put("bytes", 42))
        return JSONObject().put("files", files).put("manifest_sha256", OrezEvaluationCanonical.sha256((canonical(files) + "\n").toByteArray()))
    }
    private fun identity(body: ByteArray) = JSONObject().put("sha256", OrezEvaluationCanonical.sha256(body)).put("bytes", body.size)
    private fun fixture(mutate: (JSONObject) -> Unit = {}, corrupt: Boolean = false, extra: String? = null,
        mutateBodies: (LinkedHashMap<String, ByteArray>) -> Unit = {}): ByteArray {
        val source = directory(); val code = directory("common.py")
        val heldOut = JSONObject().put("file", "held_out.jsonl").put("records", 1).put("sha256", "b".repeat(64)).put("bytes", 123)
        val dataset = JSONObject().put("schema", "orez-training-dataset-v1").put("status", "PREPARED")
            .put("splits", JSONObject().put("held_out", heldOut))
        val datasetRaw = canonical(dataset).toByteArray()
        val evaluation = JSONObject().put("schema", "orez-host-evaluation-v1").put("status", "COMPLETED").put("runtime", "HOST_TRANSFORMERS")
            .put("android_qualification", "PENDING").put("model", source).put("dataset_manifest", identity(datasetRaw))
            .put("held_out", heldOut).put("cases", 1).put("errors", 0).put("exact_matches", 1).put("code", code)
        val quantization = JSONObject().put("schema", "orez-host-quantization-v1").put("status", "COMPLETED").put("runtime", "HOST_LLAMA_CPP")
            .put("android_qualification", "PENDING").put("base", source).put("output", JSONObject().put("sha256", "a".repeat(64)).put("bytes", 42))
            .put("converter", identity("converter".toByteArray())).put("quantizer", identity("quantizer".toByteArray())).put("quantization", "Q4_K_M").put("code", code)
        val bodies = linkedMapOf("artifacts/base_license.txt" to "Synthetic licensed control".toByteArray(),
            "artifacts/code_manifest.json" to canonical(code).toByteArray(), "artifacts/output_license.txt" to "Synthetic output license".toByteArray(),
            "artifacts/dataset_manifest.json" to datasetRaw, "artifacts/evaluation_report.json" to canonical(evaluation).toByteArray(),
            "artifacts/quantization_manifest.json" to canonical(quantization).toByteArray())
        mutateBodies(bodies)
        val artifacts = JSONArray()
        bodies.forEach { (path, body) -> artifacts.put(JSONObject().put("path", path).put("bytes", body.size)
            .put("sha256", OrezEvaluationCanonical.sha256(body)).put("role", path.substringAfter('/').substringBefore('.').uppercase())) }
        val manifest = JSONObject().put("schema", 1).put("kind", "OREZ_LOCAL_TRAINING_EVIDENCE")
            .put("model", JSONObject().put("model_id", "synthetic-control").put("sha256", "a".repeat(64)).put("bytes", 42))
            .put("runtime", "HOST_TRANSFORMERS").put("android_qualification", "PENDING").put("artifacts", artifacts)
        mutate(manifest)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun put(path: String, body: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(body); zip.closeEntry() }
            put(OrezTrainingLabPackage.MANIFEST, canonical(manifest).toByteArray())
            bodies.forEach { (path, body) -> put(path, if (corrupt && path.endsWith("code_manifest.json")) "bad".toByteArray() else body) }
            extra?.let { put(it, "unexpected".toByteArray()) }
        }
        return output.toByteArray()
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IOException) {} }
    @Test fun artifactVerifiedHostPacketStillRequiresAndroidEvaluation() {
        val checked = OrezTrainingLabPackage.read(fixture())
        assertEquals("HOST_TRANSFORMERS", checked.runtime); assertEquals(6, checked.artifactCount)
        assertEquals("synthetic-control", checked.model.modelId)
    }
    @Test fun claimedApprovalCannotBeImportedAsAndroidQualification() {
        rejects { OrezTrainingLabPackage.read(fixture({ it.put("android_qualification", "QUALIFIED") })) }
    }
    @Test fun injectedReviewAuthorityIsUnknownManifestKey() {
        rejects { OrezTrainingLabPackage.read(fixture({ it.put("trusted", true) })) }
    }
    @Test fun corruptArtifactRejectsBeforePrivatePublication() { rejects { OrezTrainingLabPackage.read(fixture(corrupt = true)) } }
    @Test fun arbitraryZipPathCannotExtractOrPublish() { rejects { OrezTrainingLabPackage.read(fixture(extra = "../../orez_models/model.gguf")) } }
    @Test fun traversalInventoryAndExtraNativeRuntimeAreRejected() {
        rejects { OrezTrainingLabPackage.read(fixture({ it.getJSONArray("artifacts").getJSONObject(0).put("path", "artifacts/../base_license.txt") })) }
        rejects { OrezTrainingLabPackage.read(fixture({ it.put("runtime", "ANDROID_NATIVE") })) }
    }
    @Test fun unknownSchemaAndStringNumbersAreNotCoerced() {
        rejects { OrezTrainingLabPackage.read(fixture({ it.put("schema", 2) })) }
        rejects { OrezTrainingLabPackage.read(fixture({ it.getJSONObject("model").put("bytes", "42") })) }
    }
    @Test fun inventoryDuplicatesAndMissingLicenseReject() {
        rejects { OrezTrainingLabPackage.read(fixture({ it.getJSONArray("artifacts").put(it.getJSONArray("artifacts").getJSONObject(0)) })) }
        rejects { OrezTrainingLabPackage.read(fixture({ it.getJSONArray("artifacts").getJSONObject(0).put("role", "TRAINING_MANIFEST") })) }
    }
    @Test fun declaredBoundsRejectBeforeBodyInflation() {
        rejects { OrezTrainingLabPackage.read(fixture({ it.getJSONArray("artifacts").getJSONObject(0).put("bytes", OrezModelEvaluationValidator.MAX_BODY_BYTES + 1) })) }
    }
    @Test fun callerCancellationPropagatesWithoutImportedStatus() {
        try { OrezTrainingLabPackage.read(fixture()) { throw java.util.concurrent.CancellationException() }; fail("Expected cancellation") }
        catch (_: java.util.concurrent.CancellationException) {}
    }
    @Test fun normalizedArchiveKeepsRealArtifactIdentity() {
        val first = OrezTrainingLabPackage.read(fixture()); val second = OrezTrainingLabPackage.read(first.normalizedBytes)
        assertArrayEquals(first.normalizedBytes, second.normalizedBytes); assertEquals(first.model, second.model)
    }
    @Test fun nestedUntrustedManifestCannotDriveUnboundedRecursion() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry(OrezTrainingLabPackage.MANIFEST)); zip.write(("[".repeat(20)+"0"+"]".repeat(20)).toByteArray()); zip.closeEntry() }
        rejects { OrezTrainingLabPackage.read(output.toByteArray()) }
    }
    @Test fun individuallyHashedUnrelatedHostEvaluationCannotBorrowGgufProvenance() {
        rejects { OrezTrainingLabPackage.read(fixture(mutateBodies = { bodies ->
            val evaluation = JSONObject(bodies.getValue("artifacts/evaluation_report.json").toString(Charsets.UTF_8))
            evaluation.put("model", directory("unrelated.safetensors"))
            bodies["artifacts/evaluation_report.json"] = canonical(evaluation).toByteArray()
        })) }
    }
    @Test fun quantizationOutputMustEqualTheDeclaredWeightIdentity() {
        rejects { OrezTrainingLabPackage.read(fixture(mutateBodies = { bodies ->
            val quantization = JSONObject(bodies.getValue("artifacts/quantization_manifest.json").toString(Charsets.UTF_8))
            quantization.getJSONObject("output").put("bytes", 43)
            bodies["artifacts/quantization_manifest.json"] = canonical(quantization).toByteArray()
        })) }
    }
    @Test fun recomputedBodyHashesCannotHideMismatchedDataset() {
        rejects { OrezTrainingLabPackage.read(fixture(mutateBodies = { bodies ->
            val evaluation = JSONObject(bodies.getValue("artifacts/evaluation_report.json").toString(Charsets.UTF_8))
            evaluation.getJSONObject("dataset_manifest").put("sha256", "c".repeat(64))
            bodies["artifacts/evaluation_report.json"] = canonical(evaluation).toByteArray()
        })) }
    }
    @Test fun selfDeclaredDirectoryDigestMustCommitExactSortedInventory() {
        rejects { OrezTrainingLabPackage.read(fixture(mutateBodies = { bodies ->
            val quantization = JSONObject(bodies.getValue("artifacts/quantization_manifest.json").toString(Charsets.UTF_8))
            quantization.getJSONObject("base").put("manifest_sha256", "c".repeat(64))
            bodies["artifacts/quantization_manifest.json"] = canonical(quantization).toByteArray()
        })) }
    }
    @Test fun duplicateJsonKeysAndPermissiveJsonExtensionsAreRejected() {
        rejects { OrezLabJson.objectFrom("{\"a\":1,\"a\":2}".toByteArray()) }
        rejects { OrezLabJson.objectFrom("{a:1}".toByteArray()) }
        rejects { OrezLabJson.objectFrom("{\"a\":+1}".toByteArray()) }
        rejects { OrezLabJson.objectFrom("{\"a\":1,}".toByteArray()) }
    }
    @Test fun finiteHostMetricsDoNotBecomeAndroidNativeQualification() {
        assertEquals(6, OrezTrainingLabPackage.read(fixture(mutateBodies = { bodies ->
            val evaluation = JSONObject(bodies.getValue("artifacts/evaluation_report.json").toString(Charsets.UTF_8))
            evaluation.put("wall_seconds", 0.000003)
            bodies["artifacts/evaluation_report.json"] = canonical(evaluation).toByteArray()
        })).artifactCount)
    }

    @Test fun baselineHostSourceDoesNotRequireInventedMergeOrTraining() {
        assertEquals(6, OrezTrainingLabPackage.read(fixture()).artifactCount)
    }
    @Test fun optionalMergeMustProduceTheActualEvaluatedSource() {
        rejects { OrezTrainingLabPackage.read(fixture(mutateBodies = { bodies ->
            val merge = JSONObject().put("schema", "orez-host-merge-v1").put("status", "COMPLETED").put("runtime", "HOST_TRANSFORMERS")
                .put("android_qualification", "PENDING").put("base", directory("original.safetensors")).put("adapter", directory("adapter.safetensors"))
                .put("output", directory("unrelated.safetensors")).put("code", directory("common.py"))
            bodies["artifacts/merge_manifest.json"] = canonical(merge).toByteArray()
        })) }
    }
    @Test fun trainingManifestCannotAttachAnUnmergedAdapterToBaselineWeights() {
        rejects { OrezTrainingLabPackage.read(fixture(mutateBodies = { bodies ->
            val training = JSONObject().put("schema", "orez-host-training-v1").put("status", "COMPLETED").put("runtime", "HOST_TRANSFORMERS")
                .put("android_qualification", "PENDING").put("base", directory()).put("output", directory("adapter.safetensors"))
                .put("dataset_manifest", identity(bodies.getValue("artifacts/dataset_manifest.json"))).put("code", directory("common.py"))
            bodies["artifacts/training_manifest.json"] = canonical(training).toByteArray()
        })) }
    }
    @Test fun validLinkedMergeAndTrainingRemainHostPending() {
        val verified = OrezTrainingLabPackage.read(fixture(mutateBodies = { bodies ->
            val merge = JSONObject().put("schema", "orez-host-merge-v1").put("status", "COMPLETED").put("runtime", "HOST_TRANSFORMERS")
                .put("android_qualification", "PENDING").put("base", directory("original.safetensors")).put("adapter", directory("adapter.safetensors"))
                .put("output", directory()).put("code", directory("common.py"))
            val training = JSONObject().put("schema", "orez-host-training-v1").put("status", "COMPLETED").put("runtime", "HOST_TRANSFORMERS")
                .put("android_qualification", "PENDING").put("base", directory("original.safetensors")).put("output", directory("adapter.safetensors"))
                .put("dataset_manifest", identity(bodies.getValue("artifacts/dataset_manifest.json"))).put("code", directory("common.py"))
            bodies["artifacts/merge_manifest.json"] = canonical(merge).toByteArray()
            bodies["artifacts/training_manifest.json"] = canonical(training).toByteArray()
        }))
        assertEquals("HOST_TRANSFORMERS", verified.runtime); assertEquals(8, verified.artifactCount)
    }

    @Test fun portableCanonicalJsonMatchesPublisherPythonSlashAndUnicodeRules() {
        val value = JSONObject().put("size", 42).put("path", "子/😀.json")
        assertEquals("{\"path\":\"子/😀.json\",\"size\":42}", OrezLabJson.canonical(value))
        assertTrue(OrezLabJson.comparePaths("\uE000", "😀") < 0)
    }

}
