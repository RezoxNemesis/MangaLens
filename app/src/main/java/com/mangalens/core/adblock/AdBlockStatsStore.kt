package com.mangalens.core.adblock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

data class BlockEvent(val timestamp: Long, val host: String, val pageHost: String, val type: String, val rule: String, val thirdParty: Boolean)

data class AdBlockStats(
    val blockedRequests: Long = 0,
    val knownBytesSaved: Long = 0,
    val blockedDomains: List<String> = emptyList(),
    val events: List<BlockEvent> = emptyList()
)

class AdBlockStatsStore {
    companion object {
        val shared = AdBlockStatsStore()
    }

    private val events = java.util.ArrayDeque<BlockEvent>()
    private val blockedRequests = AtomicLong(0)
    private val knownBytesSaved = AtomicLong(0)
    private val domains = Collections.synchronizedSet(linkedSetOf<String>())
    private val _stats = MutableStateFlow(AdBlockStats())
    val stats: StateFlow<AdBlockStats> = _stats.asStateFlow()

    @Synchronized
    fun recordBlocked(host: String, knownBytes: Long = 0, pageHost: String = "", type: String = "unknown", rule: String = "local rule") {
        events.addLast(BlockEvent(System.currentTimeMillis(), host, pageHost, type, rule,
            pageHost.isNotBlank() && host != pageHost && !host.endsWith(".$pageHost")))
        while (events.size > 200) events.removeFirst()
        blockedRequests.incrementAndGet()
        if (knownBytes > 0) knownBytesSaved.addAndGet(knownBytes)
        if (host.isNotBlank()) synchronized(domains) {
            domains.add(host)
            while (domains.size > 100) domains.remove(domains.first())
        }
        publish()
    }

    @Synchronized
    fun reset() {
        events.clear()
        blockedRequests.set(0)
        knownBytesSaved.set(0)
        synchronized(domains) { domains.clear() }
        publish()
    }

    private fun publish() {
        _stats.value = AdBlockStats(
            blockedRequests = blockedRequests.get(),
            knownBytesSaved = knownBytesSaved.get(),
            blockedDomains = synchronized(domains) { domains.toList().asReversed() },
            events = events.toList().asReversed()
        )
    }
}
