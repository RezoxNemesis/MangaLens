package com.mangalens.orez

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Tiny authoritative control record. Call persist on IO before changing WorkManager's store. */
internal class OrezModelTransferJournal(
    private val file: File,
    initial: OrezModelTransferIdentity = OrezModelTransferIdentity(null, false),
    private val beforeCommit: () -> Unit = {}
) {
    @Volatile var identity: OrezModelTransferIdentity = read(initial)
        private set
    val exists: Boolean get() = file.isFile

    fun persist(next: OrezModelTransferIdentity) {
        if (exists && next == identity) return
        val directory = file.parentFile ?: throw IOException("Model transfer journal has no directory.")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot save model transfer controls. Free storage and retry.")
        val data = Properties().apply {
            setProperty("schema", "1")
            setProperty("active", next.active.toString())
            setProperty("pending_enqueue", next.pendingEnqueue.toString())
            next.id?.let { setProperty("id", it) }
            next.workId?.let { setProperty("work_id", it) }
            next.tier?.let { setProperty("tier", it) }
        }
        val pending = File(directory, file.name + "." + UUID.randomUUID() + ".tmp")
        try {
            FileOutputStream(pending).use { data.store(it, "OREZ model transfer control"); it.fd.sync() }
            beforeCommit()
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            identity = next
        } finally { pending.delete() }
    }

    private fun read(initial: OrezModelTransferIdentity): OrezModelTransferIdentity {
        if (!file.exists()) return initial
        return try {
            require(file.length() in 1..MAX_BYTES) { "Invalid model transfer journal size." }
            val data = Properties().apply { file.inputStream().use(::load) }
            require(data.getProperty("schema") == "1")
            val id = data.getProperty("id")?.also { UUID.fromString(it) }
            val workId = data.getProperty("work_id")?.also { UUID.fromString(it) } ?: id
            val active = data.getProperty("active")?.toBooleanStrict() ?: error("Missing transfer state.")
            require(!active || id != null)
            OrezModelTransferIdentity(id, active, data.getProperty("pending_enqueue")?.toBooleanStrict() ?: false,
                data.getProperty("tier"), workId)
        } catch (_: Exception) {
            // Unknown/corrupt controls cannot grant a worker ownership; preserve every model byte.
            OrezModelTransferIdentity(null, false)
        }
    }

    companion object { private const val MAX_BYTES = 16_384L }
}
