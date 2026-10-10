package com.mangalens.ui.video

internal data class BrowserCaptionAuthorityKey(
    val sourceResolutionId: String,
    val sourceFingerprint: String,
    val configFingerprint: String
)

internal data class BrowserCaptionTaskBinding(
    val key: BrowserCaptionAuthorityKey,
    val taskId: String,
    val generation: String
)

internal class BrowserCaptionOperation internal constructor(
    internal val authority: BrowserCaptionAuthority,
    internal val nonce: String,
    val key: BrowserCaptionAuthorityKey
)

internal class BrowserCaptionAuthorityRetired : IllegalStateException(
    "The browser caption source changed. Start source captions again for the current video."
)

/**
 * The accepted browser operation is process memory only. Constructing a Store from a valid saved
 * journal never grants authority. A live DOM capture begins an operation; Store binds its freshly
 * created exact generation before the journal and WorkManager request can be published.
 *
 * Main revocation acquires only this short lock. It never touches Store, journals or WorkManager.
 * Commit actions must only atomically rename already-fsynced bytes and publish the corresponding
 * Store state. Serialization, writes, fsync, exports and asynchronous cancellation stay outside it.
 */
internal class BrowserCaptionAuthority {
    private data class Entry(val operation: BrowserCaptionOperation, var binding: BrowserCaptionTaskBinding? = null)
    private val authorityLock = Any()
    private var current: Entry? = null
    private val lastBoundGeneration = LinkedHashMap<String, String>()

    fun begin(key: BrowserCaptionAuthorityKey): BrowserCaptionOperation {
        require(key.sourceResolutionId.matches(ID) && key.sourceFingerprint.matches(HASH) && key.configFingerprint.matches(HASH))
        val operation = BrowserCaptionOperation(this, java.util.UUID.randomUUID().toString().replace("-", ""), key)
        synchronized(authorityLock) { current = Entry(operation) }
        return operation
    }

    fun bind(operation: BrowserCaptionOperation, taskId: String, generation: String): Boolean {
        require(taskId.matches(ID) && generation.matches(ID))
        val binding = BrowserCaptionTaskBinding(operation.key, taskId, generation)
        synchronized(authorityLock) {
            val entry = current?.takeIf { owns(it, operation) } ?: return false
            if (entry.binding != null) return entry.binding == binding
            // A new operation cannot revive the last generation of the same durable task.
            // Actual Store start/resume additionally creates a fresh generation from its captured
            // current task; only Store calls bind in production. Never evict this bounded ledger.
            if (lastBoundGeneration[taskId] == generation) return false
            require(taskId in lastBoundGeneration || lastBoundGeneration.size < 32) {
                "Browser caption history is full. Existing subtitles have been kept."
            }
            lastBoundGeneration[taskId] = generation
            entry.binding = binding
            return true
        }
    }

    fun revoke(operation: BrowserCaptionOperation): BrowserCaptionTaskBinding? = synchronized(authorityLock) {
        val entry = current?.takeIf { owns(it, operation) } ?: return null
        current = null
        entry.binding
    }

    fun permits(binding: BrowserCaptionTaskBinding): Boolean = synchronized(authorityLock) {
        current?.binding == binding
    }

    fun <T> commit(binding: BrowserCaptionTaskBinding, action: () -> T): T = synchronized(authorityLock) {
        if (current?.binding != binding) throw BrowserCaptionAuthorityRetired()
        action()
    }

    private fun owns(entry: Entry, operation: BrowserCaptionOperation): Boolean =
        operation.authority === this && entry.operation.nonce == operation.nonce && entry.operation.key == operation.key

    companion object {
        private val ID = Regex("[a-f0-9]{32}")
        private val HASH = Regex("[a-f0-9]{64}")
    }
}
