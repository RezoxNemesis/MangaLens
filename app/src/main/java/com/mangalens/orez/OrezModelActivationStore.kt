package com.mangalens.orez

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class OrezVerifiedArtifact(
    val file: File,
    val sha256: String,
    val modelId: String,
    val bytes: Long,
    val metadata: OrezGgufMetadata
)

/**
 * Immutable weights and one atomic per-tier activation journal. No existing model is
 * overwritten or deleted. Receipts alone never grant readiness after process restart.
 */
internal class OrezModelActivationStore(
    private val root: File,
    private val beforeManifestCommit: () -> Unit = {},
    private val legacyPin: OrezModelDescriptor = OrezModelCatalog.legacy
) {
    private data class Record(val modelId: String, val bytes: Long, val sha256: String, val path: String)
    private data class Journal(val active: Record?, val previous: Record? = null, val working: Record? = null, val previousUsable: Boolean = true)
    private data class Stamp(val bytes: Long, val modified: Long, val fileKey: String?)
    private data class Verified(val stamp: Stamp, val artifact: OrezVerifiedArtifact)
    private data class Checked(val stamp: Stamp?, val error: String?)
    private val verified = ConcurrentHashMap<String, Verified>()
    private val checked = ConcurrentHashMap<String, Checked>()
    private val lock = locks.computeIfAbsent(root.canonicalPath) { Any() }

    fun adoptExisting(
        slot: String,
        file: File,
        descriptor: OrezModelDescriptor,
        checkpoint: () -> Unit = {}
    ): OrezVerifiedArtifact {
        validateSlot(slot)
        scoped(file)
        val artifact = verify(file, descriptor.id, descriptor.bytes, descriptor.sha256, requirements(slot), checkpoint)
        checkpoint()
        synchronized(lock) { commitActivation(slot, artifact) }
        return artifact
    }

    /** Legacy packs must match the authentic historical publisher pin, not a first-observation hash. */
    fun adoptLegacy(file: File, bytes: Long, checkpoint: () -> Unit = {}): OrezVerifiedArtifact {
        scoped(file)
        if (bytes != legacyPin.bytes) throw OrezModelCompatibilityException("Legacy model size does not match its trusted publisher pin. Its file was kept; select the pinned Lite model.")
        val artifact = verify(file, legacyPin.id, legacyPin.bytes, legacyPin.sha256, requirements(LEGACY_SLOT), checkpoint)
        checkpoint()
        synchronized(lock) { commitActivation(LEGACY_SLOT, artifact) }
        return artifact
    }

    fun activate(
        slot: String,
        file: File,
        descriptor: OrezModelDescriptor,
        checkpoint: () -> Unit = {}
    ): OrezVerifiedArtifact {
        validateSlot(slot)
        scoped(file)
        val candidate = verify(file, descriptor.id, descriptor.bytes, descriptor.sha256, requirements(slot), checkpoint)
        checkpoint()
        val artifact = synchronized(lock) {
            val directory = File(root, "verified/$slot")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create model storage. Free space and resume.")
            val candidatePath = scoped(candidate.file)
            val referenced = SLOTS.any { knownSlot ->
                readJournal(knownSlot)?.let { journal ->
                    listOfNotNull(journal.active, journal.working, journal.previous).any { it.path == candidatePath }
                } == true
            }
            if (referenced || candidate.file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) {
                // Published/saved verified bytes keep their identity, including UUID collision paths.
                // A failed journal commit must never remove an active, working, or peer mmap path.
                commitActivation(slot, candidate)
                return@synchronized candidate
            }
            var destination = File(directory, candidate.sha256 + ".gguf")
            // Reuse only a file verified in this process. Never replace a potentially mmap'd inode.
            val existing = cached(destination, candidate.sha256)
            if (destination.exists() && existing == null) {
                destination = File(directory, candidate.sha256 + "-" + UUID.randomUUID() + ".gguf")
            }
            val promoted = existing ?: run {
                Files.move(file.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                candidate.copy(file = destination).also { cache(it) }
            }
            // A crash here leaves an unadvertised, recoverable blob and the old journal intact.
            commitActivation(slot, promoted)
            promoted
        }
        return artifact
    }

    fun runtimeArtifacts(slot: String): List<OrezVerifiedArtifact> {
        validateSlot(slot)
        val journal = synchronized(lock) { readJournal(slot) } ?: return emptyList()
        return listOfNotNull(journal.active, journal.working, journal.previous.takeIf { journal.previousUsable })
            .distinctBy { it.path }
            .filter { trustedRecord(slot, it) }
            .mapNotNull { cached(resolve(it.path), it.sha256) }
    }

    fun runtimeFiles(slot: String): List<File> = runtimeArtifacts(slot).map { it.file }

    fun hasJournal(slot: String): Boolean = synchronized(lock) { readJournal(slot) != null }

    /** This discovers saved bytes, not an installed model; activate() must still verify them. */
    fun savedCandidate(slot: String, descriptor: OrezModelDescriptor): File? {
        validateSlot(slot)
        val prefix = descriptor.sha256.lowercase()
        return File(root, "verified/$slot").listFiles()?.asSequence()?.take(256)
            ?.firstOrNull { file ->
                file.isFile && file.length() == descriptor.bytes &&
                    checkIssue(file) == null &&
                    (file.name == "$prefix.gguf" || (file.name.startsWith("$prefix-") && file.name.endsWith(".gguf")))
            }
    }

    fun isChecked(file: File): Boolean = checked[file.canonicalPath]?.let { it.stamp == stamp(file) } == true

    fun checkIssue(file: File): String? = checked[file.canonicalPath]?.takeIf { it.stamp == stamp(file) }?.error

    fun needsVerification(): Boolean = SLOTS.any { slot ->
        val journal = synchronized(lock) { readJournal(slot) } ?: return@any false
        listOfNotNull(journal.active, journal.working, journal.previous).any { !isChecked(resolve(it.path)) }
    }

    fun markWorking(file: File) {
        val path = scoped(file)
        synchronized(lock) {
            for (slot in SLOTS) {
                val journal = readJournal(slot) ?: continue
                val record = listOfNotNull(journal.active, journal.previous, journal.working)
                    .firstOrNull { trustedRecord(slot, it) && it.path == path && cached(file, it.sha256) != null } ?: continue
                if (journal.working != record) writeJournal(slot, journal.copy(working = record))
                return
            }
        }
    }

    fun canRollback(slot: String): Boolean = synchronized(lock) {
        val journal = readJournal(slot) ?: return@synchronized false
        rollbackTarget(journal) != null
    }

    fun rollback(slot: String, checkpoint: () -> Unit = {}): Boolean {
        validateSlot(slot)
        val original = synchronized(lock) { readJournal(slot) } ?: return false
        val target = synchronized(lock) { rollbackTarget(original) } ?: return false
        try {
            verifyRecord(slot, target, checkpoint)
        } catch (_: IOException) { return false }
        catch (_: OrezModelCompatibilityException) { return false }
        checkpoint()
        return synchronized(lock) {
            if (readJournal(slot) != original) return@synchronized false
            writeJournal(slot, Journal(target, original.active, target, previousUsable = false))
            true
        }
    }

    /** Hash and structural checks run outside the journal lock; UI reads never wait for hashing. */
    fun revalidate(checkpoint: () -> Unit = {}, force: Boolean = false): List<String> {
        val errors = mutableListOf<String>()
        for (slot in SLOTS) {
            checkpoint()
            val journal = synchronized(lock) { readJournal(slot) }
            if (journal == null) {
                if (File(root, "$slot.activation").exists()) errors += "$slot: saved model activation metadata is invalid. Recheck model health; all model files were kept."
                continue
            }
            val records = listOfNotNull(journal.active, journal.working, journal.previous).distinctBy { it.path }
            for (record in records) {
                if (!trustedRecord(slot, record)) {
                    errors += "$slot: legacy activation has no matching trusted publisher pin. Its file was kept; select the pinned Lite model."
                    continue
                }
                if (!force && isChecked(resolve(record.path))) {
                    checkIssue(resolve(record.path))?.let { errors += "$slot: $it" }
                    continue
                }
                try { verifyRecord(slot, record, checkpoint) }
                catch (failure: IOException) { errors += "$slot: ${failure.message}" }
                catch (failure: OrezModelCompatibilityException) { errors += "$slot: ${failure.message}" }
            }
            if (journal.active?.let { cached(resolve(it.path), it.sha256) } == null) {
                // Restore a verified prior artifact after a damaged or interrupted update.
                synchronized(lock) {
                    if (readJournal(slot) == journal) {
                        rollbackTarget(journal)?.let { target ->
                            writeJournal(slot, Journal(target, journal.active, target, previousUsable = false))
                        }
                    }
                }
            }
        }
        return errors
    }

    private fun rollbackTarget(journal: Journal): Record? = listOfNotNull(journal.working, journal.previous.takeIf { journal.previousUsable })
        .firstOrNull { it != journal.active && cached(resolve(it.path), it.sha256) != null }

    private fun trustedRecord(slot: String, record: Record): Boolean = slot != LEGACY_SLOT ||
        (record.modelId == legacyPin.id && record.bytes == legacyPin.bytes && record.sha256.equals(legacyPin.sha256, true))

    private fun verifyRecord(slot: String, record: Record, checkpoint: () -> Unit): OrezVerifiedArtifact {
        if (!trustedRecord(slot, record)) throw OrezModelCompatibilityException("Legacy activation does not match the trusted publisher pin. Its file was kept.")
        return verify(resolve(record.path), record.modelId, record.bytes, record.sha256, requirements(slot), checkpoint)
    }

    private fun verify(
        file: File,
        modelId: String,
        bytes: Long,
        expectedSha: String,
        requirements: OrezModelRequirements,
        checkpoint: () -> Unit
    ): OrezVerifiedArtifact {
        val cacheKey = file.canonicalPath
        try {
            checkpoint()
            verified.remove(cacheKey)
            val before = stamp(file) ?: throw IOException("Model file is missing. Existing model files were kept.")
            if (before.bytes != bytes || bytes <= 0L) throw OrezModelCompatibilityException("Model size mismatch. Download again to resume or replace the incomplete pack.")
            if (!SHA.matches(expectedSha)) throw OrezModelCompatibilityException("Invalid model integrity pin.")
            val metadata = OrezModelCompatibility.inspect(file, requirements)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(expectedSha, true)) throw OrezModelCompatibilityException("Model integrity check failed. The working model was kept; download this pack again.")
            if (before != stamp(file)) throw IOException("Model changed during verification. Retry without replacing the working model.")
            return OrezVerifiedArtifact(file, sha, modelId, bytes, metadata).also { cache(it) }
        } catch (failure: IOException) {
            checked[cacheKey] = Checked(stamp(file), failure.message)
            throw failure
        } catch (failure: OrezModelCompatibilityException) {
            checked[cacheKey] = Checked(stamp(file), failure.message)
            throw failure
        }
    }

    private fun commitActivation(slot: String, artifact: OrezVerifiedArtifact) {
        val record = Record(artifact.modelId, artifact.bytes, artifact.sha256, scoped(artifact.file))
        val previous = readJournal(slot)
        if (previous?.active == record) return
        writeJournal(slot, Journal(record, previous?.active, previous?.working))
    }

    private fun cache(artifact: OrezVerifiedArtifact) {
        val fingerprint = stamp(artifact.file) ?: throw IOException("Verified model disappeared before activation.")
        verified[artifact.file.canonicalPath] = Verified(fingerprint, artifact)
        checked[artifact.file.canonicalPath] = Checked(fingerprint, null)
    }

    private fun cached(file: File, sha: String): OrezVerifiedArtifact? {
        val value = verified[file.canonicalPath] ?: return null
        return value.artifact.takeIf { it.sha256 == sha && value.stamp == stamp(file) }
    }

    private fun stamp(file: File): Stamp? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).takeIf { it.isRegularFile }
            ?.let { Stamp(it.size(), it.lastModifiedTime().toMillis(), it.fileKey()?.toString()) }
    } catch (_: IOException) { null }

    private fun scoped(file: File): String {
        val base = root.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        if (!path.startsWith(base) || path == base) throw OrezModelCompatibilityException("Model path is outside private model storage.")
        return base.relativize(path).toString()
    }

    private fun resolve(path: String): File = File(root, path).also { scoped(it) }

    private fun readJournal(slot: String): Journal? {
        validateSlot(slot)
        val file = File(root, "$slot.activation")
        if (!file.isFile || file.length() > 16_384L) return null
        return try {
            val data = Properties().apply { file.inputStream().use { load(it) } }
            if (data.getProperty("schema") != "1" || data.getProperty("verifier") != OrezModelCompatibility.VERIFIER_VERSION.toString()) return null
            val catalog = data.getProperty("catalog_manifest", "1").toIntOrNull() ?: return null
            if (catalog !in 1..OrezModelCatalog.MANIFEST_VERSION) return null
            fun record(prefix: String): Record? {
                val path = data.getProperty("$prefix.path") ?: return null
                resolve(path)
                val id = data.getProperty("$prefix.id") ?: return null
                val sha = data.getProperty("$prefix.sha256") ?: return null
                val bytes = data.getProperty("$prefix.bytes")?.toLongOrNull() ?: return null
                if (!SHA.matches(sha) || bytes <= 0L || id.length > 256) return null
                return Record(id, bytes, sha, path)
            }
            val active = record("active") ?: return null
            Journal(active, record("previous"), record("working"), data.getProperty("fallback_previous", "true") == "true")
        } catch (_: IOException) { null }
        catch (_: OrezModelCompatibilityException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    private fun writeJournal(slot: String, journal: Journal) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot save model activation. Free storage and retry.")
        val data = Properties().apply {
            setProperty("schema", "1")
            setProperty("verifier", OrezModelCompatibility.VERIFIER_VERSION.toString())
            setProperty("catalog_manifest", OrezModelCatalog.MANIFEST_VERSION.toString())
            setProperty("fallback_previous", journal.previousUsable.toString())
            fun put(prefix: String, record: Record?) {
                record ?: return
                setProperty("$prefix.id", record.modelId)
                setProperty("$prefix.path", record.path)
                setProperty("$prefix.sha256", record.sha256)
                setProperty("$prefix.bytes", record.bytes.toString())
            }
            put("active", journal.active); put("previous", journal.previous); put("working", journal.working)
        }
        val pending = File(root, "$slot.activation-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(pending).use { output -> data.store(output, "OREZ verified model activation"); output.fd.sync() }
            beforeManifestCommit()
            Files.move(pending.toPath(), File(root, "$slot.activation").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }

    private fun validateSlot(slot: String) {
        require(slot in SLOTS) { "Unsupported OREZ model slot" }
    }

    private fun requirements(slot: String): OrezModelRequirements =
        OrezModelRequirements(fileTypes = if (slot == LEGACY_SLOT) setOf(18) else setOf(15))

    companion object {
        const val LEGACY_SLOT = "LEGACY"
        private val SLOTS = listOf("LITE", "CORE", LEGACY_SLOT)
        private val SHA = Regex("[a-fA-F0-9]{64}")
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
