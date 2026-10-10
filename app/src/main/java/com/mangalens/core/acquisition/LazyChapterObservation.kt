package com.mangalens.core.acquisition

/** Native-owned bounded observation state; network request order never supplies page order. */
internal data class ChapterObservationViewport(val top: Long, val height: Long, val total: Long) {
    init { require(top in 0..MAX_COORDINATE && height in 1..MAX_COORDINATE && total in 1..MAX_COORDINATE) }
    val atBottom: Boolean get() = top + height >= total - 4
    companion object { const val MAX_COORDINATE = 1_000_000_000L }
}

internal class LazyChapterObservation {
    data class Decision(val stop: Boolean, val limited: Boolean)
    var images: List<ChapterImageCandidate> = emptyList(); private set
    var observations = 0; private set
    var orderConflict = false; private set
    var limited = false; private set
    private var retainedChars = 0L
    private var stable = 0
    private var previousHeight: Long? = null
    private var previousVideos = -1
    fun observe(next: List<ChapterImageCandidate>, viewport: ChapterObservationViewport?, videoCount: Int, extractionLimited: Boolean = false): Decision {
        limited = limited || extractionLimited || next.size >= ChapterImageCandidates.MAX_IMAGES || videoCount >= 500
        check(observations < MAX_OBSERVATIONS)
        observations++
        val old = images
        val positions = old.withIndex().associate { it.value.url to it.index }
        val distinct = next.distinctBy { it.url }.take(ChapterImageCandidates.MAX_IMAGES)
        val existingPositions = distinct.mapNotNull { positions[it.url] }
        if (existingPositions.zipWithNext().any { (a, b) -> a >= b }) orderConflict = true
        // Each new row is anchored before the next actually observed existing node.
        // This preserves earlier virtualized rows and inserts late earlier/middle nodes.
        val additions = mutableMapOf<String?, MutableList<ChapterImageCandidate>>()
        var anchor: String? = null
        var retainedCount = old.size
        for (candidate in distinct.asReversed()) {
            if (positions.containsKey(candidate.url)) { anchor = candidate.url; continue }
            if (candidate.url.length > ChapterImageCandidates.MAX_URL_CHARS || retainedCount >= ChapterImageCandidates.MAX_IMAGES ||
                retainedChars + candidate.url.length > MAX_RETAINED_URL_CHARS) { limited = true; continue }
            additions.getOrPut(anchor) { mutableListOf() }.add(candidate)
            retainedCount++; retainedChars += candidate.url.length
        }
        images = buildList {
            for (candidate in old) { addAll(additions[candidate.url].orEmpty().asReversed()); add(candidate) }
            addAll(additions[null].orEmpty().asReversed())
        }
        val changed = old != images || viewport?.total != previousHeight || videoCount != previousVideos
        stable = if (viewport?.atBottom == true && !changed && !orderConflict && !limited) stable + 1 else 0
        previousHeight = viewport?.total; previousVideos = videoCount
        val stableEnd = stable >= STABLE_OBSERVATIONS && (images.isNotEmpty() || videoCount > 0)
        val boundedEnd = observations == MAX_OBSERVATIONS
        limited = limited || boundedEnd && !stableEnd
        return Decision(stableEnd || boundedEnd, limited)
    }
    companion object {
        const val MAX_OBSERVATIONS = 24
        const val STABLE_OBSERVATIONS = 3
        const val INITIAL_WAIT_MS = 2500L
        const val WAIT_MS = 400L
        const val MAX_RETAINED_URL_CHARS = 1_400_000L
    }
}
