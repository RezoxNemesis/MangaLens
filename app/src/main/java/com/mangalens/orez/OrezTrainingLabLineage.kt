package com.mangalens.orez

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Structural correspondence of retained receipts, never verification of absent weight/dataset bytes. */
internal object OrezTrainingLabLineage {
    private val digest = Regex("[a-f0-9]{64}")
    private fun keys(value: JSONObject, names: Set<String>) {
        if (value.keys().asSequence().toSet() != names) throw IOException("Unknown/incomplete lineage identity")
    }
    private fun text(value: JSONObject, name: String): String = value.get(name) as? String ?: throw IOException("Invalid lineage text")
    private fun integer(value: JSONObject, name: String): Long {
        val raw = value.get(name); if (raw !is Int && raw !is Long) throw IOException("Invalid lineage integer")
        return (raw as Number).toLong()
    }
    private fun identity(value: JSONObject): Pair<String, Long> {
        keys(value, setOf("sha256", "bytes"))
        val sha = text(value, "sha256"); val bytes = integer(value, "bytes")
        if (!sha.matches(digest) || bytes !in 0..16L * 1024 * 1024 * 1024) throw IOException("Invalid lineage artifact identity")
        return sha to bytes
    }
    private fun canonical(value: Any): String = OrezLabJson.canonical(value)

    private fun directory(value: JSONObject, check: () -> Unit): String {
        keys(value, setOf("files", "manifest_sha256"))
        val files = value.get("files") as? JSONArray ?: throw IOException("Missing directory inventory")
        if (files.length() !in 1..4096) throw IOException("Directory inventory bound")
        val seen = HashSet<String>(); var previous: String? = null; var volume = 0L
        for (index in 0 until files.length()) {
            check(); val file = files.get(index) as? JSONObject ?: throw IOException("Invalid directory item")
            keys(file, setOf("path", "sha256", "bytes"))
            val path = text(file, "path")
            if (path.length !in 1..512 || path.startsWith('/') || path.contains('\\') || path.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
                !seen.add(path) || previous?.let { OrezLabJson.comparePaths(it, path) >= 0 } == true) throw IOException("Invalid/unsorted directory path")
            previous = path
            val item = JSONObject().put("sha256", file.get("sha256")).put("bytes", file.get("bytes"))
            volume += identity(item).second
            if (volume > 64L * 1024 * 1024 * 1024) throw IOException("Directory declared volume bound")
        }
        val actual = OrezEvaluationCanonical.sha256((canonical(files) + "\n").toByteArray(Charsets.UTF_8))
        if (text(value, "manifest_sha256") != actual) throw IOException("Directory inventory digest mismatch")
        return canonical(value)
    }
    private fun pendingRun(value: JSONObject, schema: String, runtime: String) {
        if (text(value, "schema") != schema || text(value, "status") != "COMPLETED" || text(value, "runtime") != runtime ||
            text(value, "android_qualification") != "PENDING") throw IOException("Unsupported or falsely qualified host run")
    }
    fun validate(model: OrezModelPin, runtime: String, bodies: Map<String, ByteArray>, check: () -> Unit = {}) {
        val required = setOf("DATASET_MANIFEST", "EVALUATION_REPORT", "QUANTIZATION_MANIFEST", "BASE_LICENSE", "OUTPUT_LICENSE", "CODE_MANIFEST")
        if (!bodies.keys.containsAll(required)) throw IOException("Host dataset/evaluation/quantization/license/code lineage missing")
        fun objectFor(role: String): JSONObject = OrezLabJson.objectFrom(bodies.getValue(role), check)
        val quantization = objectFor("QUANTIZATION_MANIFEST")
        pendingRun(quantization, "orez-host-quantization-v1", "HOST_LLAMA_CPP")
        if (identity(quantization.getJSONObject("output")) != (model.sha256 to model.bytes)) throw IOException("Quantization output differs from declared GGUF identity")
        val base = directory(quantization.getJSONObject("base"), check)
        identity(quantization.getJSONObject("converter")); identity(quantization.getJSONObject("quantizer"))
        if (text(quantization, "quantization") !in setOf("Q4_K_M", "Q5_K_M", "Q8_0", "Q3_K_M")) throw IOException("Unsupported captured quantization name")
        directory(quantization.getJSONObject("code"), check)
        val evaluation = objectFor("EVALUATION_REPORT")
        pendingRun(evaluation, "orez-host-evaluation-v1", "HOST_TRANSFORMERS")
        if (runtime != "HOST_TRANSFORMERS" || directory(evaluation.getJSONObject("model"), check) != base) throw IOException("Host evaluation source differs from quantization input")
        val datasetRaw = bodies.getValue("DATASET_MANIFEST"); val dataset = objectFor("DATASET_MANIFEST")
        if (text(dataset, "schema") != "orez-training-dataset-v1" || text(dataset, "status") != "PREPARED" ||
            identity(evaluation.getJSONObject("dataset_manifest")) != (OrezEvaluationCanonical.sha256(datasetRaw) to datasetRaw.size.toLong())) throw IOException("Host evaluation dataset identity mismatch")
        val heldOut = dataset.getJSONObject("splits").getJSONObject("held_out")
        val evaluatedHeldOut = evaluation.getJSONObject("held_out")
        if (canonical(heldOut) != canonical(evaluatedHeldOut) || integer(heldOut, "records") < 1 || integer(evaluation, "cases") !in 1..integer(heldOut, "records")) throw IOException("Host held-out evaluation scope mismatch")
        val cases = integer(evaluation, "cases"); val errors = integer(evaluation, "errors"); val exact = integer(evaluation, "exact_matches")
        if (errors !in 0..cases || exact !in 0..(cases - errors)) throw IOException("Contradictory host result denominator")
        directory(evaluation.getJSONObject("code"), check); directory(objectFor("CODE_MANIFEST"), check)
        bodies["MERGE_MANIFEST"]?.let {
            val merge = objectFor("MERGE_MANIFEST"); pendingRun(merge, "orez-host-merge-v1", "HOST_TRANSFORMERS")
            if (directory(merge.getJSONObject("output"), check) != base) throw IOException("Merged output differs from evaluated/quantized source")
            directory(merge.getJSONObject("adapter"), check); directory(merge.getJSONObject("base"), check); directory(merge.getJSONObject("code"), check)
        }
        bodies["TRAINING_MANIFEST"]?.let {
            if (!bodies.containsKey("MERGE_MANIFEST")) throw IOException("Training provenance requires corresponding merged output")
            val training = objectFor("TRAINING_MANIFEST"); pendingRun(training, "orez-host-training-v1", "HOST_TRANSFORMERS")
            val merge = objectFor("MERGE_MANIFEST")
            if (directory(training.getJSONObject("output"), check) != directory(merge.getJSONObject("adapter"), check) ||
                directory(training.getJSONObject("base"), check) != directory(merge.getJSONObject("base"), check) ||
                identity(training.getJSONObject("dataset_manifest")) != identity(evaluation.getJSONObject("dataset_manifest"))) throw IOException("Training adapter/base/dataset lineage mismatch")
            directory(training.getJSONObject("code"), check)
        }
        check() // Correspondence only; actual GGUF inference and Android approval remain pending.
    }
}
