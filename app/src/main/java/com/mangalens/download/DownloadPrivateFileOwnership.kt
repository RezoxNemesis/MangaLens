package com.mangalens.download

import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class DownloadFilesBusyException : IllegalStateException(
    "This transfer is still releasing its files. Try again after it has stopped.")

internal class DownloadUnprovenFileOwnershipException : IllegalStateException(
    "An earlier transfer did not prove file release. Its partial files were retained; repeated cleanup cannot safely discard them.")

/** Short per-ID mutations only; never held over network extraction or an active transfer. */
internal object DownloadIdMutationFences {
    private data class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private var registered = 0
    suspend fun <T> withId(id: String, block: suspend () -> T): T {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val entry = synchronized(lock) {
            if (registered >= 64) throw DownloadFilesBusyException()
            entries.getOrPut(id) { Entry() }.also { it.users++; registered++ }
        }
        var acquired = false
        try {
            entry.mutex.lock(); acquired = true
            return block()
        } finally {
            if (acquired) entry.mutex.unlock()
            synchronized(lock) {
                registered--; entry.users--
                if (entry.users == 0) entries.remove(id, entry)
            }
        }
    }
}

internal data class DownloadPrivateFileOwner(val root: File, val id: String)

/**
 * Durable conservative receipts track actual file-owning producers, independently of WorkInfo.
 * A process crash or failed resource close retains its receipt; cleanup refuses unproven release.
 * No scheduler cancellation removes an active producer's receipt.
 */
internal object DownloadPrivateFileOwners {
    private val lock = Any()
    private val memoryLock = Any()
    private val reservations = mutableMapOf<String, DownloadPrivateFileOwner>()
    private val activeReceipts = mutableSetOf<String>()
    // Existing retained durable receipts bound admissions to 64 owners. Each failed transfer
    // keeps its actual response/call, independently of the retired Worker or UI, until restart.
    private val unreleasedTransports = mutableMapOf<String, MutableList<Any>>()
    private val suffix = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.receipt")

    /** Registration is nonblocking memory work; private filesystem IO stays on the bounded producer. */
    fun reserve(owner: DownloadPrivateFileOwner): Reservation = synchronized(memoryLock) {
        require(owner.id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        if (reservations.size >= 64) throw DownloadFilesBusyException()
        val key = UUID.randomUUID().toString()
        reservations[key] = owner
        Reservation(key)
    }

    private fun reserved(owner: DownloadPrivateFileOwner): Boolean = synchronized(memoryLock) {
        reservations.values.any { it.id == owner.id && it.root.absoluteFile.normalize() == owner.root.absoluteFile.normalize() }
    }

    class Reservation internal constructor(private val key: String) : AutoCloseable {
        override fun close() { synchronized(memoryLock) { reservations.remove(key) } }
    }

    fun acquire(owner: DownloadPrivateFileOwner): Lease = synchronized(lock) {
        require(owner.id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val root = checkedRoot(owner.root)
        val directory = File(root, ".owners")
        check(!Files.isSymbolicLink(directory.toPath())) { "File ownership storage is unavailable." }
        check(directory.mkdirs() || directory.isDirectory) { "File ownership storage is unavailable." }
        val receipts = inspectReceipts(directory)
        if (receipts.size >= 64) throw DownloadFilesBusyException()
        val file = File(directory, "${owner.id}.owner-${UUID.randomUUID()}.receipt")
        check(file.createNewFile()) { "Could not retain this transfer's file ownership." }
        synchronized(memoryLock) { activeReceipts.add(file.absolutePath) }
        Lease(file)
    }

    fun hasOwners(owner: DownloadPrivateFileOwner): Boolean {
        if (reserved(owner)) return true
        return synchronized(lock) {
            require(owner.id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            val directory = File(checkedRoot(owner.root), ".owners")
            check(!Files.isSymbolicLink(directory.toPath())) { "File ownership storage is unavailable." }
            if (!directory.exists()) return@synchronized false
            check(directory.isDirectory) { "File ownership storage is unavailable." }
            inspectReceipts(directory).any { file -> file.name.startsWith("${owner.id}.owner-") &&
                suffix.matches(file.name.substringAfter("${owner.id}.owner-")) }
        }
    }

    fun ownershipFailure(owner: DownloadPrivateFileOwner): IllegalStateException {
        if (reserved(owner)) return DownloadFilesBusyException()
        return synchronized(lock) {
            val directory = File(checkedRoot(owner.root), ".owners")
            if (!directory.isDirectory) return@synchronized DownloadFilesBusyException()
            val unproven = inspectReceipts(directory).any {
                it.name.startsWith("${owner.id}.owner-") && synchronized(memoryLock) { it.absolutePath !in activeReceipts }
            }
            if (unproven) DownloadUnprovenFileOwnershipException() else DownloadFilesBusyException()
        }
    }

    fun hasOtherOwners(owner: DownloadPrivateFileOwner, own: Lease): Boolean {
        if (reserved(owner)) return true
        return synchronized(lock) {
            require(owner.id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            val directory = File(checkedRoot(owner.root), ".owners")
            check(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory)
            inspectReceipts(directory).any { it.name.startsWith("${owner.id}.owner-") && it != own.receipt }
        }
    }

    private fun inspectReceipts(directory: File): Array<File> {
        val files = directory.listFiles() ?: error("File ownership storage is unavailable.")
        check(files.size <= 64) { "Too many outstanding file owners; partial files were retained." }
        val identity = Regex("[A-Za-z0-9_-]{1,100}\\.owner-" + suffix.pattern)
        check(files.all { identity.matches(it.name) && !Files.isSymbolicLink(it.toPath()) && it.isFile &&
            it.canonicalFile.parentFile == directory.canonicalFile }) { "File ownership could not be verified; partial files were retained." }
        return files
    }

    private fun checkedRoot(root: File): File {
        check(!Files.isSymbolicLink(root.toPath())) { "Download storage is unavailable." }
        check(root.mkdirs() || root.isDirectory) { "Download storage is unavailable." }
        val canonical = root.canonicalFile
        check(canonical.parentFile == root.parentFile?.canonicalFile) { "Download storage is unavailable." }
        return canonical
    }

    class Lease internal constructor(internal val receipt: File) : AutoCloseable {
        private val finished = AtomicBoolean(false)
        private val unsafe = AtomicBoolean(false)
        /** Called on unproven/failed resource cleanup; never disguise an orphan as safely released. */
        fun retain() { unsafe.set(true) }
        fun retain(resource: Any) {
            unsafe.set(true)
            synchronized(memoryLock) {
                val held = unreleasedTransports.getOrPut(receipt.absolutePath) { mutableListOf() }
                if (held.none { it === resource }) held.add(resource)
            }
        }
        val isUnproven: Boolean get() = unsafe.get()
        override fun close() {
            if (!finished.compareAndSet(false, true)) return
            synchronized(lock) {
                synchronized(memoryLock) { activeReceipts.remove(receipt.absolutePath) }
                if (unsafe.get()) return
                // A failed unlink is also conservative: the durable receipt remains authoritative.
                if (!receipt.delete() && receipt.exists()) unsafe.set(true)
            }
        }
    }
}
