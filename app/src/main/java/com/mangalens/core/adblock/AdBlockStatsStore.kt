package com.mangalens.core.adblock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

data class AdBlockStats(
    val blockedRequests: Long = 0,
    val knownBytesSaved: Long = 0,
    val blockedDomains: List<String> = emptyList()
)

class AdBlockStatsStore {
    companion object {
        val shared = AdBlockStatsStore()
    }

    private val blockedRequests = AtomicLong(0)
    private val knownBytesSaved = AtomicLong(0)
    private val domains = Collections.synchronizedSet(linkedSetOf<String>())
    private val _stats = MutableStateFlow(AdBlockStats())
    val stats: StateFlow<AdBlockStats> = _stats.asStateFlow()

    fun recordBlocked(host: String, knownBytes: Long = 0) {
        blockedRequests.incrementAndGet()
        if (knownBytes > 0) knownBytesSaved.addAndGet(knownBytes)
        if (host.isNotBlank()) synchronized(domains) {
            domains.add(host)
            while (domains.size > 100) domains.remove(domains.first())
        }
        publish()
    }

    fun reset() {
        blockedRequests.set(0)
        knownBytesSaved.set(0)
        synchronized(domains) { domains.clear() }
        publish()
    }

    private fun publish() {
        _stats.value = AdBlockStats(
            blockedRequests = blockedRequests.get(),
            knownBytesSaved = knownBytesSaved.get(),
            blockedDomains = synchronized(domains) { domains.toList().asReversed() }
        )
    }
}
