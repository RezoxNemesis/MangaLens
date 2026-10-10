package com.mangalens.orez

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Local evidence exchange only. Parsed bytes never populate the reviewed-result registry. */
internal class OrezEvaluationPackage(val receipt: OrezEvaluationReceipt, bodies: Map<OrezEvaluationArtifactKey, ByteArray>) {
    init { require(bodies.size <= OrezModelEvaluationValidator.MAX_ARTIFACTS && bodies.values.sumOf { it.size.toLong() } <= OrezModelEvaluationValidator.MAX_TOTAL_BODY_BYTES) }
    private val snapshots = bodies.mapValues { it.value.copyOf() }
    fun bodyKeys(): Set<OrezEvaluationArtifactKey> = snapshots.keys.toSet()
    fun bodies(): Map<OrezEvaluationArtifactKey, ByteArray> = snapshots.mapValues { it.value.copyOf() }
    fun status(): OrezEvaluationValidation = OrezModelEvaluationValidator().validate(receipt, snapshots,
        OrezEvaluationExpectation(receipt.binding, receipt.cases.map { OrezEvaluationExpectedCase(it.id, it.sourceSha256, it.formattedInputSha256) }))
}

/** Schema 1 is strict ordered binary UTF-8, independent of JSON duplicate-key/coercion behavior. */
internal object OrezEvaluationReceiptCodec {
    private const val MAGIC = 0x4f455631 // OEV1
    private const val MAX_TEXT_BYTES = 1024
    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.text(): String {
        val size = readInt()
        if (size !in 0..MAX_TEXT_BYTES) throw IOException("Evidence text bound")
        val raw = ByteArray(size); readFully(raw)
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
        catch (error: java.nio.charset.CharacterCodingException) { throw IOException("Invalid evidence UTF-8", error) }
    }
    // Read the token once: predicates must not consume a new token for each enum member.
    private inline fun <reified T : Enum<T>> DataInputStream.namedEnum(): T {
        val name = text(); return enumValues<T>().singleOrNull { it.name == name } ?: throw IOException("Unknown evidence enum")
    }
    fun encode(receipt: OrezEvaluationReceipt): ByteArray {
        require(receipt.cases.size <= OrezModelEvaluationValidator.MAX_CASES && receipt.artifacts.size <= OrezModelEvaluationValidator.MAX_ARTIFACTS)
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out ->
            out.writeInt(MAGIC); out.writeInt(receipt.schemaVersion)
            out.text(receipt.binding.model.modelId); out.text(receipt.binding.model.sha256); out.writeLong(receipt.binding.model.bytes)
            OrezEvaluationField.entries.forEach { out.text(it.name); out.text(receipt.binding[it]) }
            val p = receipt.profile
            out.text(p.id); out.text(p.inputProfileRevision); out.text(p.runtimeSourceRevision)
            when (val minimum = p.minimumApp) {
                OrezMinimumApp.NotDeclared -> out.writeInt(0)
                is OrezMinimumApp.Declared -> { out.writeInt(1); out.writeInt(minimum.minimumVersionCode) }
            }
            out.writeInt(p.governanceSchemaVersion); out.text(p.task.name)
            out.writeBoolean(receipt.smoke); out.writeInt(receipt.passedCases); out.writeInt(receipt.failedCases); out.writeInt(receipt.cancelledCases)
            out.writeInt(receipt.cases.size)
            receipt.cases.forEach { c ->
                out.text(c.id); out.text(c.sourceSha256); out.text(c.formattedInputSha256); out.text(c.outcome.name); out.text(c.termination)
                out.writeInt(c.promptTokens); out.writeInt(c.generatedTokens); out.writeInt(c.tokenLimit); out.writeInt(c.contextTokens)
                out.writeInt(c.threads); out.writeInt(c.batch); out.writeInt(c.microBatch); out.text(c.sampler)
                out.writeLong(c.elapsedMs); out.writeLong(c.peakResidentBytes); out.text(c.observation.name)
            }
            out.writeInt(receipt.artifacts.size)
            receipt.artifacts.forEach { a -> out.text(a.key.caseId); out.text(a.key.role.name); out.writeLong(a.bytes); out.text(a.sha256) }
            out.text(receipt.artifactRootSha256)
        }
        return raw.toByteArray().also { require(it.size <= OrezModelEvaluationValidator.MAX_RECEIPT_BYTES) }
    }
    fun decode(raw: ByteArray): OrezEvaluationReceipt {
        if (raw.size > OrezModelEvaluationValidator.MAX_RECEIPT_BYTES) throw IOException("Evidence receipt bound")
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != 1) throw IOException("Unsupported evidence schema")
            val model = OrezModelPin(input.text(), input.text(), input.readLong())
            val fields = OrezEvaluationField.entries.associateWith { field ->
                if (input.text() != field.name) throw IOException("Evidence field order/schema mismatch")
                input.text()
            }
            val id = input.text(); val profile = input.text(); val runtime = input.text()
            val minimum = when (input.readInt()) { 0 -> OrezMinimumApp.NotDeclared; 1 -> OrezMinimumApp.Declared(input.readInt()); else -> throw IOException("Unknown minimum-app state") }
            val captured = OrezEvaluationProfile(id, profile, runtime, minimum, input.readInt(), input.namedEnum())
            val smoke = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw IOException("Invalid Boolean") }
            val pass = input.readInt(); val fail = input.readInt(); val cancelled = input.readInt()
            val count = input.readInt(); if (count !in 1..OrezModelEvaluationValidator.MAX_CASES) throw IOException("Evidence case bound")
            val cases = List(count) { OrezEvaluationCase(input.text(), input.text(), input.text(), input.namedEnum(), input.text(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.text(),
                input.readLong(), input.readLong(), input.namedEnum()) }
            val artifactCount = input.readInt(); if (artifactCount !in 1..OrezModelEvaluationValidator.MAX_ARTIFACTS) throw IOException("Evidence inventory bound")
            val artifacts = List(artifactCount) { OrezEvaluationArtifact(OrezEvaluationArtifactKey(input.text(), input.namedEnum()), input.readLong(), input.text()) }
            val root = input.text()
            if (input.read() != -1) throw IOException("Trailing evidence receipt fields")
            OrezEvaluationReceipt(1, OrezEvaluationBinding(model, fields), captured, cases, artifacts, root, pass, fail, cancelled, smoke)
        }
    }
}

