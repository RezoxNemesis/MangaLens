package com.mangalens.core.search.embedding

/** Resource order is actual creation order; Result owns output tensors and is closed first. */
internal object SemanticNativeResources {
    fun closeReverse(resources: List<AutoCloseable>, owner: Any) {
        for (index in resources.indices.reversed()) try { resources[index].close() } catch (close: Throwable) {
            // Never try a second close on a resource whose real native release is unknown.
            throw SemanticNativeCloseUnproven(listOf(owner) + resources.take(index + 1), close)
        }
    }
}
