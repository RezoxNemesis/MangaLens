package com.mangalens.orez

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

internal data class OrezLabEntry(val digest: String, val model: OrezModelPin?, val description: String, val bytes: Long)

/** App-private quarantine, deliberately disconnected from model activation, retrieval and tasks. */
internal class OrezTrainingLabStore(private val directory: File) {
    companion object { const val MAX_PACKAGES = 8 }
    private val digestPattern = Regex("[a-f0-9]{64}")
    private fun packageFile(digest: String): File {
        if (!digest.matches(digestPattern)) throw IOException("Invalid lab package identity")
        return File(directory, "$digest.zip")
    }
    private fun readBounded(input: InputStream, check: () -> Unit): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            check(); val count = input.read(buffer)
            if (count < 0) break
            if (count == 0 || output.size().toLong() + count > OrezEvaluationPackageCodec.MAX_PACKAGE_BYTES) throw IOException("Lab package bound/progress")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private data class Decoded(val bytes: ByteArray, val model: OrezModelPin?, val description: String)
    private fun decode(raw: ByteArray, check: () -> Unit): Decoded {
        val names = OrezLabZipEnvelope.validate(raw, check)
        val first = ZipInputStream(ByteArrayInputStream(raw)).use { it.nextEntry?.name }
        return if (first == OrezTrainingLabPackage.MANIFEST) {
            val value = OrezTrainingLabPackage.read(raw, check)
            if (names != value.entryNames) throw IOException("Lab ZIP central inventory differs from actual bodies")
            Decoded(raw, value.model, "${value.runtime}: ${value.artifactCount} provenance artifacts; Android evaluation pending")
        } else {
            val value = OrezEvaluationPackageCodec.read(ByteArrayInputStream(raw), check)
            if (names != OrezEvaluationPackageCodec.entryNames(value)) throw IOException("Lab ZIP central inventory differs from actual bodies")
            Decoded(raw, value.receipt.binding.model,
                "${value.status().reason}; report scope supplied by author; qualification pending")
        }
    }
    @Synchronized fun importPackage(input: InputStream, check: () -> Unit = {}): OrezLabEntry {
        val value = decode(readBounded(input, check), check)
        val digest = OrezEvaluationCanonical.sha256(value.bytes); val destination = packageFile(digest)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create private lab store")
        if (!destination.exists() && directory.listFiles().orEmpty().count { it.name.matches(Regex("[a-f0-9]{64}\\.zip")) } >= MAX_PACKAGES) throw IOException("Lab storage is full. Remove a package first.")
        val temporary = File(directory, ".${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                var offset = 0
                while (offset < value.bytes.size) {
                    check(); val size = minOf(8192, value.bytes.size - offset); output.write(value.bytes, offset, size); offset += size
                }
                output.flush(); output.fd.sync()
            }
            check() // Final cancellation boundary before one atomic, private-quarantine promotion.
            if (!temporary.renameTo(destination)) throw IOException("Cannot save private lab package")
        } finally { temporary.delete() }
        return OrezLabEntry(digest, value.model, value.description, value.bytes.size.toLong())
    }
    @Synchronized fun list(check: () -> Unit = {}): List<OrezLabEntry> = directory.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[a-f0-9]{64}\\.zip")) }.take(MAX_PACKAGES).map { file ->
            check(); val digest = file.name.removeSuffix(".zip")
            try {
                val raw = file.inputStream().use { readBounded(it, check) }
                if (OrezEvaluationCanonical.sha256(raw) != digest) throw IOException("Private lab package failed integrity check")
                val value = decode(raw, check)
                OrezLabEntry(digest, value.model, value.description, raw.size.toLong())
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { OrezLabEntry(digest, null, "Stored package could not be verified. Remove it or reimport verified evidence.", file.length()) }
        }.sortedBy { it.digest }
    @Synchronized fun export(digest: String, check: () -> Unit = {}): ByteArray {
        val raw = packageFile(digest).inputStream().use { readBounded(it, check) }
        if (OrezEvaluationCanonical.sha256(raw) != digest) throw IOException("Private lab package failed integrity check")
        decode(raw, check); check(); return raw
    }
    @Synchronized fun delete(digest: String) {
        val file = packageFile(digest)
        if (file.exists() && !file.delete()) throw IOException("Cannot remove lab package")
    }
}
