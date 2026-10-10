package com.mangalens.core.translation.memory

import java.util.Collections

/** Read-only current profile values; this is never native origin/edit authority. */
internal class MemoryPreparedProfileRead(profile: SeriesMemoryProfile, private val lease: MemoryReadDeliveryLease) {
    val profile = profile.copy(glossary = Collections.unmodifiableList(profile.glossary.map { term ->
        term.copy(aliases = Collections.unmodifiableList(term.aliases.toList()))
    }))
    fun tryDeliver(accept: (SeriesMemoryProfile) -> Boolean): Boolean = lease.tryCommit { accept(profile) } == true
}
internal data class MemoryGlossaryMetadataHit(val read: MemoryPreparedProfileRead, val term: SeriesGlossaryTerm)
internal class MemoryGlossaryMetadataBatch(val query: String, hits: List<MemoryGlossaryMetadataHit>,
    val incomplete: Boolean, val profilesScanned: Int, val receivedBytes: Long) {
    val hits = Collections.unmodifiableList(hits.toList())
    init { require(hits.size <= 16 && profilesScanned <= 128 && receivedBytes in 0..16L * 1024 * 1024) }
}
internal object MemoryGlossaryMetadataQuery {
    fun matches(term: SeriesGlossaryTerm, query: String): Boolean = (listOf(term.source, term.preferred) + term.aliases)
        .any { memoryTextKey(it).contains(memoryTextKey(query)) }
    fun query(value: String): String = value.trim().also {
        require(it.length in 1..160 && it.none { character -> character.code < 32 || character.code == 127 })
    }
}
