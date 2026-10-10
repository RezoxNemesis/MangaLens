package com.mangalens.core.translation.inpainting

internal class LaMaIoCloseUnproven(val retained: List<Any>, cause: Throwable) : IllegalStateException(
    "Repair pack storage or transfer cleanup could not be confirmed. Restart the app before changing this pack.", cause)

/** Small actual producer/independent-closer acknowledgment ledger, guarded by the manager monitor. */
internal class LaMaTransferReleaseLedger {
    private val pending = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    private var returned = false
    private var unproven = false
    fun registered(ticket: Any) { check(!returned); check(pending.add(ticket)) }
    fun producerReturned() { returned = true }
    fun closeReturned(ticket: Any) { check(pending.remove(ticket)) }
    fun closeUnproven() { unproven = true }
    val mayRelease: Boolean get() = returned && pending.isEmpty() && !unproven
}

/** Real close failure retains the descriptor/stream and any nested incomplete cleanup owner. */
internal inline fun <T : AutoCloseable, R> withLaMaIoHandle(handle: T, body: (T) -> R): R {
    var failedBody: Throwable? = null
    try { return body(handle) }
    catch (problem: Throwable) { failedBody = problem; throw problem }
    finally {
        try { handle.close() } catch (problem: Throwable) {
            throw LaMaIoCloseUnproven(listOfNotNull(handle, failedBody), problem)
        }
    }
}
