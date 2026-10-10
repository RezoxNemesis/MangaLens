package com.mangalens.orez

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Host training/evaluation provenance remains separate from Android native evaluation receipts. */
internal object OrezTrainingLabPackage {
    const val MANIFEST = "lab-manifest.json"
    private val artifactPath = Regex("artifacts/[A-Za-z0-9_-]{1,96}\\.(json|txt|bin)")
    private val hash = Regex("[a-f0-9]{64}")
    private val roles = setOf("DATASET_MANIFEST", "TRAINING_MANIFEST", "EVALUATION_REPORT", "BASE_LICENSE", "OUTPUT_LICENSE", "CODE_MANIFEST", "QUANTIZATION_MANIFEST", "MERGE_MANIFEST")
    data class Verified(val normalizedBytes: ByteArray, val model: OrezModelPin, val runtime: String, val artifactCount: Int, val entryNames: Set<String>)
    private fun keys(value: JSONObject, expected: Set<String>) {
        if (value.keys().asSequence().toSet() != expected) throw IOException("Unknown/missing lab manifest keys")
    }
    private fun text(value: JSONObject, name: String): String = (value.get(name) as? String)?.takeIf { it.length <= 128 }
        ?: throw IOException("Invalid lab manifest text")
    private fun number(value: JSONObject, name: String): Long {
        val raw = value.get(name)
        if (raw !is Int && raw !is Long) throw IOException("Invalid lab manifest integer")
        return (raw as Number).toLong()
    }
    private fun canonical(value: Any): String = OrezLabJson.canonical(value)

    private fun bounded(zip: ZipInputStream, limit: Int, check: () -> Unit): ByteArray {
        val result = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            check(); val count = zip.read(buffer)
            if (count < 0) break
            if (count == 0 || result.size().toLong() + count > limit) throw IOException("Lab body bound/progress")
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }
    fun read(raw: ByteArray, check: () -> Unit = {}): Verified {
        if (raw.size > OrezEvaluationPackageCodec.MAX_PACKAGE_BYTES) throw IOException("Lab package bound")
        val output = ByteArrayOutputStream()
        return ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            val first = zip.nextEntry ?: throw IOException("Empty lab package")
            if (first.name != MANIFEST || first.isDirectory) throw IOException("Lab manifest must be first")
            val manifestRaw = bounded(zip, OrezModelEvaluationValidator.MAX_RECEIPT_BYTES, check)
            val jsonText = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(manifestRaw)).toString() }
            catch (error: java.nio.charset.CharacterCodingException) { throw IOException("Lab manifest UTF-8", error) }
            var depth = 0; var quoted = false; var escaped = false
            for (character in jsonText) {
                if (quoted) {
                    if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
                } else when (character) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; if (depth > 3) throw IOException("Lab manifest nesting bound") }
                    '}', ']' -> { depth--; if (depth < 0) throw IOException("Lab manifest nesting") }
                }
            }
            if (quoted || depth != 0) throw IOException("Incomplete lab manifest")
            val manifest = JSONObject(jsonText)
            // Require deterministic JSON, including unique keys and integral numeric spelling.
            val canonical = canonical(manifest)
            if (jsonText != canonical && jsonText != "$canonical\n") throw IOException("Lab manifest must use canonical sorted compact JSON")
            keys(manifest, setOf("schema", "kind", "model", "runtime", "android_qualification", "artifacts"))
            if (number(manifest, "schema") != 1L || text(manifest, "kind") != "OREZ_LOCAL_TRAINING_EVIDENCE" ||
                text(manifest, "android_qualification") != "PENDING") throw IOException("Unsupported or falsely qualified lab manifest")
            val modelObject = manifest.get("model") as? JSONObject ?: throw IOException("Missing lab output identity")
            keys(modelObject, setOf("model_id", "sha256", "bytes"))
            val model = OrezModelPin(text(modelObject, "model_id"), text(modelObject, "sha256"), number(modelObject, "bytes"))
            if (!OrezPinnedModelPolicy.valid(model)) throw IOException("Invalid lab output identity")
            val runtime = text(manifest, "runtime")
            if (runtime !in setOf("HOST_TRANSFORMERS", "HOST_LLAMA_CPP")) throw IOException("Unsupported host lab runtime")
            val inventory = manifest.get("artifacts") as? JSONArray ?: throw IOException("Missing lab artifact inventory")
            if (inventory.length() !in 1..OrezModelEvaluationValidator.MAX_ARTIFACTS) throw IOException("Lab artifact count bound")
            data class Entry(val size: Int, val digest: String, val role: String)
            val expected = LinkedHashMap<String, Entry>(); val seenRoles = HashSet<String>(); var volume = 0L
            for (i in 0 until inventory.length()) {
                check(); val artifact = inventory.get(i) as? JSONObject ?: throw IOException("Invalid lab artifact")
                keys(artifact, setOf("path", "role", "bytes", "sha256"))
                val path = text(artifact, "path"); val role = text(artifact, "role"); val digest = text(artifact, "sha256"); val size = number(artifact, "bytes")
                if (!path.matches(artifactPath) || role !in roles || !seenRoles.add(role) || !digest.matches(hash) || size !in 1..OrezModelEvaluationValidator.MAX_BODY_BYTES.toLong() || expected.containsKey(path)) throw IOException("Invalid/duplicate lab artifact")
                volume += size
                if (volume > OrezModelEvaluationValidator.MAX_TOTAL_BODY_BYTES) throw IOException("Lab volume bound")
                expected[path] = Entry(size.toInt(), digest, role)
            }
            val actual = LinkedHashMap<String, ByteArray>()
            while (true) {
                check(); val entry = zip.nextEntry ?: break
                val expectedBody = expected[entry.name] ?: throw IOException("Unexpected lab archive entry")
                if (entry.isDirectory || actual.containsKey(entry.name)) throw IOException("Duplicate lab entry")
                val body = bounded(zip, expectedBody.size, check)
                if (body.size != expectedBody.size || OrezEvaluationCanonical.sha256(body) != expectedBody.digest) throw IOException("Corrupt lab artifact body")
                actual[entry.name] = body
            }
            if (actual.keys != expected.keys || expected.values.none { it.role == "BASE_LICENSE" } || expected.values.none { it.role == "CODE_MANIFEST" }) throw IOException("Lab license/code provenance missing")
            OrezTrainingLabLineage.validate(model, runtime, actual.mapKeys { expected.getValue(it.key).role }, check)
            ZipOutputStream(output).use { result ->
                fun put(name: String, body: ByteArray) { check(); result.putNextEntry(ZipEntry(name).apply { time = 0L }); result.write(body); result.closeEntry() }
                put(MANIFEST, canonical.toByteArray(Charsets.UTF_8)); actual.toSortedMap().forEach { (path, body) -> put(path, body) }
            }
            Verified(output.toByteArray(), model, runtime, actual.size, actual.keys.toSet() + MANIFEST)
        }
    }
}