internal object OrezEvaluationPackageCodec {
    const val MAX_PACKAGE_BYTES = 32 * 1024 * 1024
    private const val RECEIPT = "receipt.bin"
    private val id = Regex("[A-Za-z0-9._:-]{1,128}")
    private fun path(key: OrezEvaluationArtifactKey): String {
        if (key.caseId != OrezEvaluationCanonical.SCOPE_ID && !key.caseId.matches(id)) throw IOException("Invalid evidence case identity")
        // Encode identifiers; valid legacy IDs such as '..' remain identities, never ZIP paths.
        val folder = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key.caseId.toByteArray(Charsets.UTF_8))
        return "artifacts/$folder/${key.role.name}.bin"
    }
    fun entryNames(value: OrezEvaluationPackage): Set<String> = value.bodyKeys().map(::path).toSet() + RECEIPT
    private fun bounded(input: InputStream, maximum: Int, checkCancelled: () -> Unit): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            checkCancelled(); val count = input.read(buffer)
            if (count < 0) break
            if (count == 0 || out.size().toLong() + count > maximum) throw IOException("Evidence stream bound/progress")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
    fun read(input: InputStream, checkCancelled: () -> Unit = {}): OrezEvaluationPackage {
        var compressed = 0L
        val counted = object : java.io.FilterInputStream(input) {
            override fun read(): Int { checkCancelled(); val value = super.read(); if (value >= 0 && ++compressed > MAX_PACKAGE_BYTES) throw IOException("Evidence package bound"); return value }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                checkCancelled(); val count = `in`.read(b, off, len)
                if (count > 0) { compressed += count; if (compressed > MAX_PACKAGE_BYTES) throw IOException("Evidence package bound") }; return count
            }
        }
        return ZipInputStream(counted).use { zip ->
            val first = zip.nextEntry ?: throw IOException("Empty evidence package")
            if (first.name != RECEIPT || first.isDirectory) throw IOException("Evidence receipt must be first")
            val receipt = OrezEvaluationReceiptCodec.decode(bounded(zip, OrezModelEvaluationValidator.MAX_RECEIPT_BYTES, checkCancelled))
            val expected = receipt.artifacts.associateBy { path(it.key) }
            if (expected.size != receipt.artifacts.size) throw IOException("Duplicate or ambiguous evidence inventory")
            if (receipt.artifacts.any { it.bytes !in 0..OrezModelEvaluationValidator.MAX_BODY_BYTES.toLong() } || receipt.artifacts.sumOf { it.bytes } > OrezModelEvaluationValidator.MAX_TOTAL_BODY_BYTES) throw IOException("Declared evidence volume bound")
            var total = 0L
            val bodies = LinkedHashMap<OrezEvaluationArtifactKey, ByteArray>()
            while (true) {
                checkCancelled(); val entry = zip.nextEntry ?: break
                val artifact = expected[entry.name] ?: throw IOException("Unexpected evidence entry")
                if (entry.isDirectory || bodies.containsKey(artifact.key)) throw IOException("Duplicate evidence entry")
                if (artifact.bytes !in 0..OrezModelEvaluationValidator.MAX_BODY_BYTES.toLong()) throw IOException("Evidence body bound")
                val body = bounded(zip, artifact.bytes.toInt(), checkCancelled)
                total += body.size
                if (total > OrezModelEvaluationValidator.MAX_TOTAL_BODY_BYTES) throw IOException("Evidence total bound")
                bodies[artifact.key] = body
            }
            val result = OrezEvaluationPackage(receipt, bodies)
            val validation = result.status()
            if (validation.state == OrezEvaluationState.INVALID) throw IOException(validation.reason)
            result
        }
    }
    fun write(value: OrezEvaluationPackage, output: OutputStream, checkCancelled: () -> Unit = {}) {
        if (value.status().state == OrezEvaluationState.INVALID) throw IOException("Invalid evidence package")
        ZipOutputStream(output).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                checkCancelled(); zip.putNextEntry(ZipEntry(name).apply { time = 0L }); zip.write(bytes); zip.closeEntry()
            }
            put(RECEIPT, OrezEvaluationReceiptCodec.encode(value.receipt))
            value.bodies().entries.sortedBy { path(it.key) }.forEach { put(path(it.key), it.value) }
        }
    }
}
