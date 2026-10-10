package com.mangalens.orez.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/** Bounded opaque committed hints only. Journals remain authoritative; no page/tool authority. */
internal object NativeLibraryMetadataHints {
    data class Hint(val chapterId: String,val sequence: Long)
    private val guard = Any(); private var sequence = 0L
    private val mutable = MutableStateFlow<List<Hint>>(emptyList())
    val states = mutable.asStateFlow()
    private val refresh = MutableStateFlow(0L)
    val refreshes = refresh.asStateFlow()
    fun requestRefresh() = synchronized(guard) { refresh.value = Math.addExact(refresh.value,1L) }
    fun committed(chapterId: String) = synchronized(guard) {
        require(chapterId.matches(Regex("[a-f0-9]{32}")))
        sequence = Math.addExact(sequence,1L)
        mutable.value = Collections.unmodifiableList((mutable.value.filterNot { it.chapterId == chapterId } + Hint(chapterId,sequence)).takeLast(24))
    }
}
