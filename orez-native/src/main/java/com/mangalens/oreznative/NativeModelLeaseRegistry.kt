package com.mangalens.oreznative

internal data class ModelFileIdentity(
    val canonicalPath: String,
    val size: Long,
    val modifiedAt: Long,
    val device: Long,
    val inode: Long,
)

/** Coordinates feature leases on JNI's single physical model. */
internal class NativeModelLeaseRegistry<Owner : Any> {
    @Volatile private var model: ModelFileIdentity? = null
    @Volatile private var ownerSnapshot: List<Owner> = emptyList()
    private val holders = linkedSetOf<Owner>()

    // Android memory/UI observers must not wait for the IO/native load monitor.
    val sharedPath: String? get() = model?.canonicalPath

    fun owners(): List<Owner> = ownerSnapshot

    @Synchronized
    fun acquire(owner: Owner, identity: ModelFileIdentity, load: () -> Boolean): Boolean {
        val current = model
        if (current != null) {
            if (current != identity) return false
            holders.add(owner)
            ownerSnapshot = holders.toList()
            return true
        }
        // Publish pending ownership so a memory trim can schedule its close without waiting.
        holders.add(owner)
        ownerSnapshot = holders.toList()
        try {
            if (load()) {
                model = identity
                return true
            }
            return false
        } finally {
            if (model == null) {
                holders.remove(owner)
                ownerSnapshot = holders.toList()
            }
        }
    }

    @Synchronized
    fun release(owner: Owner, unload: () -> Unit) {
        if (!holders.remove(owner)) return
        ownerSnapshot = holders.toList()
        if (holders.isNotEmpty()) return
        try {
            unload()
        } finally {
            model = null
        }
    }
}
